# Первый деплой экосистемы Aurum без переноса старых балансов

Дата runbook: 2026-09-12.

Этот сценарий предназначен для тестового сервера, на котором старые балансы
Essentials/Vault не имеют ценности и обнулены. Настройки WorldGuard, Auth,
Guilds и прочих плагинов сохраняются. AurumCore получает новую пустую MariaDB и
становится единственным источником истины для денег.

## 1. Что получится после перехода

- AurumCore хранит счета, несколько валют, казну, журнал операций и резервы.
- VaultUnlocked остаётся установленным как совместимый API-мост для сторонних
  плагинов. Он не является хранилищем денег.
- EssentialsX остаётся установленным ради остальных команд, но его `/eco` не
  используется для управления новой экономикой.
- Arena, Slots, NPC и Guilds проводят денежные операции через AurumCore.
- Companion передаёт новые данные панели. Вкладка Economy появляется только
  после того, как панель увидит активный AurumCore.
- Fabric-мод AurumUI остаётся необязательным. Без него все игровые функции
  доступны командами и обычными Minecraft GUI.

## 2. Как разделить базы данных

Рекомендуемая схема:

| Система | Движок | База |
| --- | --- | --- |
| веб-панель | PostgreSQL | существующая `aurum_panel` |
| AurumCore | MariaDB | новая пустая `aurum_core` |
| Auth/Guilds/прочие плагины | MariaDB или SQLite | существующие базы без изменений |

Можно использовать тот же физический сервер или контейнер MariaDB. Отдельный
процесс БД ради Core не нужен. Но Core следует выдать отдельную логическую базу
и, по возможности, отдельного пользователя: так проще ограничить права,
сделать точный бэкап и откатить только финансовый ledger.

Не помещайте таблицы Core в базу WorldGuard/Auth/Guilds, даже если префиксы
таблиц технически предотвращают совпадение имён. Практического выигрыша в
производительности это не даст.

## 3. Важные ограничения перед началом

1. Minecraft-сервер должен оставаться остановленным до завершения резервных
   копий.
2. Не используйте `/reload`, PlugMan и горячую замену JAR. На каждом этапе
   выполняйте полный stop/start.
3. Не открывайте сервер игрокам до третьего, контрольного запуска.
4. Не пользуйтесь пока установкой Aurum Package в один клик: release-пакет в
   Addons будет опубликован после staging-проверки. Для этого деплоя загрузите
   JAR вручную из подготовленного комплекта.
5. Не удаляйте папки `plugins/AurumArena`, `plugins/AddonsNPC` и прочие папки
   данных. Старые тестовые сущности сначала должен увидеть новый плагин, чтобы
   их можно было удалить штатно без осиротевших entity/голограмм.

## 4. Записать текущее состояние

Перед обновлением сохраните:

- текущий commit панели: `git rev-parse HEAD` в `/opt/aurum-panel`;
- список JAR и их версий из папки `plugins`;
- текущие конфиги Companion, Auth, Guilds, Arena, NPC и Slots;
- адреса и имена существующих БД без публикации паролей;
- UUID Minecraft-сервера из URL панели;
- secondary allocation Companion (обычно порт `8085`).

## 5. Сделать резервные копии

### 5.1. Файлы Minecraft

В Pterodactyl откройте `Backups` → `Create Backup`, ничего не исключайте и
дождитесь состояния `Completed`. Если возможно, скачайте копию с узла.

Такой бэкап защищает миры, конфиги, SQLite и JAR. Внешние MariaDB/PostgreSQL
обычно в него не входят, поэтому SQL-бэкапы нужны отдельно.

### 5.2. Существующие MariaDB плагинов

Для каждой существующей важной базы выполните с машины, которая видит MariaDB:

```bash
mkdir -p "$HOME/db-backups/2026-09-12"
chmod 700 "$HOME/db-backups/2026-09-12"

mariadb-dump \
  --host=DB_HOST \
  --port=DB_PORT \
  --user=DB_USER \
  --password \
  --single-transaction \
  --quick \
  --routines \
  --events \
  --triggers \
  --hex-blob \
  DB_NAME \
  --result-file="$HOME/db-backups/2026-09-12/DB_NAME.sql"

sha256sum "$HOME/db-backups/2026-09-12/DB_NAME.sql" \
  > "$HOME/db-backups/2026-09-12/DB_NAME.sql.sha256"
```

`--password` без значения безопасно запросит пароль интерактивно. Не пишите
пароль прямо в командной строке. Если пользователь базы из Pterodactyl не имеет
прав на routines/events, а плагины не используют хранимые процедуры, повторите
команду без `--routines --events`.

Лучшая проверка копии — создать временную пустую БД и импортировать dump:

```bash
mariadb --host=DB_HOST --port=DB_PORT --user=TEMP_USER --password TEMP_DB \
  < "$HOME/db-backups/2026-09-12/DB_NAME.sql"
```

После проверки временную БД можно удалить через Pterodactyl.

### 5.3. PostgreSQL панели

На VDS панели:

```bash
sudo systemctl start aurum-backup.service
sudo journalctl -u aurum-backup.service -n 100 --no-pager
sudo ls -lah /var/backups/aurum-panel/
```

Сервис проекта создаёт custom-format dump через `pg_dump` и проверяет его
список через `pg_restore --list`. Переходить дальше следует только после
успешного завершения.

## 6. Создать пустую MariaDB для AurumCore

В Pterodactyl откройте Minecraft-сервер → `Databases` → `New Database`.

Рекомендуемое назначение/имя: `aurum_core`. Pterodactyl может добавить к имени
префикс — в JDBC нужно указывать полное фактическое имя из карточки базы.

Запишите в менеджер секретов:

- host;
- port;
- database name;
- username;
- password.

Не подставляйте `127.0.0.1`, если Pterodactyl показал другое имя или адрес:
контейнер Minecraft и MariaDB обычно находятся в разных сетевых пространствах.
На старте база должна быть совершенно пустой.

## 7. Обновить веб-панель

Панель обновляется независимо от Minecraft. Экономическая вкладка останется
скрытой, пока Companion не сообщит об активном Core.

На VDS:

```bash
cd /opt/aurum-panel
sudo -u aurum git status --short
sudo -u aurum git branch --show-current
sudo -u aurum git rev-parse HEAD
```

Если `git status --short` не пуст, остановитесь и разберите локальные изменения.
Не применяйте pull поверх неизвестных правок.

Обновление:

```bash
cd /opt/aurum-panel
sudo -u aurum git pull --ff-only
sudo -u aurum npm ci
sudo -u aurum npm run prisma:generate
sudo -u aurum npm run build
```

Применение миграций панели:

```bash
cd /opt/aurum-panel/apps/api
sudo -u aurum env $(grep -v '^#' /etc/aurum-panel/api.env | grep -v '^$' | xargs) \
  npx prisma migrate deploy
```

Перезапуск и проверка:

```bash
sudo systemctl restart aurum-api
sudo systemctl status aurum-api --no-pager
curl -s http://10.0.0.1:3001/api/health/ready
```

Ожидается `ready: true`, а проверки PostgreSQL и Redis — `ok`. После этого
обновите страницу панели через `Ctrl+Shift+R`. Для уже работающей установки не
нужно менять nginx или env только ради этого релиза.

## 8. Подготовить плагины Minecraft

Используйте актуальные JAR из `outputs`. На 2026-09-14 серверный комплект такой:

- `AurumCore-0.19.0.jar`;
- `AurumCompanion-0.13.4.jar`;
- `AurumAuth-0.1.0.jar`;
- `AurumGuilds-0.5.1.jar`;
- `AddonsNPC-2.4.0.jar`;
- `AurumArena-1.6.1.jar`;
- `AurumSlots-1.6.0.jar`.

Проверьте файлы по `SHA256SUMS.txt`, затем загрузите их в `plugins`.

Контрольные суммы подготовленной сборки:

| JAR | SHA-256 |
| --- | --- |
| `AddonsNPC-2.4.0.jar` | `84D28A269CA17980697F20D6D326B7B3D21E2B8B8C15E0F564370FA6A0E5CD8D` |
| `AurumArena-1.6.1.jar` | `F6E5927D92BC4DC7931C0DD165A8A5B3C0A611AC2A3B8EFF40103A7E0E6E4B8B` |
| `AurumAuth-0.1.0.jar` | `B1BEDC3EAFF9C31BACB01BFF345CC59FFD46C728ABDE6C294F84B574604FDE0D` |
| `AurumCompanion-0.13.4.jar` | `8F4AC9E0DAA9C66FE40C943939E8F00AEB5E09E9D96081F990BBB4A7622339DF` |
| `AurumCore-0.19.0.jar` | `4C5603BE4D95DB4828C35673F7C896CB2BFB589955FD048B8F83BCFE62897655` |
| `AurumGuilds-0.5.1.jar` | `90553AB00E4357A5965CC1F1349719AD1F1CDB0BDA2AA6ACCBF0D22719E66391` |
| `AurumSlots-1.6.0.jar` | `35B67A1CE87A1B821AA6D6F6BDB6B0D7B1550BA306AD8536B1BAC04D212E64DA` |

Перед загрузкой скачайте старые JAR в локальную rollback-папку. После этого
удалите из `plugins` только старые/дублирующиеся JAR, но не папки данных.
Особенно нельзя одновременно оставлять:

- `GladiatorArena-*.jar` и `AurumArena-*.jar`;
- `SimpleSlots-*.jar` и `AurumSlots-*.jar`;
- две версии любого `Aurum*.jar` или `AddonsNPC*.jar`.

VaultUnlocked, EssentialsX, WorldGuard, FAWE, LuckPerms, PlaceholderAPI и прочие
сторонние плагины оставьте на месте.

`AurumUI-0.11.0.jar` — клиентский Fabric-мод. Его устанавливают игроки в папку
`mods`; в серверную папку `plugins` его класть нельзя.

## 9. Первый запуск: безопасный passive

На первом запуске AurumCore должен создать конфиг, но не стать провайдером
денег.

1. Запустите сервер.
2. Дождитесь полной загрузки.
3. Проверьте консоль на ошибки всех семи плагинов.
4. Выполните в консоли Pterodactyl без начального `/`:

```text
plugins
aurum status
```

Ожидается режим `passive`; старый Vault-провайдер ещё действует.

Если у остальных тестеров ещё есть старые деньги, именно сейчас обнулите их
командой старого провайдера:

```text
eco set PLAYER 0
```

Проверьте каждого тестера, затем полностью остановите сервер. Не используйте
`aeco` на этом passive-этапе.

### Существующая казна Guilds

После включения Core новая версия Guilds может перенести ненулевой старый
`bank_balance` в Core. Если старые деньги гильдий тестовые и тоже должны
исчезнуть, проверьте их до active-запуска. Имя таблицы зависит от префикса в
конфиге Guilds; при стандартном имени read-only запрос выглядит так:

```sql
SELECT id, name, bank_balance
FROM aurum_guilds
WHERE bank_balance <> 0;
```

Не исправляйте боевые данные прямыми SQL-запросами без отдельной копии. На
тестовом сервере безопаснее штатно удалить тестовую гильдию/обнулить её банк до
cutover либо затем пересоздать её.

## 10. Настроить AurumCore

Флаг, о котором был вопрос, находится здесь:

`plugins/AurumCore/config.yml`

Нужно отредактировать созданный полный конфиг, а не заменить его коротким
фрагментом. Для первого active-запуска должны быть такие значения:

```yaml
language: en

economy:
  mode: active
  primary-currency: coins
  currencies:
    coins:
      display-name: Coins
      symbol: "$"
      scale: 2
      enabled: true

active:
  require-verified-migration: false
  global-refresh-ticks: 100

database:
  enabled: true
  jdbc-url: "jdbc:mariadb://DB_HOST:DB_PORT/ACTUAL_DATABASE_NAME"
  username: "ACTUAL_DATABASE_USER"
  password: "ACTUAL_DATABASE_PASSWORD"
  pool-size: 3

financial-policies:
  enabled: false

exchange:
  enabled: false

trading:
  enabled: false
```

`active.require-verified-migration: false` разрешён только для этого одного
осознанного перехода на пустой ledger. Core дополнительно откажется принимать
неподтверждённую базу, если в ней уже есть ненулевые балансы игроков.

`en` — проектный язык по умолчанию. При желании здесь можно поставить `ru` или
`pl`; на схему и экономические операции это не влияет.

Налоги, обмен валют и безопасные трейды пока оставлены выключенными: сначала
проверяется фундамент экономики, затем функции включаются по одной.

## 11. Настроить связанные плагины

### Companion

Если Companion уже работал с панелью, сохраните его текущий конфиг. Проверьте
`plugins/AurumCompanion/config.yml`:

```yaml
token: "LONG_RANDOM_SECRET_ALREADY_SET_IN_PANEL"

http:
  bind: "0.0.0.0"
  port: 8085

panel:
  base-url: "http://10.0.0.1:3001"
  server-id: "UUID_FROM_PANEL_URL"
```

Порт `http.port` должен совпасть с secondary allocation Minecraft-сервера.
Тот же токен и адрес Companion вида `http://10.0.0.2:8085` должны быть в
настройках Minecraft-модуля панели. Порт Companion нельзя подставлять в
`panel.base-url`: там нужен API панели, обычно `3001`.

### Arena

Для денежного режима и минимальной ставки 10:

```yaml
economy:
  use_vault: true
  min_vault_bet: 10.0

settings:
  min_bet: 10.0
```

Историческое имя `use_vault` сохранено в конфиге, но новая Arena проводит
ставки через native API AurumCore. Если Core недоступен, ставки блокируются и
не переходят молча на золотые предметы.

### Slots

В `plugins/AurumSlots/config.yml`:

```yaml
use_vault: true
```

Здесь имя тоже историческое: денежный режим требует активный AurumCore.

### Auth, Guilds и NPC

- существующие настройки БД AurumAuth и AurumGuilds оставьте как есть;
- AddonsNPC имеет обязательную зависимость от AurumCore и отдельный Vault
  toggle ему не нужен;
- тестовые NPC и арены пока не удаляйте из файлов вручную.

## 12. Второй запуск: первый active cutover

Запустите Minecraft-сервер, но пока не разрешайте вход обычным игрокам.

Нормальные признаки в логе:

- схема/миграции новой MariaDB Core успешно готовы;
- предупреждение, что active запущен с
  `active.require-verified-migration=false`;
- сообщение, что AurumCore зарегистрирован как Vault provider с наивысшим
  приоритетом;
- Companion, Guilds, Arena, Slots и NPC включились без stack trace.

Немедленно остановите сервер и не продолжайте, если есть:

- `Access denied` или `Communications link failure` к MariaDB;
- ошибка про required verified migration;
- ошибка, что unverified ledger не пуст;
- отключение AurumCore или зависимых плагинов;
- две одновременно загруженные версии одного плагина.

Проверки из консоли:

```text
plugins
aurum status
abal PLAYER
atreasury
```

Сделайте маленький обратимый тест:

```text
aeco give PLAYER 10 currency:coins staging-test
abal PLAYER
aeco take PLAYER 10 currency:coins staging-test-return
aeco set PLAYER 0 currency:coins staging-reset
```

Если PlaceholderAPI установлен, проверьте Vault-мост:

```text
papi parse PLAYER %vault_eco_balance%
```

Он должен показать тот же баланс, что `abal`. Затем полностью остановите
сервер.

## 13. Закрыть одноразовый cutover-флаг

В `plugins/AurumCore/config.yml` поменяйте только:

```yaml
active:
  require-verified-migration: true
```

Запустите сервер третий раз. Core должен стартовать в active, потому что запись
о завершённом fresh cutover уже сохранена в новой базе. Повторите:

```text
aurum status
abal PLAYER
atreasury
papi parse PLAYER %vault_eco_balance%
```

После этого проверьте:

```text
slots reload
slots status
```

Создайте одну минимальную тестовую ставку Arena и одну операцию NPC/Slots, затем
убедитесь, что баланс Core, Vault placeholder и данные панели совпадают.

## 14. Очистить тестовый контент

Только после успешной загрузки новых версий удалите ненужные сущности штатными
командами плагинов:

```text
npc delete NPC_ID
npc cleanup orphans
arena delete ARENA_ID
```

Не используйте `/kill`, ручное удаление UUID из YAML или удаление ArmorStand/
Display по широкому радиусу: так легко оставить вторую половину составной NPC
или затронуть голограммы ставок.

Если существующая тестовая конфигурация не нужна, после штатного удаления можно
создать NPC и арену заново уже на чистых настройках.

## 15. Проверить панель после Core

1. Откройте страницу Minecraft-сервера.
2. Обновите список/состояние совместимых плагинов.
3. Убедитесь, что AurumCore распознан и появилась отдельная вкладка Economy.
4. Проверьте общий баланс экономики и страницу богатейших.
5. Откройте карточку онлайн- и офлайн-игрока.
6. Под учётной записью admin/GM проверьте set/add/take с тестовой суммой и
   верните баланс к нулю.
7. Под moderator убедитесь, что изменение денег недоступно.

Если вкладка не появилась, сначала проверяйте `aurum status`, затем Companion
token/server-id/port и сетевую доступность `10.0.0.2:8085`; не включайте вкладку
в обход проверки наличия Core.

## 16. После успешного перехода

1. Снова остановите Minecraft и создайте новый baseline backup его файлов.
2. Сделайте первый dump новой базы Core и сохраните SHA-256.
3. Запишите версии семи JAR и commit панели.
4. Запустите сервер и только тогда откройте его тестерам.
5. Используйте `aeco`, `abal`, `apay`/`pay` и панель. Не используйте
   Essentials `/eco` как административный источник денег.
6. Налоги, вторую валюту, обмен и трейды включайте отдельными этапами после
   проверки базового ledger.

## 17. Откат

### Если Core не успел успешно стать active

1. Остановите сервер.
2. Уберите новые JAR.
3. Верните сохранённые старые JAR и при необходимости файловый backup.
4. Не трогайте старые базы Auth/Guilds/WorldGuard.
5. Новую пустую Core DB сохраните для диагностики или удалите после выяснения
   причины.

Панель можно оставить обновлённой: без активного Core вкладка Economy должна
быть скрыта.

### Если active уже выполнил тестовые операции

1. Остановите сервер и никого не пускайте.
2. Сохраните проблемную Core DB для диагностики.
3. Восстановите файловый backup Minecraft и, если Guilds уже переносил банк,
   соответствующий dump Guilds.
4. Верните прежний набор JAR.
5. Запустите сервер и проверьте старый провайдер. Его тестовые балансы останутся
   обнулёнными — это ожидаемый результат данного сценария.

### Откат панели

Используйте записанный до обновления commit. Перед восстановлением PostgreSQL
остановите API. Восстановление дампа перезаписывает данные и выполняется только
при реальной несовместимости миграции, а не как первая попытка исправления.

## 18. Что сообщить разработчику при ошибке

Не присылайте пароли или полный конфиг с секретами. Нужны:

- этап и номер запуска;
- версии Java/Paper и список загруженных плагинов;
- `aurum status`;
- stack trace целиком, включая первое `Caused by`;
- результат health endpoint панели;
- появился ли `active_cutover` и пуст ли был ledger;
- какие команды проверки уже выполнены;
- конфиги только с замазанными password/token.
