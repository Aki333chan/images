# AurumCore 0.5.0: financial policy engine

Policy engine применяется только в `active`-режиме к операциям собственных
плагинов через `AurumEconomyApi`. Несколько подходящих правил объединяются с
исходной оплатой в одну MariaDB-транзакцию: либо проходят все проводки, либо не
проходит ни одна.

## Типы правил

- `TAX`, `FEE`, `COMMISSION`: доля `rate` от 0 до 1. `INCLUDED` удерживает её
  из указанной цены, `ADDED` начисляет поверх цены. Получатель по умолчанию —
  `TREASURY:global`.
- `CASHBACK`: возвращает долю плательщику за счёт `TREASURY:global`.
- `SUBSIDY`: доплачивает долю получателю за счёт `TREASURY:global`.
- `LIMIT`: отклоняет сумму за пределами `minimum`/`maximum`.
- `EXEMPTION`: отключает перечисленные семейства правил для совпавших условий.
- `CUSTOM`: зарезервирован. Включить его без зарегистрированного versioned
  handler нельзя.

`0.05` означает 5%, не 0,05 монеты. Cashback и subsidy завершатся отказом всей
операции, если на финансирующем счёте недостаточно денег.

## Основные команды

Все команды требуют `aurum.admin.policy`; полный вариант — `/aurum policy`,
короткий — `/apolicy`.

```text
apolicy list
apolicy inspect <id>
apolicy history <id>
apolicy reload
apolicy import-config CONFIRM

apolicy create <id> <kind> <value> <categories> [included|added]
apolicy enable <id> [причина]
apolicy disable <id> [причина]
apolicy priority <id> <число> [причина]
apolicy schedule <id> <from|now|-> <until|-> [причина]
apolicy categories <id> <CATEGORY,CATEGORY> [причина]
apolicy rate <id> <rate> [included|added]
apolicy range <id> <minimum|-> <maximum|-> [причина]
apolicy exempt <id> <TAX,FEE,...> [причина]
apolicy account <id> <account-type> <reference> [причина]
apolicy condition <id> <source-type|source-id|target-type|target-id|metadata> <value|-> [причина]
```

Примеры:

```text
apolicy create market-tax tax 0.05 NPC_PURCHASE,NPC_SALE included
apolicy account market-tax TREASURY global
apolicy schedule market-tax now 2026-12-31T23:00:00Z
apolicy enable market-tax налог рынка

apolicy create big-pay-limit limit 10.00:100000.00 PLAYER_PAYMENT
apolicy enable big-pay-limit лимиты переводов

apolicy create vip-relief exemption TAX,FEE NPC_PURCHASE,NPC_SALE
apolicy condition vip-relief metadata tier=sigmas
apolicy enable vip-relief льгота sigmas
```

Для условия по конкретному игроку используется UUID:

```text
apolicy condition vip-relief source-id <uuid>
```

Знак `-` удаляет границу, дату или условие. Время задаётся в ISO-8601 UTC,
например `2026-12-31T23:00:00Z`. Категория `*` разрешает все безопасные игровые
категории.

## Конфиг и источник истины

`financial-policies.enabled` — общий выключатель. Его изменение требует
рестарта. `bootstrap-on-empty` импортирует раздел `rules` только при полностью
пустой таблице правил. После этого MariaDB является источником истины, чтобы
команда или UI не теряли изменения после рестарта. Для намеренного повторного
применения YAML используется `apolicy import-config CONFIRM`; каждое правило
получает новую аудируемую ревизию.

Условия могут ограничивать правило типом/ID исходного и конечного счёта либо
парой metadata `key=value`. Метаданные начнут особенно активно использоваться
после перевода NPC, Arena, Slots и Guilds на AurumEconomyApi.

Защищённые категории — сырые Vault deposit/withdraw, миграция, возвраты,
административные корректировки, holds и внутренние policy-проводки. Это не даёт
случайно обложить налогом техническую операцию или применить правило повторно.

В `aurum_transactions` записываются все ID правил и точная сумма каждого из
них. Изменения самих правил сохраняются в `aurum_financial_rule_revisions` с
ревизией, автором, причиной и временем.
