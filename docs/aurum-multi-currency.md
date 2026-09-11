# AurumCore 0.6.1: несколько валют и обмен

## Граница совместимости

Vault умеет только одну валюту. AurumCore публикует через него валюту из
`economy.primary-currency`, поэтому старые плагины, `/pay` и Essentials не
получают неоднозначных балансов. Все валюты доступны нашим плагинам через
`AurumEconomyApi`: `currencies()`, `balance(account, currency)`,
`globalSnapshot(currency)`, `quoteExchange(...)` и `exchange(...)`.

Добавление новой валюты не требует новой таблицы: счета разделяются по
`currency_id`. Удалять валюту с существующими счетами нельзя; её следует
сначала исключить из новых операций и сохранить данные для аудита.

## Настройка

В `economy.currencies` у каждой включённой валюты задаются `display-name`,
`symbol`, `scale` и `enabled`. Максимум — 16 валют. Primary должна быть
включена. Старый блок `economy.currency` читается как fallback, чтобы прежний
конфиг продолжал запускаться.

`exchange.enabled` — общий переключатель исполнения. Правила можно заранее
создать и оставить выключенными. YAML импортируется лишь при пустой таблице;
после этого MariaDB и команды являются источником истины.

Поля правила: направленная пара `from`/`to`, множитель `rate`, доля комиссии
`fee-rate` от 0 до <1, `minimum`/`maximum`, `priority`, расписание,
`conditions` и `settlement`. Для обратного направления нужно отдельное правило.

- `MINT_BURN`: вход сжигается, выход создаётся; комиссия остаётся в глобальной
  казне. Подходит для системной валюты с управляемой эмиссией.
- `RESERVE`: вход пополняет резерв правила, выход списывается из его резерва.
  При нехватке ликвидности операция отклоняется целиком.

## Команды

Все команды требуют `aurum.admin.exchange`, имеют tab completion и пишут
автора новой ревизии в MariaDB.

- `/aexchange list`
- `/aexchange inspect <id>`
- `/aexchange history <id>`
- `/aexchange create <id> <from> <to> <rate> [fee-rate] [mint-burn|reserve]`
- `/aexchange enable|disable <id>`
- `/aexchange rate <id> <rate> [fee-rate]`
- `/aexchange range <id> <minimum|-> <maximum|->`
- `/aexchange priority <id> <integer>`
- `/aexchange schedule <id> <ISO-8601|now|-> <ISO-8601|->`
- `/aexchange settlement <id> <mint-burn|reserve>`
- `/aexchange condition <id> account-type <type|->`
- `/aexchange condition <id> account-id <id|*|->`
- `/aexchange condition <id> metadata <key> <value>`; вместо key передайте
  `-`, чтобы удалить пару условий.
- `/aexchange reserve <rule> <currency> <give|take> <amount>`
- `/aexchange reload`

Баланс и казна: `/abal [currency]`, `/aurum balance [player] [currency]`,
`/atreasury [currency]`. Административная корректировка:
`/aeco give|take|set <player> <amount> [currency:<id>] [reason]`.

## Контракт NPC-обменщика

NPC сначала вызывает `quoteExchange` и показывает игроку вход, комиссию,
точный выход и время истечения. При подтверждении он создаёт `ExchangeRequest`
с уникальным idempotency key, ID/ревизией правила, ожидаемым выходом и expiry
из котировки. Core повторно проверяет правило и выполняет обе валютные стороны
в одной SQL-транзакции. Повторный клик с тем же ключом не проводит обмен снова.

В 0.6.1 проверка idempotency выполняется до проверки срока котировки. Поэтому клиент может
безопасно повторить запрос после таймаута даже тогда, когда котировка уже истекла: если первая
операция успела зафиксироваться, Core вернёт сохранённый результат и восстановит актуальные
балансы в кэше; новая проводка не создаётся.

Тесты движка проверяют комиссию, округление вниз, нулевой баланс проводок,
лимиты и оба способа расчёта. Перед production-переключением всё ещё нужен
staging-прогон Paper 26.2 + реальная MariaDB: unit-тесты не моделируют сетевой
обрыв в момент подтверждения транзакции.
