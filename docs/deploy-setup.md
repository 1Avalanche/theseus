# Установка ограниченного деплоя на VPS

Инструкция для владельца с отдельным административным SSH-доступом и `sudo`. Пользователь `deploy` нужен только GitHub Actions. Секреты вводятся непосредственно на VPS или в настройках GitHub; в репозиторий и чат их не передавать. Эксплуатационный контракт — [operations.md](operations.md), решения — `DECISIONS.md` §15.

## 1. Проверить подготовленный сервер

Для текущего VPS уже подтверждены Ubuntu 22.04.5, около 2 ГБ RAM, активный swap 2 ГБ, Docker и Compose, `active`/`enabled` у Docker. Созданы `/opt/theseus/{infra,prod,test}` с владельцем `root` и правами `700`; у `deploy` только собственная группа. Повторная проверка:

```bash
uname -m
free -h
sudo systemctl is-active docker
sudo systemctl is-enabled docker
id deploy
sudo ls -ld /opt/theseus /opt/theseus/{infra,prod,test}
```

Текущий workflow Build release собирает только `linux/amd64` на `ubuntu-latest`. Для этой инструкции `uname -m` должен вернуть `x86_64`; архитектура VPS пока не проверена. Если вывод — `aarch64`, сначала адаптировать сборку образов и проверить их на нужной архитектуре, затем продолжать деплой. Gradle на VPS не запускается.

Не добавлять `deploy` в `sudo` или `docker`. Сохранить открытую административную SSH-сессию до проверки нового доступа.

## 2. Передать проверенные файлы администратору

Выбрать конкретный проверенный коммит репозитория с обработчиком. Просмотреть его обработчик, Compose, SSH- и sudoers-конфигурацию. Передать `deploy/` с локального компьютера в домашний каталог административного пользователя:

```bash
scp -r deploy ADMIN@VPS_HOST:~/theseus-install
```

`ADMIN` и `VPS_HOST` заменить своими значениями. Это разовая административная установка. Пользователь `deploy` и Deploy workflow для копирования привилегированных файлов не используются. На сервере staging-каталог будет `~/theseus-install/`, а файлы VPS — `~/theseus-install/vps/`.

Установка и последующее обновление конфигурации выполняются под общей блокировкой обработчика; администратор не редактирует рабочие Compose/env одновременно с деплоем. Версия установленного комплекта должна совпадать с `config_version` разрешённого релиза.

## 3. Разместить серверную конфигурацию

Создать дополнительные защищённые каталоги:

```bash
sudo install -d -o root -g root -m 700 /opt/theseus/docker
sudo install -d -o root -g root -m 700 /opt/theseus/releases/test /opt/theseus/releases/prod
sudo install -d -o root -g root -m 700 /opt/theseus/backups/test /opt/theseus/backups/prod
sudo install -d -o root -g root -m 755 /opt/theseus/infra/initdb
sudo install -d -o root -g root -m 755 /usr/local/lib/theseus
```

Обработчик и entrypoint устанавливаются root-owned, Compose и файлы секретов — с правами `600`. Все родительские каталоги должны принадлежать root и запрещать запись другим пользователям; симлинки вместо конфигурации не используются.

Для первой установки до включения SSH-ключей выполнить на VPS:

```bash
sudo install -o root -g root -m 644 ~/theseus-install/handler/deploy_handler.py /usr/local/lib/theseus/deploy_handler.py
sudo install -o root -g root -m 755 ~/theseus-install/ssh/ssh-entry /usr/local/lib/theseus/ssh-entry
sudo install -o root -g root -m 600 ~/theseus-install/vps/docker-compose.yml /opt/theseus/docker-compose.yml
sudo install -o root -g root -m 600 ~/theseus-install/vps/infra/docker-compose.yml /opt/theseus/infra/docker-compose.yml
sudo install -o root -g root -m 755 ~/theseus-install/vps/infra/initdb/10-theseus.sh /opt/theseus/infra/initdb/10-theseus.sh
sudo install -o root -g root -m 600 ~/theseus-install/examples/config.json /opt/theseus/config.json
sudo install -o root -g root -m 600 /dev/null /opt/theseus/deploy.lock
sudoedit /opt/theseus/config.json
```

В `config.json` заменить `OWNER/REPOSITORY` и `ghcr.io/owner`; оставить `config_version` равной `"1"` для этого комплекта:

```json
{"repository":"OWNER/REPOSITORY","registry":"ghcr.io/owner","config_version":"1"}
```

После включения доступа любые изменения комплекта выполняются одной административной операцией под общей блокировкой. Получить её можно командой ниже; внутри открывшейся root-оболочки внести все согласованные изменения, затем выполнить `exit`. Не пересоздавать и не удалять сам `deploy.lock`: блокировка привязана к inode.

```bash
sudo flock -x /opt/theseus/deploy.lock /bin/bash --noprofile --norc
```

## 4. Заполнить секреты на VPS

Скопировать шаблоны `deploy/env/infra.env.example` и `deploy/env/stand.env.example` из staging в соответствующие файлы с владельцем `root` и правами `600`. Значения вносить через `sudoedit`, не через команды с секретом в аргументах:

```bash
sudo install -o root -g root -m 600 ~/theseus-install/env/infra.env.example /opt/theseus/infra/infra.env
sudo install -o root -g root -m 600 ~/theseus-install/env/stand.env.example /opt/theseus/prod/stand.env
sudo install -o root -g root -m 600 ~/theseus-install/env/stand.env.example /opt/theseus/test/stand.env
sudoedit /opt/theseus/infra/infra.env
sudoedit /opt/theseus/prod/stand.env
sudoedit /opt/theseus/test/stand.env
```

Для каждого пароля БД отдельно выполнить `openssl rand -hex 24`. Для каждого сервисного токена отдельно — `openssl rand -hex 32`. Сохранить значения в менеджере паролей, затем заполнить файлы:

| Файл | Что записать |
|---|---|
| `infra/infra.env` | Пароль `postgres`, четыре разных пароля ролей БД prod/test core/agent |
| `prod/stand.env` | `THESEUS_STAND=prod`, prod-токен бота, сервисный токен prod, ключ OpenRouter, данные владельца |
| `test/stand.env` | `THESEUS_STAND=test`, отдельные test-токен бота и сервисный токен, тот же ключ OpenRouter и данные владельца |

`THESEUS_CORE_DB_PASSWORD` стенда должен совпадать с его `THESEUS_PROD_CORE_DB_PASSWORD` либо `THESEUS_TEST_CORE_DB_PASSWORD` из `infra.env`. У test установить heap `128m`, лимиты контейнеров `300m`; у prod — `256m`/`400m`. Таймзона владельца — `Asia/Yekaterinburg`, если профиль не требует другой. В каждом файле заменить все обязательные `TODO`; комментарии писать отдельными строками.

Токены ботов взять у BotFather. Для OpenRouter ограничить расходы и запретить data collection. `THESEUS_CORE_IMAGE`/`THESEUS_AGENT_IMAGE` и digest в `stand.env` не задавать: их выбирает обработчик из разрешённого релиза.

## 5. Настроить чтение GitHub и GHCR сервером

Серверу нужны две операции: читать приватные образы GHCR и проверять принадлежность коммита `main`. Использовать отдельные минимально необходимые credentials; они не дают GitHub Actions административный доступ к VPS.

- GitHub token для проверки репозитория: доступ только к нужному репозиторию и чтению его содержимого. Записать в `/opt/theseus/github.env` строку `GITHUB_TOKEN=…` через `sudoedit`; файл root-owned `600`.
- Credential GHCR: PAT classic с `read:packages`, без прав записи/удаления пакетов; учётная запись должна иметь доступ к нужным приватным пакетам. Войти администратором в Docker с конфигурацией `/opt/theseus/docker` через `--password-stdin`; токен не передавать как аргумент командной строки. Docker хранит credential в `config.json`, поэтому каталог root-only, файл — `600`. GitHub Packages требует PAT classic для такой внешней авторизации: [документация GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-to-the-container-registry).

Создать `config.json` из поставляемого шаблона, указать точный GitHub-репозиторий и registry `ghcr.io/<owner>` строчными. Любая ошибка серверной проверки GitHub блокирует prod; обхода флагом клиента нет.

Создание файла GitHub token и вход в GHCR на VPS:

```bash
sudo install -o root -g root -m 600 /dev/null /opt/theseus/github.env
sudoedit /opt/theseus/github.env
read -r -s -p 'GHCR token: ' THESEUS_GHCR_TOKEN
printf '\n'
printf '%s' "$THESEUS_GHCR_TOKEN" | sudo docker --config /opt/theseus/docker login ghcr.io -u OWNER --password-stdin
unset THESEUS_GHCR_TOKEN
sudo chmod 600 /opt/theseus/docker/config.json
```

`OWNER` заменить GitHub-логином учётной записи с доступом к пакетам. `github.env` содержит ровно одну строку `GITHUB_TOKEN=…`, без кавычек и комментариев. Введённый через `read` GHCR token не отображается и не попадает в текст shell history. Эти команды предназначены для первой установки; ротацию после включения деплоя выполнять под общей блокировкой.

## 6. Поднять общий PostgreSQL

После установки root-owned infra Compose и initdb-скрипта выполнить администратором:

```bash
sudo docker --config /opt/theseus/docker compose \
  -p theseus-infra \
  -f /opt/theseus/infra/docker-compose.yml \
  --env-file /opt/theseus/infra/infra.env up -d
sudo docker inspect --format '{{.State.Health.Status}}' theseus-postgres
```

Дождаться `healthy`. Проверить базы и роли:

```bash
sudo docker exec theseus-postgres psql -U postgres -c '\l'
sudo docker exec theseus-postgres psql -U postgres -c '\du'
```

Ожидаются базы `theseus_prod`, `theseus_test` и роли `theseus_{prod,test}_{core,agent}`. Порт PostgreSQL наружу не публикуется. Initdb применяется при первом создании тома; простое редактирование пароля в env не меняет пароль в существующей БД.

## 7. Ограничить SSH и sudo

Использовать поставляемые проверенные конфигурации SSH и sudoers. Sudo разрешает только два точных вызова обработчика: фиксированный Python с `-I`, фиксированный root-owned файл и аргумент `test` либо `prod`. В правиле нет wildcard, произвольного Python, shell или Docker.

Сгенерировать на своём компьютере два новых ключа, отдельно для стендов:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/theseus-test -C theseus-test
ssh-keygen -t ed25519 -f ~/.ssh/theseus-prod -C theseus-prod
```

Для неинтерактивного GitHub Actions используется выделенный ключ без passphrase. Приватные файлы оставить на своём компьютере и поместить непосредственно в GitHub Secrets своего стенда. На VPS установить только `.pub`, каждую публичную строку с собственным forced command:

```text
restrict,command="/usr/local/lib/theseus/ssh-entry test" ssh-ed25519 ПУБЛИЧНЫЙ_КЛЮЧ_TEST theseus-test
restrict,command="/usr/local/lib/theseus/ssh-entry prod" ssh-ed25519 ПУБЛИЧНЫЙ_КЛЮЧ_PROD theseus-prod
```

Хранить строки в `/etc/ssh/theseus/authorized_keys`, владелец `root`. SSH для `deploy` должен использовать только этот файл, отключать пароль, интерактивную аутентификацию, PTY, forwarding и пользовательское окружение. Домашний каталог и startup-файлы свежего `deploy` также защитить от записи этим пользователем. Entry отвергает `SSH_ORIGINAL_COMMAND`: `ssh deploy@host 'команда'`, `scp`, SFTP не становятся альтернативными входами.

На VPS установить ограничения и открыть редактор публичных ключей:

```bash
sudo chown -R root:root /home/deploy
sudo chmod 755 /home/deploy
sudo find /home/deploy -type f -exec chmod go-w {} +
sudo find /home/deploy -type d -exec chmod go-w {} +
sudo find /home/deploy -printf '%u %g %m %p\n'
sudo install -d -o root -g root -m 755 /etc/ssh/theseus
sudo install -o root -g root -m 644 /dev/null /etc/ssh/theseus/authorized_keys
sudoedit /etc/ssh/theseus/authorized_keys
sudo install -o root -g root -m 440 ~/theseus-install/ssh/theseus-deploy.sudoers /etc/sudoers.d/theseus-deploy
sudo install -o root -g root -m 644 ~/theseus-install/ssh/90-theseus-deploy.conf /etc/ssh/sshd_config.d/90-theseus-deploy.conf
sudo visudo -cf /etc/sudoers.d/theseus-deploy
sudo /usr/sbin/sshd -t
```

`chown -R` выше относится только к только что созданному выделенному `deploy`, без существующих пользовательских данных. Проверить эффективную SSH-конфигурацию, поскольку другой `Match`/Include на сервере может менять настройки:

```bash
sudo /usr/sbin/sshd -T -C user=deploy,host=localhost,addr=127.0.0.1
```

Убедиться в `authorizedkeysfile /etc/ssh/theseus/authorized_keys`, `authenticationmethods publickey`, `passwordauthentication no`, `kbdinteractiveauthentication no`, `permittty no`, `disableforwarding yes`, `permittunnel no`, `permituserrc no`, `permituserenvironment no`. Последняя настройка должна быть выключена глобально, а не добавляться в `Match`, где её поддержка зависит от версии OpenSSH. Убедиться, что глобальный `AcceptEnv` не разрешает `BASH_ENV`, `ENV`, `PYTHONPATH`, `LD_*` и другие переменные запуска команд. Справочники: [sshd_config](https://man.openbsd.org/sshd_config), [authorized_keys](https://man.openbsd.org/sshd.8#AUTHORIZED_KEYS_FILE_FORMAT).

Домашний каталог `deploy`, `.bashrc`, `.profile` и другие startup-файлы должны принадлежать root без права записи группы/остальных. Убедиться, что их содержимое — штатные файлы свежей учётной записи: передача владения уже подменённого скрипта не делает его безопасным. Системные `/etc/profile`, `/etc/bash.bashrc` и `/etc/environment` остаются доверенной конфигурацией администратора. Конфигурация окружения на сервере существует; обработчик отдельно ограничивает окружение запуска Docker, а Python вызывается с `-I`.

Перед применением проверить `sudo visudo -cf /etc/sudoers.d/theseus-deploy` и `sudo /usr/sbin/sshd -t`. Только после успешной проверки перезагрузить конфигурацию `sudo systemctl reload ssh`. Текущую административную сессию оставить открытой и в другой сессии проверить административный вход и оба ограниченных ключа.

## 8. Разрешить первый релиз

Сначала запустить workflow **Build release** для проверенного ref: он выполняет сборку и тесты, отправляет оба образа в GHCR и выдаёт artifact с кандидатом `release.json`. Проверить происхождение сборки, SHA коммита и фактический digest каждого образа. Кандидат ещё не разрешение деплоя. Тег с SHA и image label не доказывают происхождение: при утверждении владелец отвечает за соответствие digest проверенной сборке.

Создать на VPS под общей блокировкой `/opt/theseus/releases/test/<полный-sha>.json` с владельцем root и правами `600`:

```json
{
  "commit": "0123456789abcdef0123456789abcdef01234567",
  "config_version": "1",
  "core_digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "agent_digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
}
```

Значения выше демонстрационные; заменить всеми четырьмя проверенными значениями. Имя файла должно совпадать с `commit`. Разрешение test не разрешает prod: для продвижения тех же образов в prod владелец устанавливает такую же проверенную запись в `releases/prod/`. Дополнительно сервер сверит коммит с текущей `main`.

Передать проверенный кандидат по административному SSH в `~/theseus-release.json`. Подставить проверенный полный SHA вместо `ПОЛНЫЙ_SHA_40_HEX`; затем на VPS установить разрешение релиза под общей блокировкой:

```bash
sudo flock -x /opt/theseus/deploy.lock install -o root -g root -m 600 ~/theseus-release.json /opt/theseus/releases/test/ПОЛНЫЙ_SHA_40_HEX.json
```

Файл копируется целиком до снятия блокировки, поэтому конкурентный обработчик не прочитает частично установленный релиз. Путь источника указывает на проверенный администратором кандидат, не на содержимое произвольной ветки.

### Будущая автоматизация разрешения релиза

**Статус: запланировано, реализация не начата.** Вместо ручной установки записи из шага 8 предполагается отдельный workflow `Authorize release`: approval конкретного кандидата в защищённом GitHub Environment, затем вызов отдельного SSH forced-command authorizer. Сервер проверит `release.json`, получит общую блокировку и для prod сверит коммит с `main`, прежде чем добавить разрешённый SHA. Обычный Deploy останется отдельной операцией и не сможет самостоятельно разрешать релизы.

Сейчас дополнительных workflow, authorizer и его ключей нет. До реализации использовать текущий ручной шаг 8; этот план не требует менять настройки VPS.

## 9. Проверить ограничения и выполнить деплой

С локального компьютера отправить одну строку JSON через stdin SSH, без удалённой команды и без PTY:

```bash
printf '%s\n' '{"action":"deploy","release":"ПОЛНЫЙ_SHA_40_HEX"}' | \
  ssh -T -i ~/.ssh/theseus-test deploy@VPS_HOST
```

Для неизвестного релиза ожидается отказ до изменений контейнеров. Для разрешённого test-релиза — дамп, загрузка образов, обновление core/agent и успешный healthcheck. Реальные токены не выводить в команду или чат.

Проверить отказы: удалённая shell-команда, SFTP/SCP, неизвестный релиз, лишние поля JSON и попытка указать стенд в запросе. Test-ключ не должен управлять prod. Проверить отсутствие доступа пользователя к `/opt/theseus/prod/stand.env` и Docker socket. Точный стенд закреплён за ключом; `VPS_USER` в обоих Environments одинаков — `deploy`.

После проверки выключить test, чтобы оставить запас памяти:

```bash
printf '%s\n' '{"action":"stop"}' | ssh -T -i ~/.ssh/theseus-test deploy@VPS_HOST
```

Проверить prod отдельно: неизвестный/неутверждённый релиз, коммит вне `main` и недоступность GitHub должны приводить к отказу. Запрос `stop` через prod-ключ запрещён.

## 10. Заполнить GitHub Environments

Создать `test` и `prod`, ограничить prod веткой `main`. В каждом добавить Secrets:

| Secret | Значение |
|---|---|
| `VPS_HOST` | Адрес VPS |
| `VPS_USER` | `deploy` |
| `VPS_SSH_KEY` | Приватный ключ соответствующего стенда |
| `VPS_KNOWN_HOSTS` | Публичный ключ SSH-сервера в формате known_hosts |

Отпечаток host key получить через административную консоль VPS, например `sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`, и сверить с записью known_hosts. Одного непроверенного `ssh-keyscan` недостаточно. Токены Telegram, OpenRouter и пароли БД остаются только на VPS.

После первого ручного деплоя test из шага 9 проверить секреты и SSH-вход через Deploy с `stand=test`, `action=stop` и пустым `release`: тестовый стенд при этом не запускается. Остановка требует сохранённого `test/image.env`, поэтому на чистой установке до первого деплоя такая проверка завершится отказом. Деплой приложения через Actions требует `action=deploy` и разрешённого SHA; после проверки test выключить его и продвинуть тот же релиз в prod. Фактическое потребление памяти, автоподъём после перезагрузки и работа уведомления проверяются отдельно на сервере; локальные тесты обработчика этих проверок не заменяют.

## Проверки перед установкой

С корня репозитория выполнить изолированную проверку обработчика и настоящей границы SSH/sudo в Ubuntu 22.04:

```bash
docker build -f deploy/handler/tests/Dockerfile -t local/theseus-deploy-check .
docker run --rm --network none local/theseus-deploy-check
```

Проверка запускает 17 юнит-тестов и 6 интеграционных тестов с настоящими OpenSSH, sudo и файловыми правами. Команды Docker внутри проверки заменены фейком; Docker socket хоста в контейнер не передаётся. Эти тесты доказывают ограничения входа, прав и протокола, но не успешный запуск приложения через настоящий Docker.

Быстрый локальный запуск Python:

```bash
python3 -m unittest discover -s deploy/handler/tests -v
```

Без специального Linux-окружения шесть SSH/sudo-тестов пропускаются. При реализации все 23 теста прошли в Ubuntu 22.04 контейнере; `./gradlew build installDist` завершился успешно. Публикация в реальный приватный GHCR, настройки GitHub Environments и работоспособность VPS проверяются отдельно при установке.

Build release также запускает контейнерные проверки настоящего SSH/sudo перед публикацией образов.

Локальный прогон с настоящими Docker/Compose выполнен отдельно: оба локальных образа закреплены по digest, использованы чистая отдельная БД и том `theseus-deploy-check-pgdata`. Повторный запуск infra initdb прошёл идемпотентно. Core стал `healthy`; REST без авторизации вернул 401, с авторизацией — активного администратора. MCP `create_reminder` вернул `created`; при наступлении срока планировщик попытался доставить уведомление и зарегистрировал Telegram 401. Agent с фиктивным токеном завершался с `UnauthorizedException` и перезапускался — ожидаемый результат этой проверки. Успешная доставка в настоящий Telegram и prod-деплой через приватный GHCR этим прогоном не проверены.

Локально core занимал около 232 МиБ из лимита 300 МиБ, PostgreSQL — около 74 МиБ из 320 МиБ. Это измерения на ARM Mac; фактический бюджет VPS x86 проверяется после установки. Проверены непустые логи обоих контейнеров: agent содержит ожидаемый `UnauthorizedException`, текст тестового напоминания, фиктивный сервисный токен и токен бота в логах core и agent отсутствуют. После проверки стенды останавливаются через Compose `down` без удаления томов.

## Проверка на VPS — 2026-11-07

На test через ограниченный SSH-обработчик развёрнут релиз `04b995c663bcebf7d6b749dfd6834e6a42414bcd`; контейнеры healthy. Реальный запрос «Напомни через 2 минуты проверить новый стенд» получил кодовое подтверждение «Поставил: «проверить новый стенд» — сегодня в 14:41», затем пользователь получил уведомление. Путь Telegram → LLM → MCP → PostgreSQL → планировщик → Telegram подтверждён для test.

Первый релиз `705b86a` был healthy, но реальный запрос не создавал запись в БД. В `04b995c` исправлены обязательное `type: "function"` в сериализации OpenRouter и выдача текстового обещания модели без результата core. Успешный smoke относится к исправленному релизу.

Test остановлен ограниченным test-ключом с сохранением БД. Администратор разрешил для prod тот же релиз `04b995c663bcebf7d6b749dfd6834e6a42414bcd` с прежними digest; серверная проверка принадлежности коммита `main` прошла успешно. Ограниченный prod SSH-деплой сохранил преддеплойный дамп и завершился healthy. Запрос в prod-боте получил `Поставил: «проверить рабочий стенд» — сегодня в 15:01.`, затем пользователь получил настоящее уведомление. Сквозной путь подтверждён и для prod; test остаётся остановленным.

На VPS подтверждены загрузка из приватного GHCR, продвижение тех же образов между стендами, проверка `main` через GitHub API и запрет операции `stop` для prod. Live-отказ prod для коммита вне `main` ещё не проверен. GitHub Environments `test` и `prod` созданы, пользователь подтвердил сохранение всех четырёх Secrets в обоих: общий `VPS_HOST`, `VPS_USER=deploy`, одинаковый полученный с сервера `VPS_KNOWN_HOSTS` с Ed25519-ключом и отдельный приватный `VPS_SSH_KEY` для каждого стенда. Реальный GitHub Actions Deploy с `stand=test`, `action=stop` и пустым `release` вернул «test: остановлен; БД сохранена». Затем Actions Deploy для prod с `action=deploy` и релизом `04b995c663bcebf7d6b749dfd6834e6a42414bcd` сохранил дамп и завершился healthy. Тем самым проверены Environments, SSH-секреты и ограниченный обработчик из настоящего Actions-запуска. Политика prod Branch `main` была дана владельцу как инструкция настройки; независимый аудит сохранённой branch policy не выполнялся. Снимки памяти после test- и prod-сценариев записаны в `operations.md`. Минимальный мониторинг не реализован; этап 2 остаётся в работе.

После перезагрузки VPS подтверждён автозапуск Docker, PostgreSQL и обоих prod-контейнеров: через 45 секунд после загрузки все три контейнера healthy; test не запущен. Swap 2,0 ГиБ активен, используется 0; RAM 1,9 ГиБ, занято 683 МиБ, доступно 1,2 ГиБ. Сквозной пользовательский запрос после перезагрузки ещё не повторялся; отдельные проверки работы планировщика и long polling после загрузки не заявляются.

Первый VPS-деплой и установка ограниченного обработчика подтверждены. Live-отказ prod для коммита вне `main` не исполнялся; отказ при неподходящем ответе GitHub API и недоступности проверки покрыт тестами. Это граница выполненной живой проверки, а не дополнительный этап установки. Реальные Telegram-сценарии test/prod выполнены до перезагрузки; после неё подтверждены автоподъём и healthy, включая успешный prod Actions Deploy, без отдельного повторного пользовательского напоминания. Ночной бэкап, проверка восстановления и минимальный мониторинг остаются впереди; этап 2 в целом ещё в работе. Runtime-секреты остаются в env-файлах VPS, в CI хранятся только SSH-секреты доступа.
