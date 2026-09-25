#!/usr/bin/env python3
"""Restricted staging receiver. Install with receive_beget.sh outside public_html.

The SSH key cannot execute an arbitrary command; it can only submit a verified
release. The installer does not directly replace this receiver or shared config.
Deployed PHP runs as the hosting user: this is not an OS-level sandbox.
"""

from __future__ import annotations

import fcntl
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from datetime import datetime, timezone

MAX_ARCHIVE_BYTES = 5 * 1024 * 1024
MAX_UNPACKED_BYTES = 20 * 1024 * 1024
PUBLIC_FILES = (
    "index.html", "terms.html", "privacy.html", "success.html", "app.js",
    "reset-logic.js", "api/index.php", "api/health.php",
)


def private_allowed(name: str) -> bool:
    return name in ("private/backend/bootstrap.php", "private/backend/config.example.php") or bool(
        re.fullmatch(r"private/backend/(?:src/[A-Za-z0-9_/-]+\.php|bin/[A-Za-z0-9_-]+\.php|migrations/[A-Za-z0-9_-]+\.sql)", name)
    )


def unpack(payload: bytes, release: str, digest: str) -> tuple[dict[str, bytes], dict]:
    if hashlib.sha256(payload).hexdigest() != digest:
        raise ValueError("Archive digest mismatch")
    files: dict[str, bytes] = {}
    size = 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        for item in archive:
            name = item.name
            if not item.isfile() or name in files:
                raise ValueError("Only unique regular files are allowed")
            parts = PurePosixPath(name).parts
            if name.startswith("/") or any(p in (".", "..") for p in parts) or "\\" in name:
                raise ValueError("Unsafe archive path")
            if name not in ("manifest.json", *("public/" + p for p in PUBLIC_FILES)) and not private_allowed(name):
                raise ValueError("Unexpected file in deployment archive")
            size += item.size
            if size > MAX_UNPACKED_BYTES or len(files) > 100:
                raise ValueError("Archive exceeds limits")
            stream = archive.extractfile(item)
            assert stream is not None
            files[name] = stream.read()
    manifest = json.loads(files.pop("manifest.json"))
    if manifest.get("format") != 1 or manifest.get("mode") != "staging" or manifest.get("release") != release:
        raise ValueError("Not a matching staging release")
    required = {"public/" + p for p in PUBLIC_FILES} | {"private/backend/bootstrap.php"}
    if not required <= set(files) or set(manifest.get("files", {})) != set(files):
        raise ValueError("Incomplete manifest")
    for name, content in files.items():
        if hashlib.sha256(content).hexdigest() != manifest["files"][name]:
            raise ValueError("Manifest digest mismatch")
    for name in ("index.html", "terms.html", "privacy.html", "success.html"):
        if b'id="rr-staging-banner"' not in files["public/" + name] or b'noindex,nofollow' not in files["public/" + name]:
            raise ValueError("Missing staging protection")
    return files, manifest


def checked_run(args: list[str], env: dict | None = None) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True, env=env, timeout=30)
    if result.returncode:
        # PHP errors can contain connection details: do not copy stdout/stderr to CI.
        raise RuntimeError("PHP validation failed; inspect private server configuration locally")
    return result.stdout


def no_symlink(path: Path, boundary: Path) -> None:
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ValueError("Symlink in a managed directory path")
        if item == boundary:
            return
    raise ValueError("Path is outside managed directory")


def replace_file(path: Path, data: bytes, mode: int = 0o644) -> None:
    fd, temporary = tempfile.mkstemp(prefix=".rr-update-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def switch_link(current: Path, target: str) -> None:
    candidate = current.with_name(".current-next")
    if candidate.exists() or candidate.is_symlink():
        raise ValueError("Stale current-link update: inspect before retry")
    os.symlink(target, candidate)
    os.replace(candidate, current)


def install(private_root: Path, files: dict[str, bytes], manifest: dict, php: str = "php8.3") -> str:
    site_root = private_root.parent
    public_root = site_root / "public_html"
    if private_root.name != "relationship-reset-private" or not public_root.is_dir():
        raise ValueError("Receiver must be installed in <site>/relationship-reset-private/deploy")
    no_symlink(private_root, site_root)
    no_symlink(public_root, site_root)
    if (public_root / "index.php").exists():
        raise ValueError("Existing public_html/index.php: move the Beget placeholder outside public_html before deploying")
    # A first liveness deployment can precede configuration/database setup.
    checked_run([php, "-r", "exit(PHP_VERSION_ID >= 80300 && extension_loaded('pdo_mysql') ? 0 : 1);"])
    release = manifest["release"]
    release_root = private_root / "releases" / release
    backups_root = private_root / "backups"
    for directory in (release_root.parent, backups_root, private_root / "shared"):
        no_symlink(directory, private_root)
        directory.mkdir(mode=0o700, exist_ok=True)
    if release_root.is_symlink():
        raise ValueError("Release path is a symlink")
    expected_manifest = json.dumps(manifest, sort_keys=True).encode()
    if release_root.exists():
        if (release_root / "deployment_manifest.json").read_bytes() != expected_manifest:
            raise ValueError("An immutable release with this SHA already exists with different content")
    else:
        release_root.mkdir(mode=0o700)
        for name, data in files.items():
            if name.startswith("private/"):
                target = release_root / name[len("private/"):]
                target.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
                target.write_bytes(data)
                target.chmod(0o600)
        (release_root / "backend" / "release.json").write_text(json.dumps({"release": release}))
        (release_root / "deployment_manifest.json").write_bytes(expected_manifest)
    for path in (release_root / "backend").rglob("*.php"):
        checked_run([php, "-l", str(path)])
    # Lint public PHP before any public mutation.
    with tempfile.TemporaryDirectory(prefix="lint-", dir=private_root) as lint_dir:
        for name in ("api/index.php", "api/health.php"):
            temporary = Path(lint_dir) / Path(name).name
            temporary.write_bytes(files["public/" + name])
            checked_run([php, "-l", str(temporary)])
    current = private_root / "current"
    if current.exists() and not current.is_symlink():
        raise ValueError("Current must be a release symlink, not a directory")
    old_target = os.readlink(current) if current.is_symlink() else None
    if old_target is not None and not re.fullmatch(r"releases/[0-9a-f]{40}", old_target):
        raise ValueError("Current symlink points outside managed releases")
    for name in PUBLIC_FILES:
        target = public_root / name
        no_symlink(target, public_root)
        if target.exists() and not target.is_file():
            raise ValueError("A managed public path is not a file")
    backup = Path(tempfile.mkdtemp(prefix=release[:12] + "-", dir=backups_root))
    before = {name: (public_root / name).read_bytes() if (public_root / name).exists() else None for name in PUBLIC_FILES}
    old_modes = {name: (public_root / name).stat().st_mode & 0o777 for name, data in before.items() if data is not None}
    for name, data in before.items():
        if data is not None:
            path = backup / "public" / name
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            path.write_bytes(data)
            path.chmod(0o600)
    (backup / "state.json").write_text(json.dumps({"previous_release": old_target, "missing": [n for n, d in before.items() if d is None], "modes": old_modes, "created": datetime.now(timezone.utc).isoformat()}))
    changed: list[str] = []
    switched = False
    try:
        switch_link(current, "releases/" + release)
        switched = True
        (public_root / "api").mkdir(mode=0o755, exist_ok=True)
        for name in (*PUBLIC_FILES[1:], PUBLIC_FILES[0]):
            replace_file(public_root / name, files["public/" + name])
            changed.append(name)
        health = json.loads(checked_run([php, "-r", "$_SERVER['REQUEST_METHOD']='GET'; require $argv[1];", str(public_root / "api" / "health.php")]))
        if health.get("status") != "ok" or health.get("mode") != "staging" or health.get("release") != release:
            raise RuntimeError("Post-deploy CLI health failed")
    except BaseException:
        for name in reversed(changed):
            if before[name] is None:
                (public_root / name).unlink()
            else:
                replace_file(public_root / name, before[name], old_modes[name])
        if switched:
            if old_target is None:
                current.unlink()
            else:
                switch_link(current, old_target)
        raise
    return backup.name


def main() -> None:
    match = re.fullmatch(r"rr-deploy ([0-9a-f]{40}) ([0-9a-f]{64})", os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    if not match:
        raise ValueError("Only the exact staging deployment command is allowed")
    private_root = Path(__file__).resolve().parent.parent
    if private_root.name != "relationship-reset-private":
        raise ValueError("Receiver is not installed at the expected path")
    lock_path = private_root / ".deploy.lock"
    no_symlink(lock_path, private_root)
    with lock_path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        payload = sys.stdin.buffer.read(MAX_ARCHIVE_BYTES + 1)
        if len(payload) > MAX_ARCHIVE_BYTES:
            raise ValueError("Archive exceeds upload limit")
        files, manifest = unpack(payload, match[1], match[2])
        # Runtime choice is local server configuration, never an SSH argument.
        php_file = private_root / "deploy" / "php-command.txt"
        php = php_file.read_text().strip() if php_file.exists() else "php8.3"
        if not re.fullmatch(r"(?:/usr/local/bin/)?php8\.[3-9]", php):
            raise ValueError("Unsupported PHP command; configure php8.3 or newer")
        backup = install(private_root, files, manifest, php)
        print(json.dumps({"status": "deployed", "mode": "staging", "release": match[1], "backup": backup, "checked": "PHP CLI liveness; DB and public HTTPS not implied"}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Fixed classes/messages; no PHP stderr, request data, passwords or config values.
        print(f"Deployment refused: {error}", file=sys.stderr)
        sys.exit(1)
