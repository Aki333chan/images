# Деплой Aurum Panel — пошаговая инструкция

Документ рассчитан на то, что вы делаете это впервые. Каждый шаг устроен
одинаково: **что делаем → почему → команды → что должно получиться → если
не получилось**.

Читайте подряд и не пропускайте проверки: именно они ловят ошибку сразу,
а не через два часа, когда уже непонятно, где свернули не туда.

**Куда ставим:** VDS `10.0.0.1` (Ubuntu 24.04), где уже работает Pterodactyl
Panel на `panel.aurumgg.ovh`. Новая панель встаёт рядом, на `manage.aurumgg.ovh`.

**Что не трогаем:** домашний сервер `10.0.0.2` — Wings, MariaDB, игровые
сервера, существующий WireGuard. Единственное изменение там — **добавление**
правил фаервола под RCON и порт плагина.

> ⚠️ Это живая система: прямо сейчас на серверах играют люди.
> Ни одна команда в этом документе не переписывает и не удаляет то, что уже
> настроено. Всё только добавляется рядом.

---

## Сколько это займёт

| Этап | Время | Можно прерваться после? |
| --- | --- | --- |
| Шаг 0: аудит и DNS | 15 мин | да |
| Шаг 1: фаервол на домашнем сервере | 15 мин | да |
| Шаг 2: софт на VDS | 20 мин | да |
| Шаг 3: код и секреты | 30 мин | да |
| Шаг 4: запуск сервиса | 10 мин | да |
| Шаг 5: nginx и сертификат | 20 мин | **нет**, доделайте до конца |
| Шаг 6: бэкапы | 10 мин | да |
| Шаг 7: первый вход и настройка | 20 мин | да |

Всего около двух часов не спеша. Шаг 5 лучше делать целиком: между
подшагами 5.1 и 5.3 домен отвечает заглушкой.

**Когда делать:** в спокойное время, когда на серверах мало народу. Игровые
сервера от наших действий не останавливаются, но если что-то пойдёт не так,
разбираться спокойнее, когда никто не ждёт.

---

## Что понадобится

- **Доступ по SSH к обеим машинам** с правами `sudo`.
- **Доступ к панели управления доменом** `aurumgg.ovh` — чтобы добавить
  DNS-запись.
- **Доступ в админку Pterodactyl** — оттуда возьмём два API-ключа.
- **Менеджер паролей** (KeePassXC, Bitwarden, 1Password — любой). Секретов
  будет около десяти, и один из них потерять нельзя.
- **Полтора-два часа спокойного времени.**

---

## Мини-ликбез: если вы редко работаете с сервером

Пропустите этот раздел, если всё перечисленное вам знакомо.

### Как подключиться к серверу

```bash
ssh ваш_пользователь@айпи_вашего_vds
```

Вводите пароль (он не отображается при наборе — это нормально, символы
просто не печатаются) или используется ключ, если он настроен.

Отключиться: `exit` или Ctrl+D.

### Что такое sudo

`sudo` = «выполни от имени администратора». Первый раз за сессию спросит ваш
пароль. Команды, меняющие систему, без него не работают.

### Как редактировать файлы

В инструкции используется `nano` — самый простой редактор:

```bash
sudo nano /путь/к/файлу
```

Внутри:
- пишете как в обычном блокноте, стрелками двигаете курсор;
- **Ctrl+O**, затем **Enter** — сохранить;
- **Ctrl+X** — выйти;
- **Ctrl+K** — вырезать строку целиком;
- мышь обычно не работает — двигайтесь стрелками.

### Как копировать команды

Копируйте блок целиком и вставляйте в терминал. Вставка в большинстве
терминалов — **Ctrl+Shift+V** (не Ctrl+V) или щелчок правой кнопкой.

Если в команде есть `<что-то в угловых скобках>` — это заглушка, её надо
заменить на своё значение **вместе со скобками**.

### Как читать вывод

- Команда отработала молча → как правило, всё хорошо. Unix-утилиты
  «молчат, когда всё в порядке».
- `Permission denied` → забыли `sudo`.
- `command not found` → пакет не установлен.
- `No such file or directory` → опечатка в пути.

### Многострочные блоки `<<SQL ... SQL`

Такая конструкция передаёт команде сразу несколько строк. Вставляйте блок
целиком, включая последнюю строку с закрывающим словом — до неё команда
не выполнится.

---

## Шаг 0. Аудит: смотрим, ничего не меняем

**Что делаем:** собираем сведения о текущем состоянии обеих машин.

**Почему:** дальше нам нужно знать, какие порты уже заняты, как оформлены
существующие правила фаервола и конфиг nginx. Действовать вслепую на живой
системе нельзя.

Скрипт `deploy/audit.sh` содержит только read-only команды (`show`, `status`,
`list`, `cat`) — он физически не может ничего изменить.

### 0.1. Получить скрипт на сервер

Если репозиторий ещё не склонирован, проще всего временно:

```bash
cd /tmp
git clone https://github.com/Aki333chan/images.git aurum-tmp
cd aurum-tmp
git checkout claude/pterodactyl-admin-panel-core-984zye
```

Если `git` не установлен: `sudo apt-get update && sudo apt-get install -y git`

### 0.2. Запустить на VDS

```bash
sudo bash deploy/audit.sh > ~/audit-vds.txt
```

### 0.3. Запустить на домашнем сервере

Подключитесь ко второй машине и повторите то же самое:

```bash
sudo bash deploy/audit.sh > ~/audit-home.txt
```

### Что должно получиться

Два текстовых файла. Посмотреть: `less ~/audit-vds.txt` (выход — `q`,
листать — стрелки и PageUp/PageDown).

Секретов в них нет: пароли и ключи не выводятся, из `.env` Pterodactyl
берутся только несекретные строки. Файлы можно спокойно переслать.

### Что искать в выводе

Откройте `audit-vds.txt` и найдите раздел **«Открытые порты»**:

| Порт | Что ожидаем | Что делать, если занят |
| --- | --- | --- |
| **80, 443** | заняты nginx | так и должно быть |
| **6379** | скорее всего занят Redis Pterodactyl | **не трогаем**, поставим свой на 6380 |
| **5432** | должен быть свободен | если занят — там уже есть PostgreSQL, см. ниже |
| **3001** | должен быть свободен | если занят — поменяйте порт в шаге 3 |
| **6380** | должен быть свободен | если занят — выберите другой, например 6381 |

Если **5432 занят** — PostgreSQL уже стоит. Это не помеха: мы создадим
отдельную базу и пользователя внутри него, шаг установки просто пропустите.

В разделе **«Реверс-прокси»** найдите блок `panel.aurumgg.ovh` — посмотрите,
как он оформлен и где лежат сертификаты. Наш блок будет рядом, в отдельном файле.

В `audit-home.txt`, раздел **«Фаервол»**, найдите строку про порт Wings
(обычно 8080 или 2022). Она выглядит примерно так:

```
8080/tcp    ALLOW IN    10.0.0.1
```

Наши новые правила будут точно такими же по форме — это и есть «по образцу».

### Если не получилось

- `bash: deploy/audit.sh: No such file or directory` → вы не в каталоге
  репозитория. Сделайте `cd /tmp/aurum-tmp` и повторите.
- Скрипт выводит много `(команда завершилась с кодом ...)` → нормально:
  часть утилит на машине не установлена, аудит на этом не останавливается.

---

## Шаг 0.5. DNS — сделайте прямо сейчас

**Что делаем:** проверяем, что домен `manage.aurumgg.ovh` указывает на VDS.

**Почему:** без этого не выпустится сертификат на шаге 5, и вы упрётесь в
это, уже проделав всю остальную работу. DNS-записи распространяются не
мгновенно, поэтому запускаем заранее.

### 0.5.1. Узнать публичный IP вашего VDS

Выполните **на VDS**:

```bash
curl -s https://api.ipify.org; echo
```

Запишите этот адрес — он понадобится.

### 0.5.2. Проверить, есть ли уже запись

```bash
dig +short manage.aurumgg.ovh
```

Если `dig` не установлен: `sudo apt-get install -y dnsutils`

- **Вывелся тот же IP, что на предыдущем шаге** — всё готово, идите дальше.
- **Пусто** — запись надо создать, см. ниже.

### 0.5.3. Создать A-запись

Зайдите в панель управления доменом `aurumgg.ovh` (там же, где заводили
`panel`) и добавьте запись:

| Поле | Значение |
| --- | --- |
| Тип | `A` |
| Имя / Host | `manage` |
| Значение / Points to | публичный IP VDS из шага 0.5.1 |
| TTL | оставьте по умолчанию |

Подсказка: посмотрите, как заведена существующая запись `panel` — новая
делается точно так же, только имя другое.

Через 5–15 минут проверьте снова:

```bash
dig +short manage.aurumgg.ovh
```

### Если не получилось

- **Пусто и через час** → проверьте, что запись сохранилась и что имя именно
  `manage`, а не `manage.aurumgg.ovh` (многие панели дописывают домен сами,
  и получается `manage.aurumgg.ovh.aurumgg.ovh`).
- **Вывелся другой IP** → возможно, домен за прокси (Cloudflare). Для
  выпуска сертификата через webroot прокси лучше временно выключить
  («серое облачко» вместо оранжевого).

---

## Шаг 1. Домашний сервер: открыть RCON и порт плагина

**Что делаем:** разрешаем VDS подключаться к RCON-портам игровых серверов и
к порту companion-плагина.

**Почему:** панель управляет серверами по RCON через туннель. Сейчас эти
порты закрыты для всех, включая VDS.

**Всё выполняется на домашнем сервере `10.0.0.2`.**

### 1.1. Включить RCON на игровых серверах

Если RCON ещё не включён, панель не сможет ничего сделать.

В Pterodactyl откройте сервер → **File Manager** → файл `server.properties`.
Найдите и выставьте:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=ЗАМЕНИТЕ_НА_СЛУЧАЙНЫЙ_ПАРОЛЬ
```

Пароль сгенерируйте (выполните на любой из машин):

```bash
openssl rand -base64 24
```

**Скопируйте вывод в менеджер паролей** — он понадобится на шаге 7, когда
будете настраивать сервер в панели.

Порты у разных серверов **должны отличаться**: 25575 у первого, 25576 у
второго и так далее. Иначе они не смогут запуститься одновременно.

Сохраните файл и **перезапустите сервер** через кнопку Restart в Pterodactyl.

> Игроков это выкинет на время перезапуска. Если не хотите прерывать игру —
> сделайте это, когда сервер и так будет перезагружаться.

### 1.2. Посмотреть, что будет сделано с фаерволом

```bash
cd /tmp/aurum-tmp   # или туда, куда склонировали
sudo bash deploy/firewall/home-server-additive.sh --dry-run
```

Скрипт покажет текущее состояние фаервола и список правил, которые собирается
добавить. **Ничего пока не меняется.**

### 1.3. Подогнать список портов под свои сервера

Откройте скрипт:

```bash
nano deploy/firewall/home-server-additive.sh
```

Найдите блок `RULES` (примерно 25-я строка):

```bash
declare -a RULES=(
  "25575|RCON сервера Выживание"
  "25576|RCON сервера Креатив"
  "8085|companion-плагин сервера Выживание"
  "8086|companion-плагин сервера Креатив"
)
```

Приведите его к своему списку серверов: для каждого сервера одна строка с
RCON-портом и одна с портом плагина. Формат: `"порт|описание"`.

Если companion-плагин ставить не планируете — строки про него можно удалить,
потом добавите.

Сохраните (**Ctrl+O**, **Enter**, **Ctrl+X**).

### 1.4. Применить

```bash
sudo bash deploy/firewall/home-server-additive.sh
```

Скрипт спросит подтверждение — надо ввести слово `да` и нажать Enter.
Любой другой ответ = отмена без изменений.

### Что должно получиться

Скрипт выведет состояние фаервола после изменений. Проверьте глазами:

1. **Старые правила на месте** — сравните с тем, что было выведено до
   изменений (скрипт печатает и «до», и «после»).
2. Появились новые строки вида `25575/tcp ALLOW IN 10.0.0.1`.

Проверка с VDS (выполните **на VDS**):

```bash
nc -vz 10.0.0.2 25575
```

Ожидаемо: `Connection to 10.0.0.2 25575 port [tcp/*] succeeded!`

Если `nc` не установлен: `sudo apt-get install -y netcat-openbsd`

### 🎮 Обязательная проверка

**Зайдите в игру и убедитесь, что серверы работают и игроки на месте.**
Это главная проверка этого шага.

### Если не получилось

- **`ufw не установлен`** → на машине другой фаервол. Посмотрите в
  `audit-home.txt` раздел «Фаервол»: если там вывод `iptables`, правила
  добавляются иначе — напишите мне, подберём команды под ваш случай.
- **`nc` говорит `Connection refused`** → фаервол пропустил, но на порту
  никто не слушает: RCON не включён или сервер не перезапущен после правки
  `server.properties`.
- **`nc` висит и отваливается по таймауту** → пакет не доходит: правило не
  добавилось или туннель не работает. Проверьте `wg show` на обеих машинах —
  должен быть свежий `latest handshake`.

---

## Шаг 2. VDS: Node.js, PostgreSQL, Redis

**Что делаем:** ставим то, на чём работает панель.

**Почему:** Node.js запускает саму панель, PostgreSQL хранит данные, Redis —
очереди фоновых задач.

**Всё выполняется на VDS.**

### 2.1. Ещё раз проверить порты

```bash
sudo ss -tulpn | grep -E ':(3001|5432|6379|6380)\b'
```

Как читать вывод: строка вида `LISTEN 0 511 127.0.0.1:6379 ... redis-server`
означает «порт 6379 занят процессом redis-server».

Ожидаемо: занят только **6379** (Redis Pterodactyl). Если заняты 5432, 3001
или 6380 — вернитесь к разделу «Что искать в выводе» шага 0.

### 2.2. Node.js 22 LTS

Pterodactyl написана на PHP, поэтому конфликта не будет.

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
```

Проверка:

```bash
node --version    # ожидаем v22.x.x
npm --version     # ожидаем 10.x или новее
```

> Команда `curl ... | sudo bash` выполняет скачанный скрипт с правами root.
> Здесь это официальный установщик NodeSource — стандартный способ поставить
> свежий Node на Ubuntu. Посмотреть, что он делает, до запуска:
> `curl -fsSL https://deb.nodesource.com/setup_22.x | less`

### 2.3. PostgreSQL 16

Pterodactyl использует MariaDB на домашнем сервере, так что 5432 свободен.

```bash
sudo apt-get install -y postgresql-16 postgresql-client-16
sudo systemctl enable --now postgresql
```

Проверка:

```bash
systemctl is-active postgresql    # ожидаем: active
psql --version                    # ожидаем: psql (PostgreSQL) 16.x
```

> Если PostgreSQL уже стоял (5432 был занят) — эту команду всё равно можно
> выполнить, она ничего не сломает: пакеты уже установлены, apt сообщит об этом.

### 2.4. Создать базу и пользователя

```bash
DB_PASS="$(openssl rand -base64 24)"
echo "════════════════════════════════════════════"
echo "ПАРОЛЬ БАЗЫ ДАННЫХ — СОХРАНИТЕ В МЕНЕДЖЕР ПАРОЛЕЙ:"
echo "$DB_PASS"
echo "════════════════════════════════════════════"

sudo -u postgres psql <<SQL
CREATE USER aurum WITH PASSWORD '${DB_PASS}';
CREATE DATABASE aurum_panel OWNER aurum;
SQL
```

**Скопируйте пароль в менеджер паролей прямо сейчас** — на следующем шаге он
понадобится, а переменная `DB_PASS` исчезнет, как только вы закроете терминал.

Проверка:

```bash
sudo -u postgres psql -c '\l' | grep aurum_panel
```

Ожидаемо: строка с `aurum_panel` и владельцем `aurum`.

PostgreSQL по умолчанию слушает только localhost — так и оставляем, наружу
он смотреть не должен.

### 2.5. Отдельный Redis на порту 6380

**Почему отдельный, а не общий с Pterodactyl:** Pterodactyl держит в Redis
сессии пользователей и кэш. Её настройка вытеснения по памяти может выбросить
наши задачи из очереди, а команда очистки с любой стороны сломает другую.
Отдельный процесс занимает несколько мегабайт и полностью снимает вопрос.

```bash
sudo apt-get install -y redis-server
```

Копируем конфиг и юнит. На этом шаге код ещё лежит во временном каталоге из
шага 0.1 — перейдите туда:

```bash
cd /tmp/aurum-tmp        # или туда, куда клонировали на шаге 0.1

sudo install -m 0644 deploy/redis/aurum.conf /etc/redis/aurum.conf
sudo install -d -o redis -g redis -m 0750 /var/lib/redis-aurum
sudo install -m 0644 deploy/systemd/aurum-redis.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-redis
```

Проверка — **оба должны ответить `PONG`**:

```bash
redis-cli -p 6380 ping    # наш новый
redis-cli -p 6379 ping    # Pterodactyl, как работал, так и работает
```

### Если не получилось

- **`redis-cli -p 6380` не отвечает** → посмотрите журнал:
  `sudo journalctl -u aurum-redis -n 30 --no-pager`.
  Частая причина — не создан каталог `/var/lib/redis-aurum` или у него не тот
  владелец. Повторите команду `install -d` из блока выше.
- **`Address already in use`** → порт 6380 занят. Выберите другой (6381),
  поменяйте его в `/etc/redis/aurum.conf`, в юните `aurum-redis.service`
  (строка `ExecStop`) и позже в `REDIS_URL`.
- **`redis-cli -p 6379` перестал отвечать** → это серьёзно, задет Redis
  Pterodactyl. Проверьте `systemctl status redis-server` и запустите:
  `sudo systemctl start redis-server`.

---

## Шаг 3. Выкладка кода и секреты

**Что делаем:** кладём код на сервер, собираем его, заполняем конфигурацию.

### 3.1. Создать пользователя для панели

```bash
sudo useradd --system --create-home --home-dir /opt/aurum-panel --shell /usr/sbin/nologin aurum
```

**Почему отдельный пользователь:** панель будет работать не от root. Если в
ней найдётся уязвимость, злоумышленник получит права этого ограниченного
пользователя, а не всей машины. `--shell /usr/sbin/nologin` означает, что
под ним нельзя залогиниться.

### 3.2. Скачать код

```bash
sudo -u aurum git clone https://github.com/Aki333chan/images.git /opt/aurum-panel
cd /opt/aurum-panel
sudo -u aurum git checkout claude/pterodactyl-admin-panel-core-984zye
```

> `sudo -u aurum` = «выполни от имени пользователя aurum». Так все файлы
> сразу принадлежат ему и не придётся чинить права потом.

### 3.3. Установить зависимости и собрать

```bash
sudo -u aurum npm ci
sudo -u aurum npm run build
sudo -u aurum npm run prisma:generate
```

Это самый долгий шаг — **3–7 минут**. `npm ci` качает библиотеки,
`npm run build` собирает бэкенд и фронтенд.

Проверка:

```bash
ls /opt/aurum-panel/apps/api/dist/main.js      # файл должен существовать
ls /opt/aurum-panel/apps/web/dist/index.html   # и этот тоже
```

### 3.4. Создать служебные каталоги

```bash
sudo install -d -o aurum -g aurum -m 0750 /var/log/aurum-panel
sudo install -d -o aurum -g aurum -m 0700 /var/backups/aurum-panel
```

### 3.5. Сгенерировать секреты

Выполните и **сразу сохраните вывод в менеджер паролей**:

```bash
echo "JWT_ACCESS_SECRET=$(openssl rand -base64 48)"
echo "JWT_REFRESH_SECRET=$(openssl rand -base64 48)"
echo "APP_ENCRYPTION_KEY=$(openssl rand -base64 32)"
```

> 🔑 **`APP_ENCRYPTION_KEY` — самый важный.** Им шифруются RCON-пароли и
> токены плагинов.
> **Потеряете** — сохранённые пароли серверов расшифровать нельзя, придётся
> вводить их заново через интерфейс.
> **Смените на живой базе** — уже сохранённые пароли станут нечитаемыми.
> Сохраните его в менеджер паролей до первого запуска.

### 3.6. Взять ключи Pterodactyl

Нужны два разных ключа.

**Ключ 1 — Application API** (панель читает список серверов):

1. Откройте `https://panel.aurumgg.ovh` под администратором.
2. Значок гаечного ключа (админка) → в левом меню **Application API**.
3. Кнопка **Create New**.
4. Description: `Aurum Panel`.
5. В списке прав найдите **Servers** и поставьте **Read**.
6. **Create**. Ключ показывается **один раз** — скопируйте сразу.

**Ключ 2 — Client API служебного пользователя** (консоль, питание, статистика):

1. Админка → **Users** → **Create New**.
   - Email: например `panel-bot@aurumgg.ovh`
   - Username: `panelbot`
   - Пароль: `openssl rand -base64 24`, сохраните в менеджер
2. Для каждого сервера, которым будет управлять панель: откройте сервер →
   вкладка **Users** (или **Subusers**) → **New User** → email `panelbot` →
   отметьте права на консоль, питание и просмотр.
3. Выйдите из админки и **войдите под `panelbot`**.
4. Правый верхний угол → **Account** → **API Credentials**.
5. **Create**, описание `Aurum Panel`, Allowed IPs оставьте пустым.
6. Скопируйте ключ — он тоже показывается один раз.

### 3.7. Заполнить файл конфигурации

```bash
sudo install -d -m 0750 -o root -g aurum /etc/aurum-panel
sudo install -m 0640 -o root -g aurum \
  /opt/aurum-panel/deploy/env/api.env.example /etc/aurum-panel/api.env
sudo nano /etc/aurum-panel/api.env
```

Заполните поля значениями, которые собрали:

| Поле | Откуда взять |
| --- | --- |
| `DATABASE_URL` | замените `ПАРОЛЬ_БД` на пароль из шага 2.4 |
| `JWT_ACCESS_SECRET` | из шага 3.5 |
| `JWT_REFRESH_SECRET` | из шага 3.5 |
| `APP_ENCRYPTION_KEY` | из шага 3.5 |
| `PTERO_APP_API_KEY` | ключ 1 из шага 3.6 |
| `PTERO_CLIENT_API_KEY` | ключ 2 из шага 3.6 |
| `OWNER_EMAIL` | ваш email — под ним будете входить |
| `OWNER_PASSWORD` | придумайте надёжный пароль, сохраните в менеджер |

⚠️ **Не оставляйте пробелов вокруг `=`.** Правильно: `API_PORT=3001`.
Неправильно: `API_PORT = 3001`.

⚠️ **Пароль в `DATABASE_URL` со спецсимволами.** Если в пароле есть
`@`, `/`, `:`, `#` или `?`, его нужно закодировать для URL. Проще
перегенерировать пароль без них:

```bash
openssl rand -hex 24
```

и обновить его в базе:

```bash
sudo -u postgres psql -c "ALTER USER aurum WITH PASSWORD 'новый_пароль';"
```

Сохраните файл (**Ctrl+O**, **Enter**, **Ctrl+X**).

Проверка прав доступа:

```bash
ls -l /etc/aurum-panel/api.env
```

Ожидаемо: `-rw-r----- 1 root aurum` — читать может только root и панель.

### 3.8. Создать таблицы и первого пользователя

```bash
cd /opt/aurum-panel/apps/api
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | grep -v '^$' | xargs) npx prisma migrate deploy
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | grep -v '^$' | xargs) npm run prisma:seed
```

Ожидаемый вывод: `All migrations have been successfully applied.` и
`Создан владелец ваш@email`.

### 3.9. Убрать пароль владельца из конфига

Он больше не нужен — пользователь уже создан:

```bash
sudo nano /etc/aurum-panel/api.env
```

Очистите значение: строка должна стать `OWNER_PASSWORD=`

### Если не получилось

- **`npm ci` падает с ошибкой сети** → повторите; иногда реестр npm
  отвечает не сразу.
- **`prisma migrate deploy`: `Can't reach database server`** → проверьте
  `DATABASE_URL`: имя пользователя `aurum`, база `aurum_panel`, хост
  `127.0.0.1`, порт `5432`, и пароль совпадает с тем, что задали в 2.4.
- **`password authentication failed`** → пароль в `DATABASE_URL` не тот.
  Задайте заново: `sudo -u postgres psql -c "ALTER USER aurum WITH PASSWORD 'новый';"`
  и поправьте `api.env`.
- **`OWNER_EMAIL и OWNER_PASSWORD должны быть заданы`** → вы уже очистили
  пароль (шаг 3.9), а сид ещё не запускали. Верните пароль, выполните сид,
  снова очистите.

---

## Шаг 4. Запуск сервиса

**Что делаем:** настраиваем автозапуск панели через systemd.

**Почему:** systemd поднимет панель после перезагрузки сервера и перезапустит,
если она упадёт.

### 4.1. Установить юнит

```bash
sudo install -m 0644 /opt/aurum-panel/deploy/systemd/aurum-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-api
```

### 4.2. Проверить

```bash
systemctl status aurum-api --no-pager
```

Ожидаемо: зелёное `active (running)`.

Посмотреть журнал (последние 50 строк):

```bash
journalctl -u aurum-api -n 50 --no-pager
```

Ищите строку `API слушает 10.0.0.1:3001`.

Проверить, что отвечает:

```bash
curl -s http://10.0.0.1:3001/api/health
```

Ожидаемо: `{"status":"ok","uptimeSeconds":...}`

```bash
curl -s http://10.0.0.1:3001/api/health/ready
```

Ожидаемо: `{"ready":true,"checks":{"database":"ok","redis":"ok"}}`

Если `database` или `redis` = `fail`, вернитесь к шагам 2.4 / 2.5.

### 4.3. Убедиться, что API не торчит в интернет

Панель слушает только адрес туннеля — на публичном интерфейсе порт не открыт
вообще:

```bash
sudo ss -tulpn | grep 3001
```

Ожидаемо: строка вида `LISTEN ... 10.0.0.1:3001`.
**Если видите `0.0.0.0:3001` — это неправильно**, проверьте `API_BIND` в
`api.env` и перезапустите: `sudo systemctl restart aurum-api`.

Проверка снаружи — **со своего компьютера**, не с сервера:

```bash
nc -vz <публичный-IP-VDS> 3001
```

Ожидаемо: отказ или таймаут. Если соединение проходит — что-то не так,
остановитесь и разберитесь.

Дополнительный барьер (на случай, если однажды поменяете `API_BIND`):

```bash
ip -brief address                # найдите имя внешнего интерфейса, обычно eth0
sudo ufw deny in on eth0 to any port 3001 comment 'Aurum API только через nginx'
```

### Если не получилось

- **`active (running)`, но `curl` не отвечает** → смотрите журнал, скорее
  всего процесс упал сразу после старта.
- **`failed`, в журнале `EADDRNOTAVAIL`** → WireGuard не поднят, адреса
  `10.0.0.1` на машине нет. Проверьте `wg show` и `ip -brief address`.
- **`failed`, в журнале `Переменная окружения ... обязательна`** → в
  `api.env` не заполнено обязательное поле, имя указано в сообщении.
- **Постоянно перезапускается** → `journalctl -u aurum-api -n 100 --no-pager`
  и читайте первую ошибку сверху, а не последнюю.

---

## Шаг 5. nginx и сертификат

**Что делаем:** открываем панель по адресу `https://manage.aurumgg.ovh`.

**Почему в два подшага:** сертификат нельзя выпустить, пока домен не отвечает
по HTTP, а полный конфиг не заработает без сертификата. Поэтому сначала
временный блок, потом сертификат, потом рабочий конфиг.

> Существующий блок `panel.aurumgg.ovh` мы **не редактируем**. nginx выбирает
> конфигурацию по имени домена, поэтому наш файл на неё не влияет.

### 5.1. Временный блок для выпуска сертификата

```bash
sudo install -d -m 0755 /var/www/certbot
sudo install -m 0644 \
  /opt/aurum-panel/deploy/nginx/manage.aurumgg.ovh.bootstrap.conf \
  /etc/nginx/sites-available/manage.aurumgg.ovh
sudo ln -s /etc/nginx/sites-available/manage.aurumgg.ovh /etc/nginx/sites-enabled/
```

**Обязательно проверьте синтаксис перед применением:**

```bash
sudo nginx -t
```

Ожидаемо: `syntax is ok` и `test is successful`.
**Если ошибка — не перезагружайте nginx**, иначе рискуете уронить и
Pterodactyl. Прочитайте сообщение: в нём указан файл и номер строки.

```bash
sudo systemctl reload nginx
```

> `reload`, а не `restart` — nginx подхватывает новый конфиг, не разрывая
> текущие соединения. Пользователи Pterodactyl ничего не заметят.

Проверка:

```bash
curl -sI http://manage.aurumgg.ovh/
```

Ожидаемо: `HTTP/1.1 503` — это наша заглушка, значит блок работает.

### 5.2. Выпустить сертификат

Если certbot не установлен:

```bash
sudo apt-get install -y certbot
```

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d manage.aurumgg.ovh
```

При первом запуске certbot спросит email (для писем об истечении) и согласие
с условиями.

> **Почему `certonly --webroot`, а не `--nginx`:** плагин `--nginx` сам правит
> конфиги nginx и перезагружает его. На живой системе это лишний риск задеть
> блок Pterodactyl. `certonly --webroot` не трогает конфиги вообще.

Проверка:

```bash
sudo certbot certificates
```

Ожидаемо: в списке и `panel.aurumgg.ovh`, и `manage.aurumgg.ovh`.

### 5.3. Рабочий конфиг

```bash
sudo install -m 0644 /opt/aurum-panel/deploy/nginx/aurum-limits.conf /etc/nginx/conf.d/
sudo install -m 0644 /opt/aurum-panel/deploy/nginx/manage.aurumgg.ovh.conf \
  /etc/nginx/sites-available/manage.aurumgg.ovh

sudo nginx -t
sudo systemctl reload nginx
```

Правил фаервола для 80/443 добавлять не нужно — они уже открыты под Pterodactyl.

### 5.4. Проверить всё

```bash
# Панель отдаётся
curl -sI https://manage.aurumgg.ovh/ | head -1          # HTTP/2 200

# API работает через nginx
curl -s https://manage.aurumgg.ovh/api/health           # status ok

# Pterodactyl не пострадала
curl -sI https://panel.aurumgg.ovh/ | head -1           # HTTP/2 200

# Внутренний путь закрыт снаружи
curl -sI https://manage.aurumgg.ovh/api/internal/x | head -1   # HTTP/2 404
```

Автопродление сертификатов (проверка без реального выпуска):

```bash
sudo certbot renew --dry-run
```

**Откройте `https://manage.aurumgg.ovh` в браузере** — должна появиться форма
входа, замочек в адресной строке без предупреждений.

### Если не получилось

- **`nginx -t` ругается на `limit_req zone=aurum_login`** → не установлен
  файл `aurum-limits.conf`. Повторите первую команду шага 5.3.
- **certbot: `Timeout during connect`** → домен не указывает на этот сервер
  (вернитесь к шагу 0.5) или порт 80 закрыт.
- **certbot: `unauthorized ... 404`** → каталог `/var/www/certbot` не создан
  или временный блок не включён. Проверьте `ls /etc/nginx/sites-enabled/`.
- **Браузер: `502 Bad Gateway`** → nginx работает, а API нет.
  `systemctl status aurum-api`.
- **Браузер: страница белая, в консоли ошибки загрузки файлов** → не собран
  фронтенд. Вернитесь к 3.3.
- **Сломался `panel.aurumgg.ovh`** → немедленно отключите наш блок:
  `sudo rm /etc/nginx/sites-enabled/manage.aurumgg.ovh && sudo nginx -t && sudo systemctl reload nginx`

---

## Шаг 6. Бэкапы

**Что делаем:** настраиваем ежедневный бэкап базы панели.

**Почему:** база хранит пользователей, тикеты, баны и аудит. Восстановить это
руками невозможно.

Бэкапится **только база панели**. MariaDB Pterodactyl на домашнем сервере —
отдельная система со своим бэкапом, наш скрипт её не видит.

### 6.1. Настроить доступ к базе для бэкапа

```bash
sudo install -m 0640 -o root -g aurum \
  /opt/aurum-panel/deploy/env/backup.env.example /etc/aurum-panel/backup.env
sudo nano /etc/aurum-panel/backup.env
```

Замените `ПАРОЛЬ_БД` на тот же пароль базы, что и в `api.env`.

### 6.2. Установить таймер

```bash
sudo chmod +x /opt/aurum-panel/deploy/scripts/*.sh
sudo install -m 0644 /opt/aurum-panel/deploy/systemd/aurum-backup.service /etc/systemd/system/
sudo install -m 0644 /opt/aurum-panel/deploy/systemd/aurum-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aurum-backup.timer
```

### 6.3. Проверить сразу, не дожидаясь ночи

```bash
sudo systemctl start aurum-backup.service
journalctl -u aurum-backup -n 20 --no-pager
ls -lh /var/backups/aurum-panel/
```

Ожидаемо: в журнале `Готово: /var/backups/...dump`, в каталоге лежит файл.

Когда сработает в следующий раз:

```bash
systemctl list-timers aurum-backup --no-pager
```

### 6.4. Проверить, что из бэкапа можно восстановиться

**Это важнее, чем сам бэкап.** Непроверенный бэкап — не бэкап.

Проверяем безопасно, на отдельной временной базе (рабочая не трогается):

```bash
sudo -u postgres createdb aurum_restore_test
sudo -u postgres pg_restore --dbname=aurum_restore_test --no-owner \
  $(ls -t /var/backups/aurum-panel/*.dump | head -1)
sudo -u postgres psql -d aurum_restore_test -c 'select count(*) from users;'
sudo -u postgres dropdb aurum_restore_test
```

Ожидаемо: `count` больше нуля.

> ⚠️ Дампы лежат на той же машине, что и база. Если умрёт диск VDS — пропадёт
> и то, и другое. Настройте копирование дампов куда-нибудь ещё: на домашний
> сервер через туннель, в облако, куда угодно. Даже раз в неделю руками
> лучше, чем ничего.

---

## Шаг 7. Первый вход и настройка

**Что делаем:** входим в панель и подключаем игровые сервера.

### 7.1. Войти

Откройте `https://manage.aurumgg.ovh`, введите `OWNER_EMAIL` и пароль из
шага 3.7. Вы войдёте как **ГМ** — полный доступ.

### 7.2. Включить 2FA (рекомендуется)

Меню **Безопасность** → **Настроить 2FA** → отсканируйте код приложением
(Google Authenticator, Aegis, 1Password) → введите шестизначный код.

### 7.3. Подтянуть список серверов

Меню **Серверы** → кнопка **Синхронизировать с Pterodactyl**.

Появятся все серверы из Pterodactyl. Если список пуст — проверьте
`PTERO_APP_API_KEY` и журнал: `journalctl -u aurum-api -n 50 --no-pager`.

### 7.4. Настроить сервер

Откройте карточку сервера:

1. **Игровой модуль** → выберите `Minecraft (Java)`.
2. Появятся вкладки: Консоль, Игроки, Баны, Whitelist, Инвентарь.
3. Настройте подключение по RCON — хост `10.0.0.2`, порт и пароль из шага 1.1.
4. Откройте вкладку **Игроки** — если видите список онлайн, RCON работает.

### 7.5. Завести друзей

Меню **Доступы** (виден только ГМ) → создайте пользователей, выберите роль
(Админ / Модератор) и отметьте, к каким серверам у каждого есть доступ.

Изменения применяются мгновенно: если человек в этот момент сидит в панели,
у него меню перерисуется само, без перезахода.

### 7.6. Companion-плагин (по желанию)

Даёт инвентарь игроков, их координаты и команду `/ticket` в игре.
Инструкция: [`docs/companion.md`](../docs/companion.md).

---

## Обновление на новую версию

```bash
cd /opt/aurum-panel

# 1. Свежий бэкап перед миграциями — обязательно
sudo systemctl start aurum-backup.service

# 2. Забрать новый код
sudo -u aurum git pull

# 3. Пересобрать
sudo -u aurum npm ci
sudo -u aurum npm run build

# 4. Применить миграции БД
cd apps/api
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | grep -v '^$' | xargs) npx prisma migrate deploy

# 5. Перезапустить
sudo systemctl restart aurum-api

# 6. Проверить
curl -s http://10.0.0.1:3001/api/health/ready
```

Фронтенд обновится сам — nginx отдаёт файлы из `apps/web/dist`.
Пользователям, возможно, придётся обновить страницу (Ctrl+Shift+R).

## Откат

Если после обновления что-то сломалось:

```bash
sudo systemctl stop aurum-api
cd /opt/aurum-panel
sudo -u aurum git log --oneline -5          # найдите предыдущий коммит
sudo -u aurum git checkout <хеш-коммита>
sudo -u aurum npm ci && sudo -u aurum npm run build

# Если миграции испортили данные — восстановиться из дампа:
sudo -u aurum /opt/aurum-panel/deploy/scripts/restore-db.sh \
  /var/backups/aurum-panel/<файл>.dump

sudo systemctl start aurum-api
```

## Полностью выключить панель, не задев Pterodactyl

```bash
sudo systemctl stop aurum-api aurum-redis aurum-backup.timer
sudo systemctl disable aurum-api aurum-redis aurum-backup.timer
sudo rm /etc/nginx/sites-enabled/manage.aurumgg.ovh
sudo nginx -t && sudo systemctl reload nginx
```

`panel.aurumgg.ovh` продолжит работать — её конфигурацию мы не трогали.

---

## Чек-лист секретов

Все живут в `/etc/aurum-panel/api.env` с правами `0640 root:aurum`.
Ни один не попадает в git.

| Переменная | Как получить | Что будет при потере |
| --- | --- | --- |
| `JWT_ACCESS_SECRET` | `openssl rand -base64 48` | всех разлогинит, не страшно |
| `JWT_REFRESH_SECRET` | `openssl rand -base64 48` | то же |
| `APP_ENCRYPTION_KEY` | `openssl rand -base64 32` | **пароли серверов придётся вводить заново** |
| Пароль БД | `openssl rand -hex 24` | меняется через `ALTER USER` |
| `PTERO_APP_API_KEY` | Pterodactyl → Admin → Application API | создать новый |
| `PTERO_CLIENT_API_KEY` | Account → API Credentials под `panelbot` | создать новый |
| RCON-пароли | `openssl rand -base64 24` на сервер | в `server.properties` и в UI панели |
| Токены companion | `openssl rand -base64 32` на сервер | только ASCII; в `config.yml` и в UI |
| `DEEPSEEK_API_KEY` | понадобится на следующем этапе | добавится сюда же |

Сгенерировать всё разом:

```bash
printf 'JWT_ACCESS_SECRET=%s\n'  "$(openssl rand -base64 48)"
printf 'JWT_REFRESH_SECRET=%s\n' "$(openssl rand -base64 48)"
printf 'APP_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
printf 'DB_PASSWORD=%s\n'        "$(openssl rand -hex 24)"
```

**Не вставляйте секреты аргументами команд** — они сохранятся в
`~/.bash_history`. Правьте `api.env` редактором.

---

## Если что-то пошло не так: общая шпаргалка

### Куда смотреть

```bash
# Журнал панели, последние 100 строк
journalctl -u aurum-api -n 100 --no-pager

# Журнал в реальном времени (выход — Ctrl+C)
journalctl -u aurum-api -f

# Состояние всех наших сервисов разом
systemctl status aurum-api aurum-redis aurum-backup.timer --no-pager

# Ошибки nginx для нашего домена
sudo tail -50 /var/log/nginx/manage.aurumgg.ovh.error.log

# Проверка зависимостей панели
curl -s http://10.0.0.1:3001/api/health/ready
```

### Частые ситуации

| Симптом | Вероятная причина | Что делать |
| --- | --- | --- |
| Браузер: 502 Bad Gateway | API не запущен | `systemctl status aurum-api`, смотреть журнал |
| Браузер: 503 от нашей заглушки | остался bootstrap-конфиг | выполнить шаг 5.3 |
| `ready` → `database: fail` | Postgres не отвечает или пароль не тот | `systemctl status postgresql`, проверить `DATABASE_URL` |
| `ready` → `redis: fail` | наш Redis не поднят | `systemctl status aurum-redis` |
| Вкладка «Игроки» пуста, ошибка 503 | RCON недоступен | проверить фаервол (шаг 1), `nc -vz 10.0.0.2 25575` |
| Консоль сервера не открывается | не тот Client API key или у `panelbot` нет доступа к серверу | шаг 3.6, пункт 2 |
| Список серверов пуст | Application API key без прав на чтение серверов | пересоздать ключ 1 |
| Панель «забыла» пароли RCON | сменился `APP_ENCRYPTION_KEY` | вернуть прежний ключ из менеджера паролей |

### Аварийное отключение

Если новая панель мешает работе Pterodactyl — выключите её одной командой,
разбираться будете потом:

```bash
sudo systemctl stop aurum-api
sudo rm -f /etc/nginx/sites-enabled/manage.aurumgg.ovh
sudo nginx -t && sudo systemctl reload nginx
```

---

## Финальный чек-лист: перед тем как звать друзей

Отмечайте по пунктам, не пропуская.

### Ничего не сломалось

- [ ] `https://panel.aurumgg.ovh` открывается, вход работает
- [ ] 🎮 **Игровые сервера работают, игроки заходят** — зайти в игру самому
      и посмотреть, что народ онлайн
- [ ] `sudo ufw status verbose` на 10.0.0.2 — прежние правила на месте,
      добавились только новые
- [ ] `wg show` на обеих машинах — свежий `latest handshake`
- [ ] `systemctl status wings` на домашнем сервере — `active`
- [ ] `redis-cli -p 6379 ping` → `PONG` (Redis Pterodactyl жив)

### Новая панель работает

- [ ] `dig +short manage.aurumgg.ovh` = публичный IP VDS
- [ ] `https://manage.aurumgg.ovh` открывается, замочек без предупреждений
- [ ] Вход под ГМ проходит
- [ ] 2FA включается и работает (проверьте выход и повторный вход)
- [ ] `curl -s https://manage.aurumgg.ovh/api/health/ready` → оба `ok`
- [ ] Синхронизация подтянула список серверов
- [ ] Консоль сервера открывается и показывает вывод
- [ ] Вкладка «Игроки» показывает тех, кто сейчас онлайн
- [ ] Кик или бан доходит до сервера (проверьте на себе)
- [ ] Если поставлен плагин: инвентарь открывается, `/ticket` в игре создаёт
      тикет, ответ модератора приходит игроку в чат

### Безопасность

- [ ] `sudo ss -tulpn | grep 3001` → только `10.0.0.1:3001`, не `0.0.0.0`
- [ ] `nc -vz <публичный-IP> 3001` со своего компьютера → отказ
- [ ] `curl -sI https://manage.aurumgg.ovh/api/internal/x` → 404
- [ ] `ls -l /etc/aurum-panel/api.env` → `-rw-r----- root aurum`
- [ ] `OWNER_PASSWORD` в `api.env` очищен
- [ ] `nc -vz <публичный-IP-домашнего> 25575` → отказ (RCON не в интернете)
- [ ] Все секреты записаны в менеджер паролей, особенно `APP_ENCRYPTION_KEY`

### Эксплуатация

- [ ] `systemctl list-timers aurum-backup` — таймер активен, есть время
      следующего запуска
- [ ] В `/var/backups/aurum-panel/` лежит хотя бы один дамп
- [ ] Восстановление из дампа проверено на тестовой базе (шаг 6.4)
- [ ] `sudo certbot renew --dry-run` проходит для обоих доменов
- [ ] `systemctl is-enabled aurum-api aurum-redis` → `enabled` (переживут ребут)
- [ ] **Ребут проверен**: `sudo reboot`, через пару минут обе панели
      поднялись сами

Последний пункт сделайте в спокойное время, а не когда все играют.
После ребута проверьте `https://panel.aurumgg.ovh`, `https://manage.aurumgg.ovh`
и что игровые серверы поднялись.
