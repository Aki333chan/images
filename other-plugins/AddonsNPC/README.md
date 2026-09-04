# AddonsNPC

Интерактивные NPC для **Paper и Spigot 26.2** без обязательных ProtocolLib и Citizens. NPC может быть
человекоподобным `Mannequin` со скином игрока либо любой подходящей живой сущностью Minecraft:
жителем, животным или мобом.

## Возможности

- создание, удаление, перемещение и настройка NPC командами;
- сохранение и восстановление NPC после рестарта сервера;
- цветные непросвечивающие сквозь стены неймтеги, описание и отдельный радиус их видимости;
- отдельный радиус видимости модели и режим поворота `HEAD`/`BODY` к ближайшему игроку;
- выбор типа NPC из живых сущностей Minecraft;
- скин по имени Minecraft-игрока или прямой ссылке на PNG-текстуру для `Mannequin`;
- правый, левый или любой клик;
- реплики в режимах `ALL`, `RANDOM` и `SEQUENTIAL`;
- цепочки действий: сообщение, команда от консоли/игрока, магазин, звук и title;
- GUI-магазины с оплатой через Vault;
- временные и постоянные скидки на весь магазин либо отдельный слот с заметным оформлением GUI;
- GUI-скупщики, которые принимают предметы игроков и начисляют валюту через Vault;
- поштучная и оптовая продажа, включая продажу всего подходящего инвентаря одним кликом;
- сопоставление товара только по материалу либо точное — с именем, lore, чарами и custom data;
- временные и постоянные бонусы выплат скупщика на весь ассортимент либо отдельный слот;
- отдельные GUI-торговцы бонусами AurumGuilds с оплатой через Vault;
- временные и постоянные усиления гильдии с проверкой ранга покупателя;
- конечный склад либо бесконечный запас товара (`amount <= 0`);
- проверка баланса и места в инвентаре, возврат денег при ошибке выдачи;
- индивидуальные permissions и cooldown для каждого NPC;
- защита от урона, огня, мобов, толкания и удаления;
- точечная совместимость с WorldGuard `mob-spawning: deny` для административно созданных NPC;
- редактируемые `config.yml`, `npcs.yml`, `shops.yml`, `buyers.yml` и `guild-traders.yml` с горячей перезагрузкой.

## Требования

- Paper или Spigot 26.2;
- Java 25+;
- Vault и любой совместимый economy-плагин — для платных предложений и скупщиков.
- AurumGuilds — только для гильдейских торговцев; без него остальные NPC продолжают работать.
- WorldGuard необязателен; при его наличии NPC могут игнорировать региональные spawn-флаги.

Без Vault информаторы, команды и бесплатные магазины продолжают работать.

## Сборка и установка

```bash
mvn clean package
```

Скопируйте `target/AddonsNPC-1.5.3.jar` в `plugins/`, перезапустите сервер и
выполните `/npc help`. Для сборки нужен JDK 25.

## Быстрый пример

```text
/npc create guide &6Проводник
/npc description guide &7Нажмите, чтобы узнать дорогу
/npc skin guide player Notch
/npc message add guide &eПривет, {player}!
/npc message add guide &7Город находится за северными воротами.
/npc message mode guide sequential
/npc set guide cooldown 2
/npc set guide visibility 48
/npc set guide namerange 24
/npc set guide lookmode head
/npc rotation guide set
/npc equipment guide right COMPASS
/npc equipment guide left hand
```

NPC-животное создаётся сменой типа:

```text
/npc create guard_dog &6Сторожевой пёс
/npc type guard_dog WOLF
/npc message add guard_dog &7Пёс внимательно смотрит на вас.
```

Торговец:

```text
/npc shop create supplies &8Припасы
/npc shop offer supplies 11 5 BREAD 0 &eСвежий хлеб
/npc shop quantity supplies 11 8
/npc create baker &6Пекарь
/npc action add baker shop supplies
/npc shop discount supplies all 15 2h
/npc shop discount supplies 11 25 permanent
/npc shop offer supplies 13 25 SPLASH_POTION:STRONG_HEALING 0 &dСильное лечение
```

В товаре после двоеточия указывается вариант зелья: например `POTION:HEALING`,
`SPLASH_POTION:STRONG_HEALING`, `LINGERING_POTION:LONG_SWIFTNESS` или
`TIPPED_ARROW:POISON`. Вместо значения можно написать `hand`: магазин сохранит
предмет из основной руки целиком, включая тип зелья, название, эффекты и другие метаданные.

Скупщик с поштучной и оптовой ценой:

```text
/npc buyer create farmer &8Скупщик урожая
/npc buyer offer farmer 11 1.5 WHEAT 64 110 material
/npc create farm_buyer &6Фермер
/npc action add farm_buyer buyer farmer
/npc buyer bonus farmer all 10 1d
/npc buyer bonus farmer 11 25 permanent
```

Гильдейский торговец усилениями:

```text
/npc guildtrader create boosts &8Усиления гильдии
/npc guildtrader offer boosts 10 mining_speed 2 7d 25000 &6Шахтёрский порыв
/npc guildtrader offer boosts 12 block_drops 1.5 7d 40000 &bБогатая жила
/npc guildtrader offer boosts 14 experience 2 30d 75000 &dМудрость гильдии
/npc guildtrader rank boosts officer
/npc create guild_master &6Мастер гильдий
/npc action add guild_master guildtrader boosts
```

Тематические иконки назначаются автоматически: кирка для скорости добычи, кроличья лапка для
передвижения, алмазная руда для дропа блоков, зачарованная книга для мобов и бутылочка опыта
для опыта. Любую иконку можно заменить командой `guildtrader icon`.

В меню скупщика игрок использует:

- ЛКМ — продать одну штуку;
- Shift+ЛКМ — продать все подходящие предметы поштучно;
- ПКМ — продать одну полную оптовую партию;
- Shift+ПКМ — продать все полные оптовые партии. Неполный остаток остаётся у игрока.

Для предмета с точным набором свойств возьмите образец в основную руку:

```text
/npc buyer offer collector 13 250 hand off
```

Для `hand` режим `exact` включается автоматически. Команда
`/npc buyer match collector 13 material|exact` меняет режим; при переключении на `exact`
новый образец также берётся из основной руки.

Командный NPC:

```text
/npc create teleporter &bТелепорт
/npc action add teleporter message &7Телепортирую...
/npc action add teleporter console warp spawn {player}
/npc action add teleporter sound entity.enderman.teleport|1|1
```

## Несколько NPC и общие магазины

Можно создать любое количество гидов с разными ID, репликами, действиями, скинами и радиусами.
Так же можно создать несколько торговцев, привязать их к одному общему магазину либо каждому
назначить собственный `shop-id`. Одному NPC рекомендуется назначать одно действие `shop`:
несколько действий открытия GUI выполнятся подряд, поэтому игрок увидит последнее из них.
Для большого торговца используйте один магазин на 54 слота либо отдельных NPC по категориям.

## Команды

| Команда | Назначение |
|---|---|
| `/npc create <id> [name]` | Создать NPC в позиции игрока |
| `/npc delete\|move\|tp\|enable\|disable <id>` | Управление NPC |
| `/npc cleanup <id>` | Удалить загруженную осиротевшую сущность старого NPC по её внутреннему ID |
| `/npc cleanup orphans` | Удалить все загруженные сущности NPC, которых уже нет в `npcs.yml` |
| `/npc name <id> <name>` | Изменить неймтег |
| `/npc description <id> <text\|clear>` | Изменить вторую строку имени |
| `/npc type <id> <entity-type>` | Выбрать Mannequin, жителя, животное или моба |
| `/npc skin <id> player <name>` | Скин зарегистрированного игрока |
| `/npc skin <id> url <url>` | Скин по URL |
| `/npc skin <id> clear` | Сбросить скин |
| `/npc equipment <id> <right\|left> <material\|hand\|clear>` | Предмет в правой (основной) или левой (дополнительной) руке NPC |
| `/npc rotation <id> set` | Сохранить направление взгляда администратора как базовый поворот NPC |
| `/npc rotation <id> reset` | Вернуть тело и голову к сохранённому базовому повороту |
| `/npc message add <id> <text>` | Добавить реплику |
| `/npc message remove <id> <index>` | Удалить реплику |
| `/npc message mode <id> <all\|random\|sequential>` | Режим диалога |
| `/npc action add <id> <type> <value>` | Добавить действие |
| `/npc set <id> visibility <range>` | Радиус видимости модели NPC, `0` скрывает её |
| `/npc set <id> namerange <range>` | Радиус видимости имени и описания |
| `/npc set <id> lookmode <head\|body>` | Поворачивать только голову либо всю сущность |
| `/npc shop create <id> [title]` | Создать магазин |
| `/npc shop offer <shop> <slot> <price> <material[:potion-type]\|hand> <amount> [name]` | Добавить обычный предмет, конкретное зелье или предмет из руки; `amount <= 0` означает бесконечный запас |
| `/npc shop quantity <shop> <slot> <quantity>` | Количество предметов за одну покупку |
| `/npc shop discount <shop> <all\|slot> <percent\|off> [duration]` | Скидка на магазин или слот |
| `/npc shop remove <shop> <slot>` | Удалить товар |
| `/npc buyer create <id> [title]` | Создать меню скупщика |
| `/npc buyer offer <buyer> <slot> <unit-price> <material\|hand> [bulk-amount] [bulk-price] [material\|exact]` | Добавить скупаемый товар |
| `/npc buyer bulk <buyer> <slot> <amount\|off> [price]` | Настроить или отключить оптовую цену |
| `/npc buyer bonus <buyer> <all\|slot> <percent\|off> [duration]` | Бонус выплаты на ассортимент или слот |
| `/npc buyer price <buyer> <slot> <unit-price>` | Изменить поштучную цену |
| `/npc buyer match <buyer> <slot> <material\|exact>` | Выбрать правила сопоставления предмета |
| `/npc buyer name <buyer> <slot> <name>` | Изменить название предложения |
| `/npc buyer lore <buyer> <slot> <add\|remove\|clear\|list> ...` | Настроить описание предложения |
| `/npc buyer command <buyer> <slot> <add\|remove\|clear\|list> ...` | Настроить команды после продажи |
| `/npc buyer permission <buyer> <slot> <permission\|none>` | Ограничить предложение permission-узлом |
| `/npc buyer title\|size <buyer> ...` | Изменить заголовок или размер GUI |
| `/npc buyer open\|delete\|remove ...` | Открыть меню, удалить скупщика или предложение |
| `/npc guildtrader create <id> [title]` | Создать меню гильдейских усилений |
| `/npc guildtrader offer <trader> <slot> <type> <magnitude> <duration> <price> [name]` | Добавить бонус |
| `/npc guildtrader rank <trader> <member\|officer\|leader>` | Минимальный ранг покупателя |
| `/npc guildtrader icon <trader> <slot> <material\|auto>` | Настроить тематическую иконку |
| `/npc guildtrader price\|name\|lore\|permission <trader> <slot> ...` | Изменить предложение |
| `/npc guildtrader title\|size\|open\|list\|delete\|remove ...` | Управление меню торговца |
| `/npc info <id>` / `/npc list` | Просмотр состояния |
| `/npc reload` | Перечитать конфиги |

Все административные команды требуют `addonsnpc.admin` (по умолчанию OP).
Если команда `shop offer`, `buyer offer` или `guildtrader offer` указывает уже занятый слот, первая попытка только
покажет предупреждение. Повторите ту же команду в течение 30 секунд для подтверждения замены.

Формат времени акций: `30m`, `2h`, `7d`, `1w` или `permanent`. Значение `off` отключает
скидку/бонус. Акция отдельного слота имеет приоритет над общей акцией.

## Действия

Действия исполняются по порядку. Поддерживаются:

- `message:<text>` — сообщение игроку;
- `console:<command>` — команда от консоли;
- `player:<command>` — команда от игрока;
- `shop:<shop-id>` — открыть магазин;
- `buyer:<buyer-id>` — открыть скупщика;
- `guildtrader:<trader-id>` — открыть торговца бонусами AurumGuilds;
- `sound:<sound>|<volume>|<pitch>` — воспроизвести звук;
- `title:<title>|<subtitle>` — показать title.

Плейсхолдеры: `{player}`, `{npc}`, `{balance}`; в магазинах также `{price}`, `{base_price}`,
`{discount_percent}`, `{discount_remaining}`, `{item}`, `{amount}` и `{stock}`. У команд
скупщика доступны `{player}`, `{balance}`, `{item}`, `{amount}`, `{price}`, `{base_price}`,
`{unit_price}`, `{base_unit_price}`, `{bulk_amount}`, `{bulk_price}`, `{bonus_percent}` и
`{bonus_remaining}`.

В lore гильдейского торговца доступны `{guild}`, `{guild_tag}`, `{bonus}`, `{bonus_type}`,
`{bonus_value}`, `{magnitude}`, `{duration}`, `{price}`, `{current_value}` и
`{current_remaining}`.

Tab Completion контекстный: он предлагает существ, игроков онлайн, режимы диалогов, типы действий,
магазины, скупщиков и гильдейских торговцев, слоты, материалы, цены, скидки, бонусы, сроки акций, оптовые партии,
режимы сопоставления, радиусы, permissions и позы.

Расширенный формат YAML, включая lore, разрешения и дополнительные команды после покупки,
описан в [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Permissions

- `addonsnpc.admin` — управление NPC, магазинами, скупщиками и гильдейскими торговцами;
- `addonsnpc.use` — взаимодействие с NPC;
- `addonsnpc.shop` — покупки в магазинах;
- `addonsnpc.buyer` — продажа предметов скупщикам;
- `addonsnpc.guildtrader` — покупка усилений для своей гильдии;
- произвольные permissions можно назначать каждому NPC и предложению.
