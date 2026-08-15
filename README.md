# Aurum Panel — ядро модульной панели администрирования игровых серверов

Веб-панель поверх self-hosted Pterodactyl: единая точка входа для ГМ, админов и
модераторов. Этот этап — **ядро и архитектура модулей, без игровой логики**:
аутентификация, RBAC с привязкой к серверам, живое обновление прав, реестр
игровых модулей, тикеты, аудит-лог и каркас UI.

## Топология

| Компонент | Где живёт | Как обращаемся |
| --- | --- | --- |
| Pterodactyl Panel | VDS, `panel.aurumgg.ovh` | локально по `http://127.0.0.1` (не через публичный домен) |
| Aurum Panel (этот проект) | тот же VDS, `manage.aurumgg.ovh` | nginx → API :3001 + статика веба |
| PostgreSQL 16 + Redis | тот же VDS | localhost |
| Wings, игровые сервера, MariaDB Pterodactyl | домашний сервер | WireGuard `10.0.0.2` |

Обращения к Pterodactyl API — локальные. RCON и прочая связь с игровыми
серверами пойдёт через уже поднятый туннель на `10.0.0.2` (в ядре не
используется — появится в игровых модулях).

## Структура монорепозитория

```
apps/
  api/                NestJS 10 + Prisma + BullMQ
    prisma/           schema.prisma, миграции, seed
    src/
      auth/           логин, 2FA, JWT, сессии
      rbac/           права из БД, guards, декораторы
      users/          экран управления доступом (только ГМ)
      servers/        зеркало серверов Pterodactyl + периодический синк
      tickets/        core-сервис тикетов (используется модулями)
      audit/          аудит-лог + автоматический interceptor
      pterodactyl/    Application API, Client API, шифрованные ключи
      modules/        реестр игровых модулей + modules.config.ts
      ws/             socket.io-gateway (permissions.updated, tickets.updated)
  web/                React 18 + Vite + TS + Tailwind (компоненты в стиле shadcn/ui)
    src/modules/      фронтенд-реестр вкладок модулей
packages/
  shared/             общие типы: роли, права, GameModuleManifest, WS-события, DTO
```

**Почему так.** `packages/shared` — единственный источник истины для контракта
между фронтом и бэком: роли, ключи прав, `GameModuleManifest` и DTO описаны один
раз, поэтому рассинхрон ловится компилятором, а не в рантайме. Пакет собирается
в два формата (CommonJS для NestJS, ESM для Vite) — оба приложения потребляют его
как обычную зависимость. Игровые модули лежат внутри `apps/api/src/modules` и
`apps/web/src/modules`, а не отдельными пакетами: модуль почти всегда состоит из
пары «роуты + вкладки», и держать их в одном репозитории без отдельного цикла
публикации проще.

## Быстрый старт (локальная разработка)

```bash
# 1. Postgres + Redis
docker compose up -d

# 2. Зависимости
npm install

# 3. Конфигурация
cp .env.example apps/api/.env
#    Обязательно сгенерируйте секреты:
#    openssl rand -base64 48   -> JWT_ACCESS_SECRET, JWT_REFRESH_SECRET
#    openssl rand -base64 32   -> APP_ENCRYPTION_KEY (ровно 32 байта)
#    Укажите OWNER_EMAIL / OWNER_PASSWORD — из них создастся владелец.

# 4. Общие типы, схема БД, владелец
npm run build -w @aurum/shared
npm run prisma:generate
npm run prisma:migrate      # или prisma migrate deploy на проде
npm run prisma:seed

# 5. Запуск (в двух терминалах)
npm run dev:api             # http://localhost:3001/api
npm run dev:web             # http://localhost:5173
```

Vite проксирует `/api` и `/ws` на API, поэтому cookie остаются first-party и в
разработке ничего дополнительно настраивать не нужно.

### Проверки качества

```bash
npm test          # unit-тесты (auth, ротация refresh, RBAC guard, тикеты)
npm run lint      # ESLint
npm run build     # сборка shared + api + web (полная проверка типов)
```

## Настройка Pterodactyl

Нужны **два** ключа — они хранятся в БД зашифрованными (AES-256-GCM) и никогда
не попадают на фронтенд:

1. **Application API key** — админка Pterodactyl → *Application API*. Полный
   доступ; используется только бэкендом для зеркалирования списка серверов
   (`GET /api/application/servers`).
2. **Client API key служебного пользователя** — заведите отдельного пользователя
   Pterodactyl, добавьте его на нужные сервера и создайте ему Client API key
   (*Account → API Credentials*). Через него идут консоль, питание и статистика:
   `GET /api/client/servers/{id}/websocket`, `POST .../power`,
   `POST .../command`, `GET .../resources`.

Положите оба ключа в `PTERO_APP_API_KEY` / `PTERO_CLIENT_API_KEY`. При первом
старте они шифруются и переносятся в таблицу `integration_secrets`, после чего
переменные окружения можно очистить.

Список серверов синхронизируется при старте и далее каждые 5 минут (BullMQ).
Сервер, пропавший в Pterodactyl, не удаляется, а помечается статусом `missing` —
чтобы не терять тикеты и историю.

## Роли и права

| Роль в коде | Подпись в UI | Что может |
| --- | --- | --- |
| `OWNER` | ГМ | всё, включая экран «Доступы»; видит все сервера |
| `ADMIN` | Админ | управление серверами, питание, тикеты, аудит — в рамках привязанных серверов |
| `MODERATOR` | Модератор | просмотр серверов и работа с тикетами — в рамках привязанных серверов |
| `VIEWER` | Наблюдатель | только просмотр |

**Права всегда вычисляются по текущему состоянию БД.** В access-токене лежат
только `sub` (id пользователя) и `sid` (id сессии) — ни ролей, ни прав. Каждый
защищённый запрос проходит через `PermissionsGuard`, который читает роль и
привязки серверов из БД. Поэтому смена роли действует немедленно, без релогина и
без ожидания истечения токена.

Для не-OWNER пустой список привязок означает «нет доступа ни к одному серверу»
(доступ выдаётся явно), а не «ко всем».

### Живое обновление прав

При изменении роли или привязок сервер шлёт пользователю WS-событие
`permissions.updated`. Фронтенд по нему перезапрашивает `GET /api/auth/me` и
перерисовывает меню и вкладки: пропавший сервер или пункт меню исчезает сам.
Деактивация пользователя дополнительно отзывает все его сессии.

## Игровые модули

Модуль описывается манифестом (`packages/shared/src/module-manifest.ts`):

```ts
interface GameModuleManifest {
  id: string;                        // 'minecraft-vanilla'
  displayName: string;
  capabilities: ModuleCapability[];  // console | playerList | banKick | whitelist
                                     // | inventory | quickCommands | tickets
  permissions: ModulePermission[];   // ключи вида '<id>.<action>' + роли по умолчанию
}
```

Backend-часть (`BackendGameModule`) добавляет к манифесту NestJS-модуль с
роутами, WS-gateway и кронами. Включённые модули перечислены в
`apps/api/src/modules/modules.config.ts` и монтируются при старте динамически.

**Как добавить модуль**

1. `apps/api/src/modules/<id>/` — NestJS-модуль; контроллеры вешаются на
   `modules/<id>/servers/:serverId/...`, права проверяются декораторами
   `@RequirePermission('<id>.action')` и `@ServerScoped('serverId')`.
2. `<id>.def.ts` — манифест + ссылка на NestJS-модуль; зарегистрировать в
   `ALL_MODULES` (`module-registry.ts`).
3. `apps/web/src/modules/<id>/` — компоненты вкладок; связать capability →
   компонент в `MODULE_TAB_REGISTRY`.
4. Добавить id в `modules.config.ts`.
5. Модели БД модуля — в общий `schema.prisma` с префиксом таблиц `mod_<id>_`.

Убрать id из `modules.config.ts` — безопасно: роуты, крон и вкладки исчезают,
**данные в БД остаются нетронутыми**.

В комплекте есть модуль `test-dummy` — фейковый стенд, на котором видно, как
работают динамические вкладки, права модуля и вызов core-сервиса тикетов.

### Возможности, которые даёт само ядро

Некоторые capability модулю реализовывать не нужно — достаточно объявить их в
манифесте, вкладку нарисует общий компонент:

| Capability | Кто реализует |
| --- | --- |
| `console` | ядро: WebSocket-консоль Wings через Pterodactyl |
| `tickets` | ядро: общий экран «Тикеты» |

## Модуль Minecraft (Java Edition)

Первый игровой модуль: Paper / Spigot / Vanilla под Pterodactyl. Управление идёт
по **RCON** (протокол Source RCON) через уже поднятый WireGuard-туннель на
игровую машину.

| Возможность | Как работает |
| --- | --- |
| Игроки | разбор ответа `list`; с companion-плагином — UUID и пинг |
| Кик / бан | RCON-команда + собственная таблица банов с причиной, сроком и модератором |
| Whitelist | `whitelist list/add/remove`, источник истины — сам сервер |
| Быстрые команды | список шаблонов в `quick-commands.config.ts`, блок на дашборде сервера |
| Произвольная команда | только ГМ и Админ, всегда попадает в аудит |
| Инвентарь | требует companion-плагина (см. `docs/companion.md`) |
| Консоль | из ядра, через Pterodactyl — модуль её не дублирует |

### Права модуля

`minecraft.players.view`, `minecraft.kick`, `minecraft.ban`,
`minecraft.ban.pardon`, `minecraft.whitelist`, `minecraft.quick-commands`,
`minecraft.command.raw`, `minecraft.inventory.view`, `minecraft.configure`.

Роли по умолчанию заданы в манифесте: Модератор может смотреть игроков, кикать
и банить; снятие бана, whitelist и произвольная команда — у Админа;
`minecraft.configure` (настройки подключения) — только у ГМ.

### Настройка подключения

Роль ГМ задаёт для каждого сервера RCON-хост (приватный адрес `10.0.0.2`), порт
и пароль. Значения шифруются AES-256-GCM и кладутся в `servers.credentials_enc`.

**Секреты не покидают бэкенд:** эндпоинт статуса отдаёт только флаги
«настроено / не настроено», а тело запросов на настройку помечено декоратором
`@AuditRedactBody()` — в аудит-лог пишется факт вызова (кто, когда, какой
сервер), но не адреса и пароли.

Со стороны Minecraft нужно включить RCON в `server.properties`:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<длинный случайный пароль>
```

RCON слушает на всех интерфейсах, поэтому порт должен быть закрыт файрволом
для всего, кроме адреса туннеля.

### Особенности реализации

- **Соединения переиспользуются**, команды на один сервер идут строго
  последовательно (очередь), при обрыве — одна прозрачная попытка
  переподключиться и повторить. Простаивающее соединение закрывается через 5 минут.
- **Временные баны**: у ванильного сервера их нет, поэтому срок хранится в
  панели, а снимает бан крон раз в минуту (`minecraft-ban-expiry`).
- **Защита от инъекций**: ники валидируются регулярным выражением Minecraft,
  а из текстовых аргументов вырезаются переводы строк и управляющие символы —
  иначе причиной бана можно было бы дописать вторую команду.
- Если игровой сервер недоступен, модуль отвечает `503` с понятным текстом,
  а панель продолжает работать.

## Тикеты

Ядро предоставляет модулям сервис:

```ts
ticketsService.createOrAppendTicket(serverId, playerUuid, playerName, text)
```

Правило: **один открытый тикет на пару (server_id, player_uuid)**. Повторное
обращение добавляет сообщение в существующий тикет; после закрытия следующее
обращение создаёт новый. Инвариант закреплён частичным уникальным индексом
(`tickets_one_open_per_player`), поэтому он держится и при параллельных запросах.

Права: `tickets.view`, `tickets.respond`, `tickets.close`. Число открытых тикетов
показывается бейджем в меню и обновляется по WS-событию `tickets.updated`.

## Аудит-лог

`AuditInterceptor` автоматически пишет каждый успешный мутирующий запрос
(POST/PUT/PATCH/DELETE): актор, действие, тип и id объекта, метаданные. Поля с
паролями, токенами и ключами заменяются на `[redacted]`; логин и refresh не
логируются. Экран «Аудит» с фильтрами доступен по праву `audit.view` (ГМ и
Админ). Колонка `actor_type` предусматривает значение `ai` — для будущих
автоматических действий.

## Развёртывание на VDS

```bash
npm ci
npm run build                       # shared + api + web
npm run prisma:generate
npm --workspace @aurum/api run prisma:deploy
node apps/api/dist/main.js          # под systemd
```

Статика веба — `apps/web/dist`. Пример nginx для `manage.aurumgg.ovh`:

```nginx
location /api/ { proxy_pass http://127.0.0.1:3001; }

location /ws  {                      # WebSocket-эндпоинт панели
  proxy_pass http://127.0.0.1:3001;
  proxy_http_version 1.1;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";
}

location / { root /var/www/aurum-panel; try_files $uri /index.html; }
```

В продакшене выставьте `NODE_ENV=production` (refresh-cookie получит флаг
`Secure`) и `WEB_ORIGIN=https://manage.aurumgg.ovh`.

## Модель безопасности

- Пароли — argon2id (`argon2` с параметрами по умолчанию).
- Access-токен живёт 15 минут и не содержит прав; refresh — непрозрачная строка
  в httpOnly-cookie с областью `/api/auth`, в БД лежит только её SHA-256.
- Refresh ротируется при каждом обновлении. Повторное использование старого
  токена трактуется как кража и отзывает сессию целиком.
- 2FA — TOTP, секрет хранится зашифрованным и активируется только после
  подтверждения кодом.
- Ключи Pterodactyl и доп. креды серверов шифруются AES-256-GCM ключом
  `APP_ENCRYPTION_KEY` и существуют только на бэкенде.
- Последнего активного ГМ нельзя понизить или деактивировать.
