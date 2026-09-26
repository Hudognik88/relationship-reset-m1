# Kotlin API: отдельная тестовая сборка

Kotlin + Ktor, Java 17, JDBC/MySQL. Это перенос операторского API для вымышленных
репетиций. Текущий PHP-сервис на Beget продолжает работать. Этот каталог сам по
себе не переключает сайт, DNS, базу или платёжный сценарий.

API сохраняет пути и схему данных PHP: `rr_cases`, `rr_drafts`,
`rr_schema_migrations`. SQL берётся непосредственно из
`../backend/migrations/001_initial.sql`; его контрольная сумма не меняется.
Авторизация, синтетические анкеты и черновики остаются операторскими. Нельзя
помещать служебный токен в сайт, PWA или клиентское приложение.

## Сборка и проверки

Из корня репозитория, с Java 17 и Maven 3.9.9:

```sh
mvn -B -ntp -f backend-kotlin/pom.xml verify
```

Исполняемый файл: `backend-kotlin/target/relationship-reset-api.jar`.
Без `RR_TEST_DB_*` интеграционные проверки базы пропускаются; одних модульных
тестов недостаточно для выпуска. Для полного прогона нужна **отдельная
одноразовая тестовая база**, не `movereed_rrstage` и не рабочая база клиентов:

```sh
export RR_TEST_DB_HOST=127.0.0.1
export RR_TEST_DB_PORT=3306
export RR_TEST_DB_NAME=rr_kotlin_test
export RR_TEST_DB_USER=rr_kotlin_test
export RR_TEST_PHP_BINARY=php8.3
# RR_TEST_DB_PASSWORD передайте через закрытое окружение.
mvn -B -ntp -f backend-kotlin/pom.xml verify
python3 backend-kotlin/scripts/smoke.py
```

PHP 8.3 с `pdo_mysql` нужен для проверки обратной совместимости: прежний
PHP Store должен прочитать записанный Kotlin кейс и распознать повтор запроса.
Если `RR_TEST_PHP_BINARY` отсутствует, эта отдельная проверка пропускается.

Workflow `.github/workflows/kotlin.yml` запускает полный прогон с одноразовым
MySQL 8.4, PHP 8.3 и Maven 3.9.9/Java 17, затем собирает Docker-образ и проверяет Compose
и Caddyfile. Workflow не публикует образ и не подключается к хостингу. Действующий
PHP workflow и его SSH-ключ не используются Kotlin-сборкой.

`scripts/smoke.py` с теми же `RR_TEST_DB_*` отдельно запускает миграцию и
собранный JAR, проверяет HTTP, авторизацию, создание/чтение кейса и повторы.
Так проверяется исполняемый артефакт с Netty и Hikari, а не только тестовое
приложение Ktor. Скрипт предназначен для одноразовой тестовой базы.

## Настройки сервиса

Секреты хранятся вне репозитория. Пути `_FILE` указывают на файлы внутри среды
запуска; пароли и исходный bearer-токен в код не записываются.

| Переменная | Значение для Compose |
| --- | --- |
| `RR_ENVIRONMENT` | Только `staging` |
| `RR_HOST`, `RR_PORT` | `0.0.0.0`, `8080`, без публикации порта на хосте |
| `RR_OPERATOR_TOKEN_SHA256_FILE` | Файл SHA-256 операторского токена |
| `RR_DB_HOST`, `RR_DB_PORT` | `mysql`, `3306` в частной сети |
| `RR_DB_NAME`, `RR_DB_USER` | `rr_kotlin_stage` |
| `RR_DB_PASSWORD_FILE` | Файл пароля пользователя БД |
| `RR_DB_SSL_MODE` | `REQUIRED` для частной сети Compose |
| `RR_TRUSTED_PROXY_IPS` | `172.30.0.2`, адрес Caddy в сети `proxy` |
| `RR_RELEASE` | Полный SHA проверенного коммита, 40 hex-символов |

В Compose соединение шифруется с автоматически созданным сертификатом MySQL;
`REQUIRED` не проверяет подлинность имени сервера. Если база находится вне
частной сети, требуется отдельно настроить TLS с
проверкой имени и сертификата (`VERIFY_IDENTITY`) и сетевые ограничения.
Не открывайте удалённый MySQL на Beget ради подключения нового сервиса.

Сервис доверяет признаку HTTPS только от указанного прокси. Порт API и порт
MySQL не публикуются. Caddy заменяет входящий `X-Forwarded-Proto`, принимает
HTTPS и обслуживает только `/api/index.php` и `/api/health.php`.
Если меняется подсеть Compose из-за конфликта адресов, одновременно измените
IP Caddy и `RR_TRUSTED_PROXY_IPS`; доверять всему Интернету нельзя.

## Подготовка отдельного JVM-хостинга

Потребуются Linux с Docker Engine/Compose, отдельный адрес тестового API и
свободные порты 80/443. Возможности текущего тарифа Beget для такого запуска
пока не подтверждены. Покупка VPS, настройка DNS и переключение домена —
отдельный этап после проверки сборки. Следующие команды предназначены для
выбранного тестового сервера, а не для существующего `public_html`.

Compose создаёт новую базу `rr_kotlin_stage` в собственном томе. Существующая
`movereed_rrstage` не подключается и не переносится автоматически.

1. Получите точный проверенный коммит репозитория. Сохраните предыдущий SHA,
   если это обновление. Перейдите в `backend-kotlin`.
2. На сервере создайте отдельные секреты. Команды ниже ничего не печатают:

```sh
(
set -eu
umask 077
mkdir -p .secrets
chmod 700 .secrets
# При повторном развёртывании используйте сохранённые файлы, не генерируйте заново.
test ! -e .secrets/operator-token
test ! -e .secrets/operator-token.sha256
test ! -e .secrets/db-password
test ! -e .secrets/db-root-password
rr_token=$(openssl rand -hex 32)
printf '%s' "$rr_token" > .secrets/operator-token
printf '%s' "$rr_token" | sha256sum | cut -d ' ' -f 1 > .secrets/operator-token.sha256
unset rr_token
rr_password=$(openssl rand -hex 32)
printf '%s' "$rr_password" > .secrets/db-password
rr_password=$(openssl rand -hex 32)
printf '%s' "$rr_password" > .secrets/db-root-password
unset rr_password
# Docker Compose монтирует file secrets без смены владельца.
# Родитель .secrets имеет 0700; эти три файла доступны UID 10001 внутри контейнера.
chmod 444 .secrets/operator-token.sha256 .secrets/db-password .secrets/db-root-password
chmod 600 .secrets/operator-token
)
```

Исходный токен контейнеру не передаётся. Храните его в закрытом менеджере
паролей либо в этом файле; не отправляйте в чат, CI-логи или URL.
Пароль уже инициализированной MySQL-базы не меняется от редактирования файла:
ротация требует отдельного изменения пользователя БД.
Файлы паролей создаются без завершающего перевода строки: приложение читает
их побайтно, а MySQL entrypoint удаляет завершающий перевод строки.

3. Задайте проверенный SHA и **выделенный тестовый** домен, уже направленный
   на этот сервер. Пример имени ниже нужно заменить фактическим настроенным:

```sh
export RR_RELEASE=$(git rev-parse HEAD)
export RR_DOMAIN=api-staging.poslessory.ru
docker compose config --quiet
docker compose build api
docker compose up -d --wait mysql
docker compose run --rm --no-deps api migrate
docker compose up -d --wait api caddy
```

Миграция выполняется явной командой; старт приложения не меняет схему.
Повторный запуск миграции проверяет прежнюю контрольную сумму. При её
несовпадении остановитесь и восстановите правильный файл миграции.
Не редактируйте ранее применённый SQL и не удаляйте запись из журнала миграций.

Dockerfile собирается с контекстом корня репозитория. Его отдельный
`Dockerfile.dockerignore` разрешает только исходники Kotlin, POM и SQL:
ни `.secrets`, ни сайт, ни локальная сборка не попадают в образ.
Процесс Java запускается под UID/GID 10001 с файловой системой только для
чтения, кроме временного каталога. Caddy хранит сертификаты в отдельном томе.
Базовые образы Maven/JRE, Caddy и MySQL закреплены по digest, проверенному
в Docker Hub. Перед выпуском обновляйте их отдельным проверяемым изменением,
включая полный CI-прогон. При изменении Caddyfile контейнер Caddy нужно
пересоздать (`docker compose up -d --force-recreate caddy`): файл смонтирован
напрямую, автоматическая горячая перезагрузка не настроена.

## Проверка перед использованием

Команды выполняются из `backend-kotlin` на тестовом сервере. Токен не
передаётся аргументом процесса curl и не выводится:

```sh
curl --fail --silent --show-error "https://$RR_DOMAIN/api/health.php"
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  "https://$RR_DOMAIN/api/index.php?route=/ready"
# Предыдущая команда должна вернуть 401.
{
  printf 'header = "Authorization: Bearer '
  cat .secrets/operator-token
  printf '"\n'
} | curl --config - --fail --silent --show-error \
      "https://$RR_DOMAIN/api/index.php?route=/ready"
```

Ожидается `200 / 401 / 200`: liveness, анонимный отказ, авторизованная
readiness с проверкой базы и миграции. Один зелёный healthcheck не доказывает
доступность БД. Затем выполните синтетический сценарий создания/чтения анкеты,
повтора того же запроса, конфликтного повтора и сохранения черновика.
Проверьте, что после перезапуска API и MySQL данные сохранены.

Контракт запросов и ограничения описаны в [PHP API](../backend/README_RU.md).
В этой версии нет приёма реальных клиентских анкет, выдачи результата,
Prodamus webhook или публичных клиентских токенов.

## Резервная копия и откат

Перед сменой версии сделайте закрытую резервную копию БД и сохраните текущий
SHA образа. Для резервной копии используйте `mysqldump` внутри контейнера с
`--single-transaction`, `--routines`, `--triggers` и `--events`; пароль берите
из смонтированного секрета через временный файл настроек клиента, не из
аргумента командной строки. Храните копию вне публичного каталога и проверяйте
восстановление в отдельной базе. Резервная копия без проверенного восстановления
не является достаточной проверкой выпуска.

Этот перенос использует ту же миграцию `001_initial`, поэтому откат приложения
не требует удаления таблиц или отката схемы. Для возврата к предыдущему
**сохранённому локальному образу**:

```sh
export RR_RELEASE=PREVIOUS_VERIFIED_40_CHARACTER_COMMIT_SHA
docker image inspect "relationship-reset-api:$RR_RELEASE" > /dev/null
docker compose up -d --no-build --no-deps --wait api
```

После отката повторите проверки `200 / 401 / 200` и чтение синтетического
кейса. Не используйте `docker compose down --volumes`: эта команда удаляет
базу и сертификаты. Старые образы сохраняйте до окончания проверки выпуска.

Возврат маршрута на существующий PHP-хостинг — отдельная операция настройки
прокси/DNS. Для неё заранее сохраняют старую конфигурацию и план переноса
данных. В текущем отдельном тестовом запуске переключения PHP вообще нет.

## Проверенные первичные источники

- [Docker: контекст сборки и Dockerfile-specific ignore](https://docs.docker.com/build/concepts/context/)
- [Docker Compose: file secrets](https://docs.docker.com/reference/compose-file/secrets/)
- [Caddy: reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
- [Официальный образ Maven](https://hub.docker.com/_/maven)
