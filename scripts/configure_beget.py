#!/usr/bin/env python3
"""Configure the existing Beget staging backend without displaying credentials."""
from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import tempfile
import warnings

AUTH_BEGIN = b"# BEGIN relationship-reset API authorization\n"
AUTH_END = b"# END relationship-reset API authorization\n"
AUTH_BLOCK = (AUTH_BEGIN + b'SetEnvIfNoCase Authorization "^(.+)$" HTTP_AUTHORIZATION=$1\n' + AUTH_END)


class SetupError(Exception):
    pass


def private_file(path: Path) -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise SetupError("Приватный файл должен принадлежать вам и иметь права 600.")


def create_private(path: Path, content: str) -> None:
    # O_EXCL also refuses existing symlinks. Never overwrite working credentials.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(content)
        handle.flush()
        os.fsync(handle.fileno())


def atomic_bytes(path: Path, content: bytes, mode: int) -> None:
    fd, temporary = tempfile.mkstemp(prefix=".rr-config-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
            os.fchmod(handle.fileno(), mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def forward_authorization(site: Path, shared: Path) -> tuple[Path, bytes | None, int] | None:
    public = site / "public_html"
    api = public / "api"
    if public.is_symlink() or api.is_symlink() or not api.is_dir():
        raise SetupError("Каталог API отсутствует или является символической ссылкой; настройка не изменена.")
    path = api / ".htaccess"
    before = None
    mode = 0o644
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode):
            raise SetupError("Файл API .htaccess должен быть обычным файлом; настройка не изменена.")
        before = path.read_bytes()
        mode = stat.S_IMODE(info.st_mode)
    content = before or b""
    if AUTH_BEGIN.rstrip() in content or AUTH_END.rstrip() in content:
        if (content.count(AUTH_BEGIN.rstrip()) != 1 or content.count(AUTH_END.rstrip()) != 1
                or content.count(AUTH_BLOCK) != 1):
            raise SetupError("Обнаружен изменённый блок авторизации API; .htaccess не перезаписан.")
        return None
    if before is not None:
        # A private backup contains only the previous hosting configuration.
        fd, _ = tempfile.mkstemp(prefix="api-htaccess-before-", suffix=".bak", dir=shared)
        with os.fdopen(fd, "wb") as handle:
            handle.write(before)
            handle.flush()
            os.fsync(handle.fileno())
    separator = b"\n" if content and not content.endswith(b"\n") else b""
    atomic_bytes(path, content + separator + AUTH_BLOCK, mode)
    return path, before, mode


def php_run(php: str, args: list[str], env: dict[str, str], payload: str | None = None) -> str:
    result = subprocess.run([php, *args], input=payload, text=True,
                            capture_output=True, env=env, timeout=90)
    if result.returncode:
        # A PHP error may contain a username, path or credential. Do not forward it.
        safe_status = re.search(r"Smoke failed: health=\d+, unauthenticated=\d+, readiness=\d+\.", result.stderr)
        if safe_status:
            raise SetupError(safe_status.group() + " Настройки сохранены; повторите команду после проверки HTTPS/API.")
        raise SetupError("Шаг PHP не выполнен. Настройки сохранены только если это указано выше; секреты не выведены.")
    return result.stdout


def configure(site: Path, database: str, php: str, forward_auth: bool = False) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise SetupError("Некорректное имя базы.")
    site = site.resolve(strict=True)
    private = site / "relationship-reset-private"
    if not (site / "public_html").is_dir() or private.is_symlink():
        raise SetupError("Не найдена ожидаемая папка сайта.")
    if private.resolve().is_relative_to((site / "public_html").resolve()):
        raise SetupError("Приватные настройки не должны находиться внутри публичной папки сайта.")
    backend = (private / "current" / "backend").resolve(strict=True)
    release = backend.parent
    if release.parent != private / "releases" or not re.fullmatch(r"[a-f0-9]{40}", release.name):
        raise SetupError("Не найден установленный выпуск backend.")
    shared = private / "shared"
    if shared.is_symlink():
        raise SetupError("Каталог shared не должен быть символической ссылкой.")
    shared.mkdir(mode=0o700, exist_ok=True)
    os.chmod(shared, 0o700)
    config_path = shared / "config.php"
    token_path = shared / "operator-token.txt"
    env = os.environ.copy()
    for name in ("RR_CONFIG_FILE", "RR_OPERATOR_TOKEN", "RR_LOCAL_TEST"):
        env.pop(name, None)
    env["RR_CONFIG_FILE"] = str(config_path)
    os.umask(0o077)

    # Resolve the release once; a simultaneous deployment cannot change this path.
    bootstrap = str(backend / "bootstrap.php")
    probe = r'''
ini_set('display_errors', '0'); ini_set('log_errors', '0');
try {
    if (PHP_VERSION_ID < 80300 || !extension_loaded('pdo_mysql')) { exit(1); }
    require $argv[1];
    $input = json_decode(stream_get_contents(STDIN), true, 512, JSON_THROW_ON_ERROR);
    $config = $input['resume'] ? rr_config() : $input['config'];
    if ($config['database']['host'] !== 'localhost'
        || $config['database']['name'] !== $input['database']
        || $config['database']['user'] !== $input['database']
        || !hash_equals($config['operator_token_sha256'], $input['hash'])) { exit(1); }
    rr_db($config);
    if (!$input['resume']) { echo "<?php\ndeclare(strict_types=1);\nreturn ", var_export($config, true), ";\n"; }
} catch (Throwable $error) { exit(1); }
'''
    if config_path.exists() or config_path.is_symlink():
        private_file(config_path)
        private_file(token_path)
        token = token_path.read_text(encoding="ascii").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token):
            raise SetupError("Существующий файл токена некорректен; он не изменён.")
        payload = {"resume": True, "database": database,
                   "hash": hashlib.sha256(token.encode()).hexdigest()}
        print("Проверяю существующие настройки, без перезаписи…", flush=True)
        php_run(php, ["-r", probe, bootstrap], env, json.dumps(payload))
    else:
        if token_path.exists() or token_path.is_symlink():
            raise SetupError("Есть файл токена без config.php. Ничего не перезаписано; нужна проверка частичной настройки.")
        if not sys.stdin.isatty():
            raise SetupError("Запустите файл из интерактивного терминала Beget, без перенаправления ввода.")
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            password = getpass.getpass(f"Пароль базы {database} (ввод скрыт): ")
        if not password:
            raise SetupError("Пустой пароль: настройка остановлена.")
        token = secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        config = {"environment": "staging", "operator_token_sha256": token_hash,
                  "database": {"host": "localhost", "port": 3306, "name": database,
                               "user": database, "password": password}}
        print("Проверяю подключение к базе…", flush=True)
        try:
            content = php_run(php, ["-r", probe, bootstrap], env, json.dumps(
                {"resume": False, "database": database, "hash": token_hash, "config": config}))
        except SetupError:
            raise SetupError("Подключение не удалось. Проверьте имя базы, пароль и PHP 8.3 с PDO MySQL. Файлы с реквизитами не созданы.") from None
        # Save only after PDO successfully connects. No password enters shell history.
        create_private(token_path, token + "\n")
        create_private(config_path, content)
        del password, config, content
        print("Приватные настройки сохранены. Пароль и токен не выводятся.", flush=True)

    print("Создаю / проверяю таблицы…", flush=True)
    php_run(php, [str(backend / "bin" / "migrate.php")], env)
    print("Таблицы готовы.", flush=True)
    rollback = forward_authorization(site, shared) if forward_auth else None
    print("Проверяю HTTPS, запрет анонимного доступа и готовность базы…", flush=True)
    env["RR_OPERATOR_TOKEN"] = token
    try:
        php_run(php, [str(backend / "bin" / "smoke.php"), "https://poslessory.ru/api"], env)
    except BaseException:
        if rollback is not None:
            path, before, mode = rollback
            if before is None:
                path.unlink()
            else:
                atomic_bytes(path, before, mode)
            print("Проверка не пройдена: изменение .htaccess отменено. Реквизиты базы и токен сохранены.", flush=True)
        raise
    print("ГОТОВО: база подключена, таблицы созданы, HTTPS/API проверены.")
    print("Режим staging. Клиентские данные и платежи не включены.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    parser.add_argument("database")
    parser.add_argument("--php", default="php8.3")
    parser.add_argument("--forward-authorization", action="store_true",
                        help="Передавать Authorization в PHP через API .htaccess; откатить изменение при ошибке smoke")
    args = parser.parse_args()
    try:
        configure(args.site, args.database, args.php, args.forward_authorization)
        return 0
    except SetupError as error:
        print(str(error), file=sys.stderr)
    except (OSError, ValueError, subprocess.SubprocessError, getpass.GetPassWarning):
        print("Настройка остановлена. Проверьте доступ к файлам, PHP 8.3 и скрытый ввод. Секреты не выведены.", file=sys.stderr)
    except (KeyboardInterrupt, EOFError):
        print("Настройка прервана.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
