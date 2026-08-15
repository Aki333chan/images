# Деплой Aurum Panel на живую систему

Целевая машина — VDS `10.0.0.1` (Ubuntu 24.04), где уже работает Pterodactyl
Panel на `panel.aurumgg.ovh`. Новая панель встаёт рядом, на `manage.aurumgg.ovh`.

**Домашний сервер `10.0.0.2` не трогаем**: ни Wings, ни MariaDB, ни игровые
сервера, ни существующий WireGuard. Единственное изменение там — **добавление**
правил ufw под RCON и порт companion-плагина.

> Это живая система с игроками онлайн. Каждый шаг ниже либо ничего не меняет
> (аудит), либо добавляет новое рядом с существующим. Ни одна команда в этом
> документе не переписывает и не удаляет то, что уже настроено.

---

## Шаг 0. Аудит. Ничего не менять

Сначала смотрим, что есть. Скрипт `deploy/audit.sh` — только read-only команды
(`show`, `status`, `list`, `cat`), ни одного изменения.

```bash
# на VDS
sudo bash deploy/audit.sh > audit-vds.txt

# на домашнем сервере
sudo bash deploy/audit.sh > audit-home.txt
```

Секретов в выводе нет (пароли и ключи не печатаются), файлы можно просто
просмотреть глазами. Что нужно из них выяснить перед следующими шагами:

| Что смотрим | Зачем |
| --- | --- |
| `ss -tulpn` на VDS | свободны ли **5432** (Postgres), **6380** (наш Redis), **3001** (API) |
| занят ли **6379** | если да — там Redis Pterodactyl, и мы к нему не притрагиваемся |
| `ufw status verbose` на 10.0.0.2 | по какому образцу сделано правило для Wings |
| `nginx -t` и `sites-enabled` | как оформлен блок `panel.aurumgg.ovh` |
| `certbot certificates` | каким способом выпускались сертификаты |
| `wg show` | что туннель поднят и оба адреса на месте |

**Проверка DNS** — сделайте прямо сейчас, до всего остального, потому что
выпуск сертификата без неё не пройдёт:

```bash
dig +short manage.aurumgg.ovh
```

Ответ должен совпасть с публичным IP VDS (тем же, что у `panel.aurumgg.ovh`).
Если пусто — **добавьте A-запись `manage` → публичный IP VDS** у своего
DNS-провайдера и дождитесь распространения (обычно минуты, иногда до часа).

---

## Шаг 1. Домашний сервер: открыть RCON и порт плагина

Добавляем правила навстречу VDS. Скрипт additive: только `ufw allow`, никаких
`reset`, `delete` или смены default-политик.

```bash
# сначала посмотреть, что будет сделано
sudo bash deploy/firewall/home-server-additive.sh --dry-run

# отредактировать список портов под свои сервера, затем применить
sudo bash deploy/firewall/home-server-additive.sh
```

Правила выглядят так (по образцу того, что уже сделано для Wings):

```
ufw allow from 10.0.0.1 to any port 25575 proto tcp comment 'RCON ...'
ufw allow from 10.0.0.1 to any port 8085  proto tcp comment 'companion ...'
```

Наружу порты остаются закрытыми: разрешён только источник `10.0.0.1`.

**Проверить сразу после:**

```bash
sudo ufw status verbose          # прежние правила на месте?
# с VDS:
nc -vz 10.0.0.2 25575            # порт открыт
```

И главное — зайдите в игру и убедитесь, что сервера работают как работали.

В `server.properties` каждого сервера должно быть включено RCON:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<длинный случайный пароль>
```

---

## Шаг 2. VDS: Node.js 22, PostgreSQL 16, отдельный Redis

### Проверка конфликтов портов

```bash
sudo ss -tulpn | grep -E ':(3001|5432|6379|6380)\b'
```

Ожидаемо: **6379** занят Redis Pterodactyl (его не трогаем), остальные свободны.
Если занято что-то ещё — остановитесь и разберитесь, прежде чем ставить.

### Node.js 22 LTS

Pterodactyl — PHP, конфликта нет.

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
node --version   # ожидаем v22.x
```

### PostgreSQL 16

Pterodactyl использует MariaDB на домашнем сервере, так что 5432 свободен.

```bash
sudo apt-get install -y postgresql-16 postgresql-client-16
sudo systemctl enable --now postgresql
```

Создаём свою базу и пользователя (пароль сгенерируйте, см. «Секреты»):

```bash
DB_PASS="$(openssl rand -base64 24)"
echo "Пароль БД (сохраните в менеджер паролей): $DB_PASS"

sudo -u postgres psql <<SQL
CREATE USER aurum WITH PASSWORD '${DB_PASS}';
CREATE DATABASE aurum_panel OWNER aurum;
SQL
```

Postgres по умолчанию слушает только localhost — так и оставляем.

### Redis для панели: отдельный инстанс на 6380

Redis Pterodactyl на 6379 **не трогаем**. Причина отдельного инстанса: Pterodactyl
держит там сессии и кэш, её политика вытеснения может выбросить наши задачи
BullMQ, а любой `FLUSHALL` с одной стороны сломал бы другую.

```bash
sudo apt-get install -y redis-server

sudo install -m 0644 deploy/redis/aurum.conf /etc/redis/aurum.conf
sudo install -d -o redis -g redis -m 0750 /var/lib/redis-aurum
sudo install -m 0644 deploy/systemd/aurum-redis.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-redis

redis-cli -p 6380 ping    # PONG
redis-cli -p 6379 ping    # PONG — Pterodactyl как работал, так и работает
```

---

## Шаг 3. Выкладка кода

```bash
sudo useradd --system --create-home --home-dir /opt/aurum-panel --shell /usr/sbin/nologin aurum
sudo -u aurum git clone https://github.com/Aki333chan/images.git /opt/aurum-panel
cd /opt/aurum-panel
sudo -u aurum git checkout claude/pterodactyl-admin-panel-core-984zye

sudo -u aurum npm ci
sudo -u aurum npm run build          # shared + api + web
sudo -u aurum npm run prisma:generate

sudo install -d -o aurum -g aurum -m 0750 /var/log/aurum-panel
sudo install -d -o aurum -g aurum -m 0700 /var/backups/aurum-panel
```

### Секреты

```bash
sudo install -d -m 0750 -o root -g aurum /etc/aurum-panel
sudo install -m 0640 -o root -g aurum deploy/env/api.env.example /etc/aurum-panel/api.env
sudo install -m 0640 -o root -g aurum deploy/env/backup.env.example /etc/aurum-panel/backup.env
sudo nano /etc/aurum-panel/api.env      # заполнить по чек-листу ниже
```

### Миграции и первый пользователь

```bash
cd /opt/aurum-panel/apps/api
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | xargs) npx prisma migrate deploy
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | xargs) npm run prisma:seed
```

После сида **очистите `OWNER_PASSWORD`** в `api.env`.

---

## Шаг 4. systemd для API

```bash
sudo install -m 0644 deploy/systemd/aurum-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-api

systemctl status aurum-api --no-pager
journalctl -u aurum-api -n 50 --no-pager
```

Проверка, что сервис жив и видит зависимости:

```bash
curl -s http://10.0.0.1:3001/api/health          # {"status":"ok",...}
curl -s http://10.0.0.1:3001/api/health/ready    # database/redis: ok
```

**Проверка, что API не торчит наружу.** Он слушает только адрес туннеля
(`API_BIND=10.0.0.1`), на публичном интерфейсе не слушает вовсе:

```bash
sudo ss -tulpn | grep 3001         # LISTEN только на 10.0.0.1:3001
# с любой внешней машины — соединения быть не должно:
nc -vz <публичный-IP-VDS> 3001     # ожидаем refused/timeout
```

Дополнительно (defence in depth, если когда-нибудь смените bind на 0.0.0.0):

```bash
sudo ufw deny in on eth0 to any port 3001 comment 'Aurum API только через nginx'
```

Имя внешнего интерфейса возьмите из `ip -brief address` в аудите.

---

## Шаг 5. nginx и сертификат

Существующий блок `panel.aurumgg.ovh` не редактируем — добавляем новый файл.
nginx разводит их по `server_name`.

### 5.1. Временный HTTP-блок для выпуска сертификата

```bash
sudo install -d -m 0755 /var/www/certbot
sudo install -m 0644 deploy/nginx/manage.aurumgg.ovh.bootstrap.conf \
  /etc/nginx/sites-available/manage.aurumgg.ovh
sudo ln -s /etc/nginx/sites-available/manage.aurumgg.ovh /etc/nginx/sites-enabled/

sudo nginx -t          # ОБЯЗАТЕЛЬНО: без OK дальше не идти
sudo systemctl reload nginx
```

`reload` (не `restart`) — существующие соединения к `panel.aurumgg.ovh`
не рвутся.

### 5.2. Сертификат

Используем `certonly --webroot`, а не плагин `--nginx`: этот способ **не
редактирует конфиги nginx вообще**, поэтому блок Pterodactyl физически не может
пострадать.

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d manage.aurumgg.ovh
sudo certbot certificates      # видим и panel, и manage
```

Если в аудите видно, что для `panel.aurumgg.ovh` использовался плагин `--nginx`,
это не мешает: способы можно смешивать, автопродление работает для обоих.

### 5.3. Рабочий конфиг

```bash
sudo install -m 0644 deploy/nginx/aurum-limits.conf /etc/nginx/conf.d/
sudo install -m 0644 deploy/nginx/manage.aurumgg.ovh.conf \
  /etc/nginx/sites-available/manage.aurumgg.ovh

sudo nginx -t
sudo systemctl reload nginx
```

Правил ufw для 80/443 добавлять не нужно — они уже открыты под Pterodactyl.

### 5.4. Проверка

```bash
curl -sI https://manage.aurumgg.ovh/                 # 200, отдаётся index.html
curl -s  https://manage.aurumgg.ovh/api/health       # status ok
curl -sI https://panel.aurumgg.ovh/                  # Pterodactyl как работала
curl -sI https://manage.aurumgg.ovh/api/internal/x   # 404 — внутренний путь закрыт
```

Автопродление проверяется без реального выпуска:

```bash
sudo certbot renew --dry-run
```

---

## Шаг 6. Бэкап своей базы

Только база панели. MariaDB Pterodactyl — отдельная система, её этот бэкап
не касается.

```bash
sudo install -m 0755 deploy/systemd/aurum-backup.service /etc/systemd/system/aurum-backup.service
sudo install -m 0644 deploy/systemd/aurum-backup.timer /etc/systemd/system/
sudo chmod +x /opt/aurum-panel/deploy/scripts/*.sh
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-backup.timer

# прогнать прямо сейчас, не дожидаясь 04:20
sudo systemctl start aurum-backup.service
journalctl -u aurum-backup -n 20 --no-pager
ls -lh /var/backups/aurum-panel/
systemctl list-timers aurum-backup --no-pager
```

Скрипт пишет во временный файл и переименовывает его только после того, как
`pg_restore --list` подтвердит читаемость дампа: обрезанный дамп, выглядящий
валидным, в каталог не попадёт.

**Проверьте восстановление хотя бы раз** — непроверенный бэкап бесполезен.
Безопасно это делать на отдельной базе:

```bash
sudo -u postgres createdb aurum_restore_test
sudo -u postgres pg_restore --dbname=aurum_restore_test --no-owner \
  /var/backups/aurum-panel/aurum_panel_*.dump
sudo -u postgres psql -d aurum_restore_test -c 'select count(*) from users;'
sudo -u postgres dropdb aurum_restore_test
```

Дампы лежат на той же машине, что и база. Как минимум для БД панели стоит
настроить копирование куда-то ещё — иначе потеря диска VDS унесёт и то, и другое.

---

## Чек-лист секретов

Все — в `/etc/aurum-panel/api.env` (права `0640 root:aurum`). Ни один не
коммитится в git.

| Переменная | Как получить | Замечания |
| --- | --- | --- |
| `JWT_ACCESS_SECRET` | `openssl rand -base64 48` | смена разлогинит всех — это нормально |
| `JWT_REFRESH_SECRET` | `openssl rand -base64 48` | отдельный от access |
| `APP_ENCRYPTION_KEY` | `openssl rand -base64 32` | **ровно 32 байта**; им шифруются RCON-пароли и токены плагина |
| `DATABASE_URL` | пароль из `openssl rand -base64 24` | пользователь `aurum`, база `aurum_panel` |
| `PTERO_APP_API_KEY` | Pterodactyl → Admin → Application API | доступ на чтение серверов |
| `PTERO_CLIENT_API_KEY` | Account → API Credentials служебного пользователя | этого пользователя надо добавить на нужные сервера |
| RCON-пароли | `openssl rand -base64 24` на сервер | вводятся в UI панели, не в `.env` |
| Токены companion | `openssl rand -base64 32` на сервер | ASCII обязателен; тот же токен в `config.yml` плагина |
| `DEEPSEEK_API_KEY` | понадобится на следующем этапе | добавится сюда же |

Про `APP_ENCRYPTION_KEY` отдельно: **потеря** ключа = сохранённые RCON-пароли
и токены плагина расшифровать нельзя, их придётся ввести заново через UI.
**Смена** ключа на живой базе делает ранее сохранённые креды нечитаемыми.
Положите его в менеджер паролей сразу, до первого запуска.

Быстрая генерация всего разом:

```bash
printf 'JWT_ACCESS_SECRET=%s\n'  "$(openssl rand -base64 48)"
printf 'JWT_REFRESH_SECRET=%s\n' "$(openssl rand -base64 48)"
printf 'APP_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
printf 'DB_PASSWORD=%s\n'        "$(openssl rand -base64 24)"
```

Не вставляйте секреты в командную строку напрямую — они попадут в `~/.bash_history`.
Правьте `api.env` редактором.

---

## Обновление на новую версию

```bash
cd /opt/aurum-panel
sudo systemctl start aurum-backup.service     # свежий бэкап перед миграциями
sudo -u aurum git pull
sudo -u aurum npm ci
sudo -u aurum npm run build
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | xargs) npx prisma migrate deploy
sudo systemctl restart aurum-api
curl -s http://10.0.0.1:3001/api/health/ready
```

Статика подхватывается сразу: nginx отдаёт файлы из `apps/web/dist`.

## Откат

```bash
sudo systemctl stop aurum-api
cd /opt/aurum-panel && sudo -u aurum git checkout <предыдущий-коммит>
sudo -u aurum npm ci && sudo -u aurum npm run build
# если миграции испортили данные:
sudo -u aurum deploy/scripts/restore-db.sh /var/backups/aurum-panel/<дамп>
sudo systemctl start aurum-api
```

Отключить новую панель целиком, не задев Pterodactyl:

```bash
sudo systemctl stop aurum-api aurum-redis aurum-backup.timer
sudo rm /etc/nginx/sites-enabled/manage.aurumgg.ovh
sudo nginx -t && sudo systemctl reload nginx
```

`panel.aurumgg.ovh` при этом продолжает работать: её блок мы не трогали.

---

## Финальный чек-лист: перед тем как звать друзей

### Ничего не сломалось

- [ ] `https://panel.aurumgg.ovh` открывается и логин работает
- [ ] **Игровые сервера на домашнем сервере работают, игроки заходят** —
      зайти в игру самому и посмотреть, что народ онлайн
- [ ] `sudo ufw status verbose` на 10.0.0.2 — прежние правила на месте,
      добавились только новые
- [ ] `wg show` на обеих машинах — туннель поднят, handshake свежий
- [ ] `systemctl status wings` на домашнем сервере — active
- [ ] Redis Pterodactyl отвечает: `redis-cli -p 6379 ping`

### Новая панель работает

- [ ] `dig +short manage.aurumgg.ovh` = публичный IP VDS
- [ ] `https://manage.aurumgg.ovh` открывается, сертификат валиден
- [ ] Логин ГМ проходит, 2FA включается и работает
- [ ] `curl -s https://manage.aurumgg.ovh/api/health/ready` — database и redis `ok`
- [ ] Синхронизация с Pterodactyl подтянула список серверов
- [ ] Консоль сервера открывается и показывает вывод
- [ ] Вкладка «Игроки» показывает онлайн (RCON работает через туннель)
- [ ] Кик/бан доходят до сервера
- [ ] Если поставлен companion-плагин: инвентарь открывается, `/ticket` в игре
      создаёт тикет, ответ модератора приходит игроку в чат

### Безопасность

- [ ] `ss -tulpn | grep 3001` — LISTEN только на `10.0.0.1`, не на `0.0.0.0`
- [ ] `nc -vz <публичный-IP> 3001` снаружи — соединения нет
- [ ] `curl -sI https://manage.aurumgg.ovh/api/internal/x` → 404
- [ ] `ls -l /etc/aurum-panel/api.env` → `-rw-r----- root aurum`
- [ ] `OWNER_PASSWORD` из `api.env` удалён после сида
- [ ] Порты RCON и плагина на 10.0.0.2 недоступны из интернета:
      `nc -vz <публичный-IP-домашнего> 25575` → отказ
- [ ] Секреты записаны в менеджер паролей, особенно `APP_ENCRYPTION_KEY`

### Эксплуатация

- [ ] `systemctl list-timers aurum-backup` — таймер активен, есть next run
- [ ] В `/var/backups/aurum-panel/` лежит хотя бы один дамп
- [ ] Восстановление из дампа проверено на тестовой базе
- [ ] `sudo certbot renew --dry-run` проходит для обоих доменов
- [ ] `systemctl is-enabled aurum-api aurum-redis` → enabled (переживут ребут)
- [ ] Ребут VDS проверен: после `sudo reboot` обе панели поднялись сами

Последний пункт стоит сделать в спокойное время, а не когда все играют.
