# AurumArena 1.5.0

Version 1.4.0 adds the typed AurumUI provider: the mod lists configured arenas
and edits the selected arena's final prize pool and other safe settings without
manual IDs or arbitrary command forwarding.

Version 1.3.1 allows registered arena controls to work inside WorldGuard
regions even when ordinary block interaction is denied. Only configured arena
buttons and hoppers bypass the cancelled interaction, and players must have
`arena.use`; unrelated protected blocks remain untouched.

Version 1.3.0 adds optional AurumUI support through AurumCompanion. Players
without the Fabric client keep the normal arena sidebar; clients that complete
the AurumUI handshake receive the same live values as a compact right-edge
panel and retain unrelated scoreboards such as TAB.

Плагин гладиаторских арен со ставками для Paper/Spigot 26.2 и Java 25.

## Язык

В `plugins/AurumArena/config.yml` задайте `language: en`, `language: pl` или
`language: ru` и выполните `/arena reload`. Английский выбран по умолчанию.
Редактируемые файлы находятся в `plugins/AurumArena/locales/`. Локализация применяется
к сообщениям и справке команд, GUI, sidebar, bossbar, title и голограммам. Если в
пользовательском locale-файле отсутствует новый ключ, используется встроенное значение
и английский fallback.

## Обновление с 1.01/1.05

Замените JAR при выключенном сервере и сохраните папку `plugins/AurumArena` как резервную копию. Старые секции `arenas` в `config.yml` и комплекты из `kits.yml` читаются без пересоздания. Новые параметры автоматически дописываются из стандартного конфига.

## Сайдбар и голограммы

На Paper 26.2 числовые значения строк справа от сайдбара скрываются по умолчанию. Это настраивается через `settings.sidebar.hide_scores`. Spigot 26.2 не предоставляет API пустого формата score, поэтому на чистом Spigot номера остаются видимыми; остальной функционал сохраняется.

Все голограммы AurumArena по умолчанию перекрываются стенами и отображаются на расстоянии до 32 блоков. Параметры `settings.holograms.see_through_walls`, `view_distance_blocks` и `betting_scale` применяются после `/arena reload`; голограммы над ставками и финальной статистикой пересоздаются с новыми значениями.

## MariaDB из Pterodactyl

В `plugins/AurumArena/config.yml`:

```yaml
database:
  type: mariadb
  mariadb:
    jdbc_url: ""
    host: 127.0.0.1
    port: 3306
    database: gladiatorarena
    username: gladiatorarena
    password: "сложный_пароль"
    use_ssl: false
    pool_size: 5
    connection_timeout_ms: 10000
```

Возьмите `host`, `port`, имя базы, пользователя и пароль на вкладке Databases панели Pterodactyl. Если панель выдаёт готовый нестандартный JDBC URL, внесите его в `jdbc_url`; тогда отдельные `host`, `port`, `database` и `use_ssl` не используются. Плагин сам создаёт таблицу `gladiator_stats`.

При ошибке MariaDB статистика временно отключается и причина пишется в консоль. Плагин намеренно не переключается молча на SQLite: иначе два источника статистики разойдутся. Матчи, ставки, резервные копии инвентарей и ожидающие выплаты при этом остаются защищены локальным `recovery.yml`.

Для автономного режима:

```yaml
database:
  type: sqlite
  sqlite:
    file: statistics.db
```

## Экономика

Деньги идут через **AurumCore**, а не через Vault. Прямой зависимости от Vault в плагине больше нет.

`economy.use_vault: true` включает денежный режим. Ключ назван так по историческим причинам: переименование сломало бы существующие конфиги. Если AurumCore недоступен или не в активном режиме, ставки блокируются — молчаливого перехода на золото нет, иначе правила игры менялись бы за спиной у игроков.

При `use_vault: false` используются `main_currency` и `sub_currency` с отношением 1:10; AurumCore для этого режима не нужен.

### Где лежат деньги

У арены два счёта, и это разные деньги:

- `ARENA_ESCROW:bet:<арена>` — касса ставок текущего боя;
- `ARENA_ESCROW:final:<арена>` — чемпионский пул, собранный взносами игроков.

Возврат ставок при ничьей не может залезть в призовой пул, и наоборот.

### Комиссия

| Ключ | По умолчанию | Что делает |
| --- | --- | --- |
| `economy.commission.percent` | `settings.casino_commission_percent` | Процент казино со ставок |
| `economy.commission.destination` | `treasury` | `treasury` — в казну сервера; `champion-pool` — в чемпионский пул этой же арены |
| `economy.commission.apply-to-champion-pool` | `false` | Брать ли комиссию ещё и с чемпионского пула |

`treasury` — доход сервера: деньги уходят из оборота арены. `champion-pool` — перераспределение: комиссия с обычных боёв копится и достаётся победителям финала, сервер на ней не зарабатывает.

Комиссия **со ставок берётся всегда**, в том числе в финальном бою: там идёт обычный тотализатор поверх пула. Комиссия **с самого пула** по умолчанию не берётся — пул собран взносами игроков именно на приз.

Настраивается вживую, без правки файла и перезапуска:

```text
/arena commission                                # показать текущее
/arena commission treasury | champion-pool       # получатель
/arena commission percent <0..90>                # ставка
/arena commission champion-pool-rake <on|off>    # рейк с пула
```

Те же параметры доступны из AurumUI. Обе точки входа пишут в `config.yml` и перечитывают настройки, поэтому изменение переживает перезапуск.

> **Не заводите policy-правило `COMMISSION` на категорию `ARENA_PAYOUT`.** Рейк арены считается здесь, и правило удержало бы с победителя второй раз. Policy engine остаётся для налогов и сборов поверх.

### Надёжность

Каждое списание идёт через hold: деньги сначала резервируются, и только после подтверждённой фиксации ставка учитывается. Незавершённые списания лежат в `transactions.yml` и разбираются при старте по состоянию резерва в Core — зависший резерв снимается, зафиксированный возвращается игроку.

Выплаты и возвраты идут со стабильными ключами идемпотентности, построенными из круга ставок. Если ответ от Core не пришёл, сумма ложится в очередь вместе с тем же ключом и повторяется при входе игрока и при следующем старте: уже проведённая операция вторично не заплатит.

### Обновление с 1.4.0

В `recovery.yml` могли остаться денежные выплаты старого формата. **Автоматически они не выдаются**: за такой записью нет ни счёта, ни проводки — в эпоху Vault деньги просто появлялись на балансе. Выдать их сейчас значило бы создать валюту мимо ledger. При старте плагин перечисляет такие долги в логе поимённо; рассчитайтесь через `/aurum economy give`, чтобы операция попала в аудит, и удалите записи.

## Основные команды

- `/arena create <имя>`, `/arena delete <имя>`, `/arena status [имя]`, `/arena validate [имя]`
- `/arena gui [имя]`, `/arena start`, `/arena stop`, `/arena reload`
- `/arena setred`, `setblue`, `sethost`, `setreset`, `sethopred`, `sethopblue`, `setfhop`, `bankomat`
- `/arena setspawn`, `spawnred1`, `spawnred2`, `spawnblue1`, `spawnblue2`
- `/arena auto`, `manual`, `betting <true|false>`, `kit <true|false>`, `friendlyfire <true|false>`
- `/arena winxp <0..1000000>`, `/arena finalxp <0..1000000>`, `/arena xpmode <points|levels>`
- `/arena maxplayers <число>`, `/arena radius <число>`, `/arena showbar <all|spectators|false>`
- `/arena odds [арена]`, `/arena stats [игрок]`
- `/arena spectate <арена>`, `/arena leave`
- `/arena final`, `finalstats`, `fstatsremove`, `fstatsscale <0.1..5>`
- `/arena debug hologram` удаляет ближайшую голограмму именно AurumArena.
- `/arena recover [игрок]` вручную восстанавливает сохранённый инвентарь игрока, если он уже не участвует в арене.
- `/arena commission [...]` показывает и меняет настройки комиссии — см. раздел «Экономика».

Все команды имеют контекстное автодополнение. Административные команды требуют `arena.admin`, наблюдение — `arena.spectate`, обычное использование — `arena.use`.

`winxp` задаёт награду каждому победителю обычного боя, `finalxp` — каждому победителю финала. Режим `points` добавляет обычные очки в полосу опыта, `levels` — целые уровни. Значение `0` отключает соответствующую награду. Команды сохраняют параметры конкретной арены в `arenas.<id>.winnerExperience`, `finalWinnerExperience` и `experienceMode`; секция `settings.rewards` задаёт значения для новых арен. Если победитель недоступен в момент выплаты, опыт сохраняется в `recovery.yml` и выдаётся при следующем входе.

После каждой ставки чат показывает выбранную команду, размер текущего взноса и общую сумму ставки игрока. В sidebar появляется персональная строка `Ваша`, а голограммы над воронками сразу обновляют общий банк и коэффициент.

## Комплекты и восстановление

Перед выдачей комплекта сохраняются обычный инвентарь, броня, вторая рука, эффекты, здоровье, голод, огонь, уровень и полоса опыта. При выходе кнопкой или `/arena leave`, техническом поражении, смерти с последующим респавном, штатном окончании, остановке матча, перезагрузке плагина и следующем входе после сбоя используется журнал `recovery.yml`. С версии 1.1.4 свежий снимок дополнительно хранится в памяти: обычный выход восстанавливает неизменяемую копию напрямую, а файл остаётся аварийным вариантом после перезапуска.

`/arena finalstats` добавляет одну финальную голограмму в текущем месте. На одной арене можно создать несколько копий в разных местах; повторное добавление в том же блоке не создаёт новую сущность и очищает физические дубликаты. `/arena fstatsremove` удаляет ближайшую настроенную копию в радиусе 5 блоков, `/arena fstatsremove all` — все копии, а `/arena fstatsscale <0.1..5>` меняет масштаб ближайшей. При первом запуске 1.1.2 повреждённые записи и записи одного блока автоматически очищаются, а голограммы из разных частей арены сохраняются.

## Наблюдатели

Перед входом сохраняются координаты, GameMode и параметры полёта. `/arena leave`, выход с сервера, смена GameMode, телепортация или выход за сферическую границу арены завершают наблюдение и возвращают исходное состояние. Незавершённая сессия восстанавливается после аварийного рестарта.

## PlaceholderAPI

- `%aurumarena_arena%`, `%aurumarena_state%`
- `%aurumarena_red_players%`, `%aurumarena_blue_players%`
- `%aurumarena_red_bets%`, `%aurumarena_blue_bets%`, `%aurumarena_total_bets%`
- `%aurumarena_wins%`, `%aurumarena_losses%`, `%aurumarena_streak%`, `%aurumarena_best_streak%`, `%aurumarena_earnings%`

PlaceholderAPI опционален. SQLite, MariaDB Connector/J и HikariCP уже включены в JAR.

## Переименование в AurumArena

Остановите сервер и удалите старый JAR GladiatorArena из папки plugins, затем установите новый JAR AurumArena. Одновременная загрузка старого и нового плагинов блокируется.

Если папка `plugins/AurumArena` ещё не существует, содержимое `plugins/GladiatorArena` автоматически копируется в неё целиком. Исходная папка остаётся резервной копией. Если новая папка уже существует, используется только она: объединения и перезаписи данных нет. Не создавайте пустую новую папку перед первым запуском, если нужен автоимпорт. При ошибке копирования плагин отключается без запуска с пустыми данными.

Команды, права и идентификаторы сохранённых голограмм не изменены. Внутренние Java-пакеты сохранены для совместимости. Пользовательские сообщения в перенесённом конфиге не заменяются автоматически. Версия и игровой функционал при переименовании не менялись.

Основной префикс PlaceholderAPI — `%aurumarena_*%`; старый `%gladiatorarena_*%` продолжает работать. Таблица `gladiator_stats` и существующие реквизиты MariaDB не переименовываются: накопленная статистика остаётся на месте. Команда `/aurumarena` — алиас `/arena`.
