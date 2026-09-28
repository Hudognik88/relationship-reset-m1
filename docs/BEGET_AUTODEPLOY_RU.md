# GitHub → Beget: тестовый автодеплой

Продолжение существующего сайта, без нового хостинга и без изменения `main`.
Workflow находится в `.github/workflows/beget-staging.yml`. После настройки он
проверяет код и обновляет тестовую версию при push в `m1-first-payment-20260925`.
До включения переменной `BEGET_DEPLOY_ENABLED=true` выполняются только проверки.
Приём оплат и реальных анкет этим workflow не включается.

## Что уже реализовано

- CI: прежние JS-тесты, PHP 8.3, отдельная MySQL 8.4, backend unit/integration и проверки безопасной доставки.
- Детерминированный архив только из разрешённых публичных файлов и приватного backend. Из HTML формируется прежняя тестовая версия с баннером и отключёнными внешними действиями.
- SSH с заранее проверенным ключом сервера, отдельным ключом доставки и forced command. Пароли Beget, БД и операторский токен в GitHub для доставки не нужны.
- Приватная конфигурация и код лежат вне `public_html`. Установщик релизов не заменяет конфигурацию и обработчик доставки. Forced command ограничивает SSH-команды, но **не является системной песочницей**: доставленный PHP работает от пользователя хостинга и имеет его доступ к файлам. Ключ доставки требует такого же доверия, как право исполнять код на этом аккаунте.
- Снимок заменяемых публичных файлов, обновление каждого файла атомарным переименованием, переключение backend через `current`. При неудачной проверке CLI предыдущие файлы и указатель восстанавливаются автоматически.
- Нет удаления чужих файлов, `rsync --delete`, миграций БД при каждом push или автоматического деплоя `main`.

Обновление всего сайта не является единой транзакцией: отдельные файлы меняются по очереди; главная страница — последней. Это тестовый стенд. Снимки и старые релизы автоматически не удаляются.

## Однократная настройка Beget

В примерах `/home/ACCOUNT/SITE` — **существующая папка сайта**, в которой уже есть `public_html`. Реальный абсолютный путь можно увидеть командой `pwd` в веб-терминале/SSH. Не используйте `public_html` как папку приватного backend.

1. В панели Beget включите SSH. В настройках домена выберите PHP 8.3. Версия CLI проверяется отдельно: `php8.3 -v`. Потребуются Python **3.8+** и PHP-расширение `pdo_mysql`; установщик проверяет их до изменений. Наличие подходящего Python в конкретном аккаунте ещё нужно подтвердить.
2. Через файловый менеджер загрузите **вне `public_html`** три файла из `scripts`: `install_beget_receiver.sh`, `receive_beget.sh`, `deploy_beget_remote.py`. Можно поместить их в папку `setup`. В терминале запустите:

   ```bash
   bash setup/install_beget_receiver.sh /home/ACCOUNT/SITE
   ```

   Установщик не включает SSH, не создаёт БД и не меняет `authorized_keys`. Если обработчик уже установлен, повторная установка прекращается для ручной проверки изменений.
3. Если в `public_html` остался исходный Beget `index.php`, перенесите **именно этот шаблон** в резервную папку вне `public_html`. Обработчик откажется от деплоя при наличии этого файла; чужой PHP-сайт он не заменяет.
4. На своём компьютере создайте отдельную пару ключей (не используйте основной ключ):

   ```bash
   ssh-keygen -t ed25519 -f beget-staging-deploy -C github-beget-staging
   ```

   Для автоматизации ключ оставляют без passphrase; закрытую часть хранят только в GitHub Environment secret. В Beget файл `~/.ssh/authorized_keys` дополните одной строкой, которую напечатает установщик, заменив публичную часть на содержимое `beget-staging-deploy.pub`:

   ```text
   restrict,command="/home/ACCOUNT/SITE/relationship-reset-private/deploy/receive_beget.sh" ssh-ed25519 PUBLIC_KEY github-beget-staging
   ```

   Сохраните существующие строки ключей. Права каталога `.ssh` — `700`, `authorized_keys` — `600`. Этот ключ допускает только доставку staging; интерактивный вход, SCP и forwarding для него отключены. Веб-приложение по-прежнему исполняется от пользователя хостинга, поэтому право изменять код репозитория следует давать только доверенным участникам.
5. Получите публичный SSH host key сервера и сверьте fingerprint с доверенным источником Beget/поддержкой. Результат одного `ssh-keyscan` сам по себе не подтверждает подлинность. Проверенную строку формата `HOST ssh-ed25519 …` сохраните как `BEGET_SSH_KNOWN_HOSTS`. Скрипт использует `StrictHostKeyChecking=yes`; отключать проверку не нужно.

После установки структура такова:

```text
SITE/public_html/                         существующий тестовый сайт + api/
SITE/relationship-reset-private/deploy/  установленный вручную обработчик
SITE/relationship-reset-private/shared/config.php   только на сервере
SITE/relationship-reset-private/releases/<SHA>/backend/
SITE/relationship-reset-private/current -> releases/<SHA>
SITE/relationship-reset-private/backups/ резервные копии заменённых файлов
```

## GitHub: четыре секрета и переключатель

Repository → Settings → Environments → создать `beget-staging`.
В **Deployment branches and tags** разрешить только `m1-first-payment-20260925`.
В этом Environment добавить:

| Secret | Значение |
|---|---|
| `BEGET_SSH_HOST` | SSH-сервер из панели Beget, без `https://` |
| `BEGET_SSH_USER` | Логин SSH |
| `BEGET_SSH_PRIVATE_KEY` | Полное содержимое отдельного закрытого ключа |
| `BEGET_SSH_KNOWN_HOSTS` | Проверенный публичный host key сервера |

Repository → Settings → Secrets and variables → Actions → **Variables**:

| Variable | Значение |
|---|---|
| `BEGET_DEPLOY_ENABLED` | `true` после установки обработчика и секретов; иначе отсутствует/`false` |
| `BEGET_SSH_PORT` | `22`, если Beget не указал другой порт |
| `BEGET_HEALTH_URL` | После исправного HTTPS: `https://ВАШ-ДОМЕН/api/health.php`; до этого оставить пустой |

`BEGET_DEPLOY_ENABLED` задаётся именно на уровне **репозитория**, чтобы условие job могло его прочитать до загрузки Environment. Секреты доступны только deploy job, который не выполняется для pull request и `main`.

Отправка нового коммита в указанную ветку запускает проверки, затем деплой. Если workflow уже запускался без настроек, после заполнения выполните **Re-run all jobs**. `workflow_dispatch` подготовлен, но GitHub показывает ручной **Run workflow** только когда файл workflow присутствует в default branch. Для первого запуска из текущей draft-ветки используйте push/re-run; переносить весь незавершённый сайт в `main` ради кнопки не требуется.

Если GitHub-приложение не имеет права записывать `.github/workflows`, файл можно добавить владельцу репозитория через GitHub UI. Это не подтверждение выполненного деплоя.

## База данных — отдельный шаг, после первой доставки

В Beget → MySQL создайте отдельную тестовую базу. Оставьте доступ `localhost`, не открывайте MySQL для всех IP. Имя пользователя базы совпадает с именем базы. Затем скопируйте `current/backend/config.example.php` в `shared/config.php`, заполните реквизиты и хеш случайного операторского токена по [BACKEND_RU.md](BACKEND_RU.md). Права файла — `600`.

Schema создаётся **отдельной командой владельца**, а не workflow:

```bash
php8.3 /home/ACCOUNT/SITE/relationship-reset-private/current/backend/bin/migrate.php
```

Проверку MySQL и пробного вымышленного случая выполняйте по backend-инструкции. Не используйте настоящие истории отношений в staging. Миграции CI относятся только к временной базе GitHub Runner; они не подключаются к Beget.

## Как понимать результат

- **Validation passed, deploy skipped**: код проверен; переключатель доставки ещё выключен. Хостинг не обновлён.
- **Deployed + CLI liveness**: файлы доставлены, PHP CLI смог прочитать релиз. Это не проверка web-PHP, HTTPS, БД или оплаты.
- **Public HTTPS health check passed**: браузерный endpoint ответил корректным JSON именно для этого релиза. Если URL ещё не задан, проверка честно отмечается как пропущенная.
- **Backend readiness / integration passed**: проверены конфигурация и тестовая БД; это отдельный результат после создания схемы.

При неудачной CLI-проверке обработчик откатывает свои публичные файлы и указатель backend. В случае ошибки внешней HTTPS-проверки workflow становится красным, но уже успешный CLI-деплой автоматически не отменяется: сначала проверьте DNS, сертификат и web-PHP. Для ручного восстановления используйте указанную в логе папку `backups/<id>`: `state.json` хранит прошлый указатель, права и список ранее отсутствовавших файлов. Восстанавливайте только восемь управляемых файлов; остальные каталоги и БД не трогайте.

## Проверки и первоисточники

Локально: `python3 tests/deploy_test.py`. Тесты проверяют повторяемость архива, исключение конфигурации, tampering/traversal/symlinks, сохранение чужих файлов и возврат предыдущего сайта при сбое health. В этих тестах PHP заменён специальной заглушкой; реальный PHP/MySQL проверяются отдельной CI job.

- [Beget: SSH-доступ](https://beget.com/ru/kb/faq/hosting/dostup-k-serveram)
- [Beget: отдельный SSH-ключ](https://beget.com/ru/kb/how-to/ssh/avtomaticheskaya-ssh-avtorizacziya-po-klyuchu)
- [Beget: MySQL](https://beget.com/ru/kb/manual/mysql)
- [Beget: PHP в терминале выбирается отдельно](https://beget.com/ru/kb/manual/crontab)
- [GitHub: секреты](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub: неизменяемый SHA actions](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub: ограничение ручного запуска workflow](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
