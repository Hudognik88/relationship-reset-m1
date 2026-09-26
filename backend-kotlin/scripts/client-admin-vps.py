#!/usr/bin/env python3
"""Local owner commands for the synthetic client rehearsal on the fixed VPS."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.request

ORIGIN = 'https://api-staging.poslessory.ru'
SECRETS = Path('/opt/relationship-reset-kotlin-staging/backend-kotlin/.secrets')


class AdminError(Exception):
    pass


def require(value, message):
    if not value:
        raise AdminError(message)


def private_file(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode)
            and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600,
            'Нужен обычный файл владельца root с правами 600.')


def operator_token():
    info = SECRETS.lstat()
    require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode)
            and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o700,
            'Проверьте закрытый каталог настроек VPS.')
    path = SECRETS / 'operator-token'
    private_file(path)
    token = path.read_text().strip()
    require(re.fullmatch('[A-Za-z0-9_-]{43,128}', token), 'Проверьте настройки оператора.')
    return token


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


def request(method, path, payload=None):
    require(path.startswith('/client/operator/'), 'Неподдерживаемый маршрут.')
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    headers = {'Authorization': 'Bearer ' + operator_token(), 'Accept': 'application/json'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(ORIGIN + path, data=data, method=method, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=20) as response:
            body = response.read(65537)
        require(len(body) <= 65536, 'Ответ сервера слишком большой.')
        result = json.loads(body)
        require(isinstance(result, dict), 'Неожиданный ответ сервера.')
        return result
    except urllib.error.HTTPError as error:
        # Never echo response bodies, bearer tokens, submitted text or stack traces.
        raise AdminError('Сервер отклонил запрос (HTTP %s).' % error.code) from None
    except (OSError, ValueError, urllib.error.URLError):
        raise AdminError('Не удалось выполнить запрос. Проверьте HTTPS и готовность кабинета.') from None


def review_text(filename):
    path = Path(filename)
    private_file(path)
    require(path.stat().st_size <= 16000, 'Текст разбора слишком большой.')
    text = path.read_text(encoding='utf-8').strip()
    require(0 < len(text.encode('utf-16-le')) // 2 <= 4000,
            'Разбор должен содержать от 1 до 4000 символов.')
    require(not any(ord(c) < 32 and c not in '\n\r\t' or ord(c) == 127 for c in text),
            'В тексте есть недопустимые управляющие символы.')
    return text


def main():
    parser = argparse.ArgumentParser(description='Только тестовый кабинет и вымышленные данные.')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('invite', help='Одноразовый код приглашения; вывод только в терминал.')
    commands.add_parser('list', help='Последние тестовые анкеты.')
    show = commands.add_parser('show', help='Прочитать одну анкету.')
    show.add_argument('case_id')
    publish = commands.add_parser('publish', help='Опубликовать проверенный человеком тестовый разбор.')
    publish.add_argument('case_id')
    publish.add_argument('file')
    publish.add_argument('--reviewed', action='store_true', required=True)
    args = parser.parse_args()
    require(os.geteuid() == 0, 'Запускайте из терминала VPS от root.')
    if args.command in ('show', 'publish'):
        require(re.fullmatch('[a-f0-9]{32}', args.case_id), 'Неверный идентификатор анкеты.')
    if args.command == 'invite':
        require(sys.stdout.isatty(), 'Код приглашения можно вывести только в интерактивный терминал.')
        result = request('POST', '/client/operator/invitations', {'synthetic': True})
        token = result.get('invitation', '')
        require(isinstance(token, str) and re.fullmatch('[A-Za-z0-9_-]{43}', token),
                'Неожиданный формат приглашения.')
        print('Тестовый кабинет: ' + ORIGIN + '/rehearsal/')
        print('Передайте код только приглашённому участнику теста. Не публикуйте его.')
        print('Одноразовый код: ' + token)
        print('Действует до: ' + str(result.get('expires_at', '')))
    elif args.command == 'list':
        print(json.dumps(request('GET', '/client/operator/cases'), ensure_ascii=False, indent=2))
    elif args.command == 'show':
        print(json.dumps(request('GET', '/client/operator/cases/' + args.case_id), ensure_ascii=False, indent=2))
    else:
        request('POST', '/client/operator/reviews', {
            'case_id': args.case_id, 'text': review_text(args.file), 'synthetic': True,
        })
        print('Проверенный тестовый разбор опубликован в кабинете участника.')


if __name__ == '__main__':
    try:
        main()
    except AdminError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Операция остановлена. Проверьте права файлов и настройки VPS.', file=sys.stderr)
        sys.exit(1)
