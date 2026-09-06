# Конфигурация

## `npcs.yml`

```yaml
npcs:
  mayor:
    name: "&6Мэр"
    description: "&7Глава города"
    enabled: true
    entity-type: MANNEQUIN # либо VILLAGER, COW, WOLF, ZOMBIE и т. п.
    location:
      world: world
      x: 0.5
      y: 64.0
      z: 0.5
      yaw: 180.0
      pitch: 0.0
    skin:
      type: PLAYER # NONE, PLAYER или URL
      value: Notch
    click-mode: RIGHT # RIGHT, LEFT или BOTH
    dialogue:
      mode: SEQUENTIAL # ALL, RANDOM или SEQUENTIAL
      lines:
        - "&eЗдравствуйте, {player}!"
        - "&7На площади сегодня праздник."
    actions:
      - "sound:entity.villager.yes|1|1"
      - "message:&7Ваш баланс: &e{balance}"
    cooldown-seconds: 1.5
    permission: ""
    look-at:
      enabled: true
      range: 8.0
      mode: HEAD # HEAD или BODY
    visibility:
      entity-range: 48.0
      name-range: 24.0
    pose: STANDING
```

ID состоит из латинских строчных букв, цифр, `_` и `-`, максимум 32 символа.
Изменения можно внести вручную и применить командой `/npc reload`.

Значения по умолчанию для новых NPC задаются в `config.yml`: `default-visibility-range`,
`default-name-visibility-range` и `default-look-mode`.

`entity-range` и `name-range` задаются в блоках и работают независимо; `0` полностью скрывает
соответствующую часть. Имя и описание создаются как `TextDisplay` с отключённым `see-through`,
поэтому не видны сквозь стены. `look-at.mode: HEAD` оставляет корпус в исходном направлении
и использует независимый контроллер головы у поддерживающих его типов; `BODY` поворачивает
всю сущность. Команды: `/npc set <id> visibility <range>`, `namerange`, `lookmode head|body`.

При `settings.ignore-worldguard-spawn-flags: true` плагин повторно разрешает только свои
помеченные спавны с причиной `CUSTOM`, если на сервере загружен WorldGuard. Обычный спавн
мобов и сущности других плагинов это не затрагивает.

## `shops.yml`

```yaml
shops:
  supplies:
    title: "&8Припасы"
    size: 27 # 9, 18, 27, 36, 45 или 54
    discount:
      percent: 15.0
      expires-at: 0 # Unix-время в миллисекундах; 0 = до ручного отключения
    offers:
      11:
        icon: BREAD
        display-name: "&eСвежий хлеб"
        lore:
          - "&7Получить: &f8 хлеба"
          - "&6Цена: &e{price}"
        price: 5.0
        item: BREAD
        amount: -1 # запас; 0 или отрицательное значение = бесконечный
        infinite: true # сохраняется плагином, чтобы отличить бесконечный и исчерпанный склад
        quantity: 8 # сколько предметов выдаётся за одну покупку
        permission: ""
        commands:
          - "say {player} купил хлеб"
        discount:
          percent: 25.0 # активная скидка слота имеет приоритет над общей
          expires-at: 0
```

`slot` начинается с нуля. Положительный `amount` задаёт конечный склад в предметах. Значения
`0` и меньше создают бесконечный запас. Поле `quantity` задаёт размер одной покупки и уменьшает
конечный склад на это число. `price: 0` создаёт бесплатное предложение и не требует Vault.
Список `commands` выполняется консолью после успешной оплаты и выдачи предмета.
Для lore и команд доступны `{price}`, `{base_price}`, `{discount_percent}` и
`{discount_remaining}`; `{price}` всегда содержит реально списываемую сумму.

Команда `/npc shop quantity <shop> <slot> <quantity>` меняет размер покупки без ручного
редактирования YAML.

Скидки удобнее задавать командами — плагин сам рассчитает `expires-at`:

```text
/npc shop discount supplies all 15 2h
/npc shop discount supplies 11 25 permanent
/npc shop discount supplies 11 off
```

Допустимое время: секунды `s`, минуты `m`, часы `h`, дни `d`, недели `w` либо `permanent`.
В GUI активная скидка добавляет блеск и префикс, зачёркнутую базовую цену, итоговую цену,
процент и оставшееся время. Если акция истекла или была изменена при уже открытом меню,
первая попытка покупки только обновит предложение — старая показанная цена не будет списана.

## `buyers.yml`

```yaml
buyers:
  farmer:
    title: "&8Скупщик урожая"
    size: 27
    bonus:
      percent: 10.0
      expires-at: 0
    offers:
      11:
        item: WHEAT
        match: MATERIAL # MATERIAL или EXACT
        display-name: "&eПшеница"
        lore:
          - "&7Поштучно: &e{unit_price}"
          - "&7Стак: &f{bulk_amount} &7за &e{bulk_price}"
        unit-price: 1.5
        bulk-amount: 64
        bulk-price: 110.0
        permission: ""
        commands:
          - "say {player} продал {amount} пшеницы за {price}"
        bonus:
          percent: 25.0 # бонус слота имеет приоритет над общим
          expires-at: 0
```

`unit-price` — выплата за одну штуку. Для оптовой продажи одновременно задаются
`bulk-amount` (размер партии от 2 до 2304) и `bulk-price` (выплата за всю партию).
При продаже всех партий неполный остаток не изымается.

`match: MATERIAL` сравнивает только тип материала и подходит для обычных ресурсов.
`match: EXACT` дополнительно сравнивает имя, lore, чары и прочие метаданные. Точный образец
сохраняется плагином в поле `template`; удобнее всего создать его командой с `hand`, держа
нужный предмет в основной руке.

```text
/npc buyer create farmer &8Скупщик урожая
/npc buyer offer farmer 11 1.5 WHEAT 64 110 material
/npc action add farm_buyer buyer farmer
```

Заголовок и размер GUI меняются через `buyer title` и `buyer size`; поштучная и оптовая
цены — через `buyer price` и `buyer bulk`. Команды `buyer name`, `buyer lore`,
`buyer command`, `buyer match` и `buyer permission` позволяют настроить остальные поля без
ручного редактирования YAML. Контекстное tab completion подсказывает ID, занятые и свободные
слоты, материалы, цены, размеры партий, режимы сопоставления и индексы списков.

Управление продажей в GUI: ЛКМ — одна штука, Shift+ЛКМ — все предметы поштучно,
ПКМ — одна оптовая партия, Shift+ПКМ — все полные оптовые партии. Предметы удаляются только
после полной проверки сделки. Если Vault отклоняет начисление или выбрасывает ошибку,
инвентарь восстанавливается из снимка.

Команды после успешной продажи выполняются от консоли. Доступные плейсхолдеры:
`{player}`, `{balance}`, `{item}`, `{amount}`, `{price}`, `{base_price}`, `{unit_price}`,
`{base_unit_price}`, `{bulk_amount}`, `{bulk_price}`, `{bonus_percent}` и
`{bonus_remaining}`. Продажа требует Vault, economy-провайдер и permission `addonsnpc.buyer`;
отдельное поле `permission` может дополнительно ограничить конкретное предложение.

Бонусы выплат настраиваются аналогично скидкам:

```text
/npc buyer bonus farmer all 10 1d
/npc buyer bonus farmer 11 25 permanent
/npc buyer bonus farmer 11 off
```

GUI показывает обычную и повышенную выплату, процент и оставшееся время. Поштучные и оптовые
операции получают одинаковый процентный бонус. Если срок или размер бонуса изменился после
открытия меню, предложение сначала обновится и потребует повторного клика.

При попытке создать `shop offer`, `buyer offer` или `guildtrader offer` в занятом слоте первая корректная команда
ничего не заменяет. Для подтверждения необходимо повторить ту же команду в течение 30 секунд;
другая команда отменяет ожидающее подтверждение.

## `guild-traders.yml`

Отдельное меню продаёт усиления через сервис `AurumGuildsApi`. Интеграция мягкая: если
AurumGuilds не установлен или ещё не зарегистрировал API, меню честно откажется открываться,
но остальные NPC, магазины и скупщики продолжат работать.

```yaml
guild-traders:
  boosts:
    title: "&8Усиления гильдии"
    size: 27
    required-rank: OFFICER # MEMBER, OFFICER или LEADER
    offers:
      10:
        type: MINING_SPEED
        magnitude: 2.0
        duration-seconds: 604800 # 0 = навсегда
        price: 25000.0
        icon: DIAMOND_PICKAXE
        display-name: "&6Шахтёрский порыв"
        lore:
          - "&7Бонус: &f{bonus_value}"
          - "&7Срок: &f{duration}"
          - "&6Цена: &e{price}"
        permission: ""
```

Поддерживаемые типы соответствуют публичному API AurumGuilds:

- `mining_speed` — целый уровень эффекта «Спешка»;
- `movement_speed` — целый уровень эффекта «Скорость»;
- `block_drops` — множитель добычи из блоков;
- `mob_drops` — множитель добычи с мобов;
- `experience` — множитель получаемого опыта.

Один тип бонуса может быть активен у гильдии только в одном экземпляре. Новая покупка заменяет
прежнюю силу и срок, а не складывается и не продлевает её автоматически. GUI показывает текущее
значение и остаток времени, подсвечивает такой слот и предупреждает о замене. Если бонус изменился
после открытия меню, первый клик только обновляет сведения.

Создание и привязка:

```text
/npc guildtrader create boosts &8Усиления гильдии
/npc guildtrader offer boosts 10 mining_speed 2 7d 25000 &6Шахтёрский порыв
/npc guildtrader offer boosts 12 block_drops 1.5 7d 40000 &bБогатая жила
/npc guildtrader rank boosts officer
/npc create guild_master &6Мастер гильдий
/npc action add guild_master guildtrader boosts
```

Формат времени: `30m`, `2h`, `7d`, `1w` или `permanent`. Для постоянного бонуса в YAML
используется `duration-seconds: 0`. `required-rank: OFFICER` по умолчанию разрешает покупки
лидеру и офицерам; `LEADER` — только лидеру, `MEMBER` — любому участнику.

Дополнительные команды:

```text
/npc guildtrader title <trader> <title>
/npc guildtrader size <trader> <9|18|27|36|45|54>
/npc guildtrader price <trader> <slot> <price>
/npc guildtrader icon <trader> <slot> <material|auto>
/npc guildtrader name <trader> <slot> <display-name>
/npc guildtrader lore <trader> <slot> <add|remove|clear|list> ...
/npc guildtrader permission <trader> <slot> <permission|none>
/npc guildtrader open|remove|delete|list ...
```

Платные предложения требуют Vault. Сначала списывается валюта игрока, затем асинхронно
вызывается `grantBonus`. Если AurumGuilds отказывает (например, гильдия была распущена между
открытием GUI и кликом), плагин возвращает платёж. Пока операция не завершилась, повторная
покупка тем же игроком заблокирована.

Плейсхолдеры lore: `{guild}`, `{guild_tag}`, `{bonus}`, `{bonus_type}`, `{bonus_value}`,
`{magnitude}`, `{duration}`, `{price}`, `{current_value}`, `{current_remaining}`.

### Переход с 1.0

В версии 1.0 поле `amount` означало размер выдаваемого стака. В 1.1 оно означает склад.
Плагин автоматически переносит старые предложения: прежний `amount: 8` станет бесконечным
складом с `quantity: 8`, после чего запишет `schema-version: 2`.

## Скины

Скины применяются только к типу `MANNEQUIN`. `PLAYER` асинхронно получает официальный профиль Mojang по имени. `URL` принимает прямую
HTTP/HTTPS-ссылку на PNG. Для URL используется статический профиль; серверу не требуется
делать запрос к Mojang.

Paper и Spigot 26.2 предоставляют разные сигнатуры API профиля `Mannequin`. Плагин определяет
платформу во время выполнения, поэтому отдельные JAR не нужны. Для внешнего вида животного,
жителя или моба используется `/npc type <id> <entity-type>`; это нативная сущность, а не
текстура, наложенная на модель игрока.

## Цвета

В неймтегах, репликах, title, названиях магазинов и lore используются стандартные цветовые
коды с `&`, например `&6`, `&c` и `&l`.
