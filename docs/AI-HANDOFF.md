# Aurum ecosystem — AI handoff room

Этот файл — общий рабочий журнал Codex и Claude Code. Перед началом работы агент обязан
прочитать его вместе с `docs/aurum-ecosystem-roadmap.md`, проверить фактический `git status`
и не считать записанный здесь план заменой проверке исходников.

## Правила передачи

1. Рабочий каталог проекта: `E:\Codex\2026-08-26\new-chat`. Не создавать рабочие копии в
   Documents на диске C.
2. Исходники, документация и каталог панели находятся в репозитории `Aki333chan/images`.
   Готовые актуальные JAR для панели — в `Aki333chan/addons` под именами `<name>-v<version>.jar`.
3. После завершённого этапа обновить этот файл и roadmap, выполнить тесты, собрать JAR,
   закоммитить и отправить `Images`, затем обновить JAR в `Addons`, создать и отправить тег
   `<name>-v<version>` и проверить GitHub Release с `.sha256`.
4. Не коммитить `target`, `build`, серверные данные или секреты. Не переписывать чужие
   незавершённые изменения без проверки.
5. В секции «Текущий этап» должна оставаться только актуальная незавершённая работа.
   В «Журнале» добавляется короткая запись с коммитами, тестами и важными решениями.
6. При спорном изменении финансового поведения сначала сохранить инварианты ledger:
   идемпотентность, отсутствие отрицательного доступного баланса, неизменяемый аудит и
   отсутствие прямых Vault-записей из мигрированных плагинов.

## Репозитории и сборка

- Sources: `E:\Codex\2026-08-26\new-chat\work\images-repo`
- Release JARs: `E:\Codex\2026-08-26\new-chat\work\addons-repo`
- User artifacts: `E:\Codex\2026-08-26\new-chat\outputs`
- Maven: `E:\Codex\2026-08-26\new-chat\work\tooling\apache-maven-3.9.16\bin\mvn.cmd`
- JDK 25: `E:\Codex\2026-08-26\new-chat\work\tooling\jdk25\jdk-25.0.4.1+1`
- Gradle-проекты используют собственный wrapper.
- Companion использует Gradle 8.14.3, который не разбирает строку версии локального JDK
  `25.0.4.1`. Wrapper запускать системной Java 21 обычной командой
  `.\gradlew.bat --no-daemon clean test :paper:jar`: Gradle сам использует подготовленный
  Temurin 25 из своего toolchain-кэша. Не задавать этот локальный JDK через `JAVA_HOME` или
  `org.gradle.java.installations.paths`; перед сборкой на новой машине проверить
  `.\gradlew.bat -q javaToolchains`.

## Архитектура экономики

`AurumCore` — единственный авторитетный денежный движок. Он хранит счета и ledger в
MariaDB, публикует `AurumEconomyApi`, обслуживает primary-валюту через VaultUnlocked и
несколько валют через native API. Essentials не должен оставаться economy provider после
финального cutover.

Основные денежные операции представлены сбалансированным набором postings. Policy engine
может атомарно добавлять TAX, FEE, COMMISSION, CASHBACK, SUBSIDY, LIMIT и EXEMPTION.
Exchange engine использует версионированную котировку и идемпотентную фиксацию.

Для операций между MariaDB и состоянием Minecraft используются hold и claim:

1. `createHold` резервирует полный `sourceDebit`, но ещё не переводит деньги.
2. Потребитель записывает долговечный claim до внешнего эффекта.
3. `captureHold` фиксирует денежную операцию идемпотентно; `releaseHold` снимает резерв.
4. Claim хранит курсор и аренду, но внешняя item/command операция обязана иметь свой
   receipt/escrow: MariaDB и Minecraft не образуют одну транзакцию.

Обычный `balance` показывает ledger-баланс; доступность нового списания дополнительно
учитывает активные holds. Текущая реализация рассчитана на один авторитетный экземпляр Core,
а не на конкурентный кластер серверов.

## Актуальные релизы Addons

На 2026-09-15 опубликованы и проверены GitHub Releases с JAR и `.sha256`:
`companion-v0.13.4`, `core-v0.19.0`, `auth-v0.1.0`, `guilds-v0.5.1`,
`npc-v2.4.0`, `arena-v1.6.1`, `slots-v1.6.0`. Заменённые releases и tags удалены;
актуальный Addons commit — `aa687ca`.

## Исторические выпуски и этапы

### AurumCore 0.7.0

- Sources commit: `fcfd945`
- Addons commit: `d2afcaf`
- Release/tag: `core-v0.7.0`
- SHA-256: `8F11D4E690299133BB0DA471D6057B88AD3D11C450E72C807AD63EABE432197C`
- Реализованы ledger, migration/cutover, команды экономики, policies, несколько валют,
  exchange и durable holds с MariaDB migration 7.
- Capture восстанавливается без повторного списания, если денежная транзакция успела
  зафиксироваться, а статус hold — нет.
- Проверка этапа: 30 тестов.

### AddonsNPC 1.9.0

- Sources commit: `229ed30`
- Addons commit: `02ad492`
- Release/tag: `npc-v1.9.0`
- SHA-256: `45C9A7F2D606092F270196C2670D20247A8DAF64565D1C1274520CF844B5FE76`
- Обменники используют atomic exchange API Core.
- Магазины, скупщики и гильдейские торговцы используют holds; прямых Vault withdraw/deposit
  в плагине больше нет.
- `plugins/AddonsNPC/transactions.yml` хранит состояния `HELD/APPLIED`; recovery запускается
  периодически. Гильдейская выдача сверяется по уникальному `guild-actor`.
- Существующие YAML NPC/магазинов не требуют сложной миграции.
- Проверка этапа: 34 теста.

### AurumSlots 1.4.0

- Sources commit: `9e782fc`
- Addons commit: `7512de9`
- Release/tag: `slots-v1.4.0`
- SHA-256: `FA0D0055B0DEFB850BDC23C60C93BDF705593770D9B8582062C90E23F60E4B08`
- `use_vault: true` сохранён как legacy-ключ, но денежные записи теперь идут только через
  native `AurumEconomyApi`; прямой зависимости и вызовов Vault нет.
- Ставка: hold `PLAYER -> SLOTS:<machine-id>`, затем durable journal и capture.
- Выигрыш: отдельный идемпотентный перевод `SYSTEM_SOURCE:slot-payouts -> PLAYER`.
- `transactions.yml` различает принятую ставку и ожидающую выплату. После сбоя captured-ставка
  компенсируется на полный `reservedAmount`, held-ставка освобождается, payout повторяется.
- Активная прокрутка помечается в памяти и не попадает под периодический recovery; после нового
  процесса метки нет, поэтому незавершённая прокрутка безопасно компенсируется.
- `use_vault: false` продолжает использовать физический `pool` внесённых предметов и загружается
  без AurumCore; optional linkage закреплён отдельным тестом.
- Проверка этапа: 13 тестов, Maven/JDK 25 `clean package`.

### AurumArena 1.5.0

- Sources commit: см. ветку `claude/pterodactyl-admin-panel-core-984zye`
- Release/tag: `arena-v1.5.0`, опубликован в Addons 2026-09-13
- Ставки, отмена, возврат при ничьей, выплаты победителям, доля чемпиона и взнос
  в финальную кассу переведены на holds и native API. Зависимости VaultAPI в сборке
  нет; `softdepend` — AurumCore.
- Два счёта на арену: `ARENA_ESCROW:bet:<арена>` и `ARENA_ESCROW:final:<арена>`.
  Возврат ставок не может залезть в призовой пул.
- Появился `roundId` — круг ставок, из которого строятся ключи идемпотентности
  выплат и возвратов.
- Комиссия настраивается: `treasury` (по умолчанию) или `champion-pool`.
  Со ставок берётся всегда, с чемпионского пула — по умолчанию нет.
  Меняется из `/arena commission` и из AurumUI, обе точки пишут в `config.yml`.
- Незавершённые списания — в `transactions.yml`, разбираются по состоянию резерва.
- Проверка этапа: 25 тестов, Maven/JDK 25 `clean package`.

## Известные ограничения

- Trade: предмет, выложенный на стол, не существует физически нигде — только записью в
  MariaDB, а в окне лежит копия для показа. Это осознанно: две копии, которые могут
  разойтись, — это две копии, которые могут задвоиться.
- Trade: сумма задаётся командой `/trade money <сумма>`, не кликом: слот инвентаря не
  умеет принимать число.
- Trade: окно и весь обмен проверены только компиляцией и логикой — живого сервера в этой
  сессии нет. Машина состояний покрыта тестами, Bukkit-слой нет.
- Trade: выдача идёт только игроку, который в сети. Вещи не теряются (заявка ждёт), но
  сделка с тем, кто вышел сразу после расчёта, довыдастся при его следующем входе.
- Trade: круг `encode`/`decode` предметов не покрыт тестом — `ItemStack.serialize()`
  требует живого сервера. Поэтому и используется штатный поток Bukkit, а не своя
  кодировка.
- Trade: `trading.enabled` остаётся `false` до живого fault-injection. Выдача claim
  защищена PDC receipt и порядком player.dat → MariaDB cursor; исходящее изъятие в
  Core 0.11.0 защищено полной квитанцией оферты и idempotent MariaDB operation.

- NPC: доставка идёт только игроку, который в сети. Гильдейский бонус, оплаченный игроком,
  который больше никогда не зайдёт, останется невыданным. Это заметно только на бонусах:
  предметы и так некуда девать без игрока.
- NPC: повторная выдача гильдейского бонуса (авария ровно между выдачей и отметкой курсора)
  продлевает его срок заново, а не удваивает эффект — бонус каждого вида у гильдии один.
- NPC: у продажи есть срок годности заявки (`economy.sale-deadline-seconds`, 15 минут по
  умолчанию). Это защита игрока от изъятия предметов неделю спустя, а не защита денег.
  У покупок срока нет намеренно: за них уже заплачено.
- NPC: бесплатные предложения с 2.1.0 тоже проходят через claim, только без денежного шага.
  Они требуют доступного claim-хранилища Core; прямой небезопасной выдачи больше нет.
- NPC: сток магазина уменьшается вместе с записью заявки и возвращается только на пути, где
  выяснилось, что никого не списали. Авария между уменьшением стока и записью заявки теряет
  одну единицу стока — это не деньги, и отдельного механизма не заведено.
- NPC: полный круг `encode`/`decode` payload не покрыт тестом — `ItemStack.serialize()`
  требует живого сервера. Поэтому предмет пишется штатной сериализацией Bukkit, а не
  собственной кодировкой.
- NPC: произвольные команды оферт имеют явный контракт. Plain/`once:` — at-most-once
  с `advance` до dispatch: дубликата нет, но авария в коротком окне может пропустить
  эффект. `idempotent:` требует `{idempotency_key}`, выполняется до `advance` и безопасно
  повторяется только потому, что принимающий плагин обязан дедуплицировать стабильный ключ.
- Claims: payload непрозрачен для Core, поэтому негодный payload обнаруживается только
  в момент доставки и уходит в карантин. Проверять его заранее Core не может и не должен.
- Реальная MariaDB/Paper staging-проверка всей экосистемы ещё не выполнена.
- Arena: policy-правило `COMMISSION` на категорию `ARENA_PAYOUT` удержит с победителя
  второй раз — рейк арены считается в самом плагине. Это задокументировано в её README,
  но технически не запрещено.
- Arena: настройки комиссии общие для всех арен, как и была ставка до миграции.
  Per-arena значения — отдельный шаг, схема арены в YAML к нему готова.
- Arena: при обновлении с 1.4.0 денежные выплаты старого формата из `recovery.yml`
  НЕ выдаются автоматически — за ними нет проводки в ledger. Плагин перечисляет их
  в логе; рассчитываться администратору через `/aurum economy give`.
- Guilds: policy-правило `TAX` на категории `GUILD_DEPOSIT` или `MIGRATION` уменьшит
  сумму, в том числе на самом переносе старых балансов. Плагин это замечает и пишет
  предупреждение в лог, но не отменяет проводку.
- Guilds: на сервере без AurumCore выдача долей при роспуске идёт через Vault, а у него
  идемпотентности нет. Авария ровно между выдачей доли и удалением гильдии приведёт
  к повторной выдаче при следующем роспуске. С AurumCore этого не происходит.
- Guilds: зеркало `bank_balance` обновляется из ledger после каждой операции банка, но
  между операциями может разойтись, если администратор двигает деньги счёта `GUILD:<id>`
  напрямую командами AurumCore. На выдачу это не влияет — отказывает сам ledger.
- Guilds: переключение обратно с ledger на Vault не поддерживается намеренно. Если
  AurumCore выключить после переноса, банк отвечает «недоступно», а не работает по
  зеркалу.
- Guilds: `bank.on-disband` сохраняет immutable plan до первой выплаты для всех четырёх
  режимов. Участники и суммы не меняются после частичного расчёта; оплаченная доля и
  bank log отмечаются одной DB-транзакцией, restart продолжает неоплаченные строки.
  Exactly-once потерянного ответа обеспечивается AurumCore stable key; у Vault его нет.
- Managed accounts: профиль существующей гильдии впервые получает founder=current leader,
  потому что старая схема Guilds не сохраняла отдельного исходного создателя.

## Текущий этап

**Проверка Core 0.19.0 / Companion 0.13.4: стартовый баланс и настройка в панели.**

Реализация готова: Economy → Starting balance, preview/apply, сумма/валюта/включение.
По умолчанию false / 100 coins; включить в панели после запуска Minecraft. Панель
(API и web) и два серверных JAR обновлены 2026-09-15; сервер оставлен OFFLINE по
согласованной границе деплоя. UI-мод и Essentials kits не менялись.
Следующий live-тест: новый UUID до/после AurumAuth, начисление один раз, reconnect,
restart, изменение/отключение для следующих новых UUID. Старым игрокам backfill нет.
Уже записанные PENDING фиксируют сумму/валюту и исполняются даже после отключения
НОВЫХ обещаний. Подробности в README Core и последней записи журнала.

**Также ожидает живого теста: NPC 2.4.0 / UI 0.11.0 — выручка и restock.**

Реализация и сборки готовы, подробности в последней записи журнала и README AddonsNPC.
Следующее действие — обновить NPC/Companion/UI и проверить живой сценарий: выбрать
получателя, купить товар, увидеть проводку и обратный отсчёт, дождаться восстановления,
повторить со скупкой и перезапуском. Полные лимиты не запускают таймер; деньги скупщика
не восполняются при restock. Сценарии боёв и fault-injection остаются отложенными до тестеров.

Core 0.17.3 содержит MariaDB migration 12 и реестр карточек поверх существующего ledger.
Companion 0.13.1 и web-панель дают on-demand список, детали и управление именованными
фондами без Vault fallback. Player, Guild, Arena и Slots регистрируются идемпотентно;
Arena/Slots перед удалением сохраняют план, блокируют новые операции, проверяют свои
обязательства и переносят все валюты в настроенную казну. Падение или рестарт продолжает
тот же close plan со стабильными ключами.

Общий status gate готов: один SQL-запрос строит индекс при старте, затем hot path читает
ConcurrentHashMap. Прямые проводки, holds, exchange, admin set и policy postings уважают
`FROZEN/CLOSING/CLOSED`; SQL на каждый платёж не добавлен. Guilds 0.5.1 связал durable
disband с managed profile: после последней выплаты, но до удаления строки гильдии,
профиль закрывается; `keep` замораживает его для ручного решения. Даже нулевой баланс
сохраняет барьер, а план строится по авторитетному ledger balance. Live smoke на
пользовательском Paper/MariaDB уже подтвердил обычный transfer, повтор idempotency key,
freeze/unfreeze, отказ при frozen destination, restart persistence, close sweep,
Vault/PAPI и idle Spark. Остались предметные сценарии Arena/Slots/NPC/Guilds, закрытие
с живым hold и контролируемый сбой между sweep-проводками.

`trading.enabled` остаётся `false` до живого Paper/MariaDB fault-injection. AurumUI 0.7.0
остаётся опциональным клиентским Fabric-модом.

В панели готов принудительный административный перевод между любыми двумя активными
нетехническими managed account members. Селекторы ищут на сервере и сортируют по типу;
для Arena адресуются отдельные роли `bet`/`final`. Сумма, валюта и причина обязательны,
операция идёт одной сбалансированной Core-проводкой и не обходит lifecycle/остаток.

## Очередь после текущего этапа

1. Довести live fault-injection на Paper 26.2 + MariaDB + VaultUnlocked: Arena, Slots,
   NPC, Guilds, policy limits/tax, закрытие с hold и сбой между sweep-проводками.
2. Bankroll Slots и бюджет NPC buyers реализованы. Procurement, guild support и
   city/region accounts отложены владельцем; не начинать их вместо тестирования.
3. После fault injection отдельно включать guaranteed trade.
4. Отдельно провести контролируемое обновление production-зависимостей панели: на
   2026-09-13 `npm audit --omit=dev` показывает 14 transitive/direct findings
   (3 high, 10 moderate, 1 low, 0 critical). Не применять `npm audit fix --force`:
   часть автоматических исправлений предлагает major NestJS 12 и требует регрессии.

## Журнал передачи

### 2026-09-13 — Codex, первый live fault-injection и история счёта

- Панель на VDS была чистой, но оставалась на старой Claude-ветке `9346f0c`, поэтому
  обычный pull не получал `main`. После успешного `aurum-backup.service` checkout исправлен
  на `main`, production build и Prisma deploy успешны, readiness: PostgreSQL/Redis `ok`.
- Перед заменой JAR создан locked Pterodactyl backup
  `6889c978-5011-4f26-b355-2fded521baed` (11.8 GB). На сервер установлены Core 0.17.3,
  Companion 0.13.1, Guilds 0.5.1, Arena 1.6.0, Slots 1.5.0; NPC/Auth уже были актуальны.
- Live ledger: два временных фонда получили 55/25, Companion forced transfer дал 50/30,
  точный retry вернул `duplicate` без второго списания. Frozen source отклонил transfer;
  frozen destination отклонил close до изменения source. Restart сохранил 50/30; оба
  close перевели остатки в global и оставили CLOSED-аудит. Cleanup вернул treasury и
  money supply к нулю. Vault provider и `%vault_eco_balance%` подтвердили Core.
- Spark idle: TPS 20.0; tick median/p95 0.1 ms, max 2.5 ms; process CPU 0/0/1%.
  Report: `https://spark.lucko.me/2BHZf7ESxF`.
- Live upgrade выявил старые locale-файлы без новых ключей. Core 0.17.3 материализует
  недостающие строки из bundled EN/RU/PL в памяти, не перезаписывая правки администратора;
  проверено на фактической старой `messages_ru.yml`. Source commits `f10cb57`, `41b376d`;
  Addons `aa3174b`; release `core-v0.17.3`; SHA-256
  `A0543FD3F43CC07121FD80E8D4C065BC014BE9F40D4E1D76FE2EBF2658DDDF28`.
- В account details добавлена on-demand история последних ledger-проводок: выбор member
  role и валюты, отдельные `bet`/`final`, без polling. Source commit `e13110e` развернут
  на VDS; production build, Prisma deploy, readiness PostgreSQL/Redis и 111 web tests
  успешны.
- Для оставшейся live-проверки нужны созданные игровые Arena/Slots/NPC/Guild и два
  одновременно доступных тестовых игрока; на текущем сервере эти списки пусты.
- Production dependency audit панели: 14 findings (3 high, 10 moderate, 1 low,
  critical 0). Среди direct high — `@nestjs/platform-express` и `nodemailer`; обновлять
  отдельным проверяемым этапом, поскольку полный auto-fix перескакивает на NestJS 12.

### 2026-09-13 — Codex, forced transfer между managed accounts

- Source commit `1c9c112`; Addons commit `09ecf9b`; release `companion-v0.13.1`;
  SHA-256 `59C339BFBD356CC9064BC2E6DA066B8728786AD52AF92F7F5397552F9D164893`.
- Панель получила отдельное модальное окно для принудительного перевода: два независимых
  серверных поиска, фильтр/сортировка по типу, выбор member role, сумма, валюта и обязательная
  причина. Доступ остаётся под `minecraft.economy.admin` и отражается в аудите панели/Core.
- Технические source/sink-профили и неактивные счета намеренно не предлагаются. Это только
  сбалансированный transfer: недостаточный остаток, `FROZEN`, `CLOSING` и `CLOSED` Core
  продолжает отклонять.
- Companion 0.13.1 передаёт `sourceRole`/`targetRole`, поэтому роли Arena `bet` и `final`
  больше не схлопываются в несуществующий `primary`.
- Проверки: Companion 172 tests и clean Paper JAR; API 693 tests; web 111 tests; shared
  CJS/ESM, Nest production build, TypeScript/Vite и EN/RU/PL JSON-каталоги успешны.
- Следующий этап остаётся живым fault-injection Paper 26.2 + MariaDB + VaultUnlocked.

### 2026-09-13 — Codex, durable managed guild retirement

- Source commit `e2857e2`; Addons commit `9f0a8d3`; release `guilds-v0.5.1`.
- Guilds 0.5.1 закрывает `guild:<id>` после завершения persisted disband payouts, но до
  удаления guild row. Любой отказ/таймаут Core сохраняет гильдию и план для безопасного
  повтора; stable lifecycle key переживает сбой между закрытием profile и удалением строки.
- `leader`, `split` и `treasury` закрывают профиль и отправляют остатки всех валют в его
  close destination. `keep` замораживает оставленный профиль вместо скрытого sweep.
- Нулевой общак тоже получает persisted plan. В ledger-режиме сумма плана берётся из
  авторитетного Core balance, поэтому прямой перевод из панели не теряется в старом зеркале.
- Проверки: 119 core tests и 13 paper tests (132 всего), включая отказ lifecycle после
  уже проведённых выплат, сохранение нулевого барьера, прямое изменение ledger balance,
  создание отсутствующего profile, close rejection и `keep` freeze.
- SHA-256: `90553AB00E4357A5965CC1F1349719AD1F1CDB0BDA2AA6ACCBF0D22719E66391`.
- Следующий этап: живой fault-injection Paper 26.2 + MariaDB + VaultUnlocked.

### 2026-09-13 — Codex, managed accounts generation

- Registry commit `1001c23`, lifecycle hardening commit `1cb5c16`, Addons commit `baad0ea`.
- Core 0.17.1: migration 12, `AurumAccountRegistryApi`, карточки/участники счёта,
  именованные фонды, list/inspect/create/transfer/pay/collect/freeze/close, durable
  multi-currency sweep и восстановление незавершённых close plans.
- Один startup-query загружает статусы всех участников в `AccountStatusGate`; после этого
  ledger/hold/exchange/admin-set/policy paths проверяют ConcurrentHashMap без SQL. Frozen
  блокирует вход и выход, closing разрешает только исходящий `ACCOUNT_CLOSE`.
- Companion 0.13.0 и панель: strict-native on-demand список и детали, фильтры,
  точные decimal strings, административные действия под `minecraft.economy.admin`,
  обязательная причина и идемпотентный ключ. Vault fallback отсутствует.
- Core регистрирует player wallet при входе. Guilds 0.5.0 синхронизирует карточку и
  текущего лидера. Arena 1.6.0 группирует `bet`/`final`; Slots 1.5.0 регистрирует каждую
  машину. Arena/Slots сохраняют `closing`, блокируют новые операции, проверяют
  обязательства и удаляют игровой объект только после успешного sweep.
- Проверки: Core 78 tests; Companion 172 tests; Guilds 126 tests; Arena 25 tests;
  Slots 13 tests; API 693 tests; shared TypeScript, Nest build, web TypeScript/Vite и
  шесть JSON-каталогов успешны. Maven shade предупреждает только о штатных совпадениях
  manifest/module-info у драйверов Arena.
- SHA-256: Core `5585237B...C865A`; Companion `C6961999...E8A94`; Guilds
  `A68ECC54...3168`; Arena `5E8D9D23...0D82`; Slots `C8927A5F...107F`.
- Следующий обязательный hardening: закрытие managed guild profile после durable disband
  settlement, затем живой close/fault-injection.

### 2026-09-13 — Codex, account registry TODO и актуализация Addons

- В `docs/aurum-account-registry-todo.md` записан следующий финансовый этап: управляемые
  счета с founder/controller, именованные фонды, список и карточка счёта в панели.
- Каждая Arena и Slots machine получает отдельный managed account profile. У Arena
  сохраняются два ledger-subaccount (`bet` и `final`), потому что объединение нарушило бы
  изоляцию возвратов и чемпионского пула. При удалении остатки по умолчанию уходят в
  `TREASURY:global`, либо в настроенный активный фонд через restart-safe close plan.
- В план включены procurement, guild support и будущие city/region treasuries. Cashback
  и subsidy по умолчанию остаются на глобальной казне. Отдельный event fund не нужен.
- В Addons commit `554afd8` опубликованы семь текущих releases с `.sha256`; старые
  releases/tags удалены. Локальные устаревшие сборки отправлены в Корзину, `outputs`
  содержит только актуальные JAR, AurumUI 0.7.0 и справочные файлы.

### 2026-09-12 — Codex, runbook первого live cutover

- Подготовлен `deploy/AURUM-ECOSYSTEM-FIRST-CUTOVER.md`: отдельная пустая логическая
  MariaDB для Core на том же DB-сервере, backup файлов/MariaDB/PostgreSQL, обновление
  панели, ручная установка staging JAR, passive → active → closed-gate последовательность,
  smoke checks и rollback.
- Для fresh ledger `active.require-verified-migration: false` применяется только в
  `plugins/AurumCore/config.yml` перед первым active-запуском; после успешной проверки
  возвращается `true`. Сервер не открывается игрокам до контрольного третьего запуска.
- Собран единый локальный комплект семи серверных JAR в
  `outputs/aurum-ecosystem-staging-2026-09-12`; фактические SHA-256 сверены и записаны в
  `SHA256SUMS.txt`. Addons/release по-прежнему не публиковались до живого staging.
- Повторно собраны и прошли тесты: Arena 25, NPC 58, Slots 13; Guilds и Auth — все тесты.
  Core 74 и Companion 170 были проверены на предыдущем этапе.
- Следующая работа — пользовательский live cutover по runbook, затем fault injection,
  Spark и только после этого публикация Addons package.

### 2026-09-12 — решение о чистом первом развёртывании

- Развёрнутые сервер и панель ещё pre-Core: экономика остаётся на Vault, AurumCore и новые
  версии экосистемы не установлены.
- Тестовые NPC и арену переносить не нужно — пользователь разрешил удалить и пересоздать.
  Универсальная миграция динамических YAML исключена как лишняя сложность и нагрузка кода.
- Все Vault-балансы принадлежат трём тестерам, были накручены и ценности не имеют. Их
  обнуляют до backup, shadow-import не выполняется; Core начинает с пустого ledger через
  одноразовый `active.require-verified-migration: false`, затем флаг возвращается в `true`.
  Старые plugin data предварительно архивируются; неожиданные ценные Guilds/Slots данные
  рассматриваются отдельно до cutover.
- Следующий этап — deployment/staging runbook, затем живой fault-injection. Изменений на
  сервере этим решением ещё не выполнялось.

### 2026-09-12 — Codex, Core 0.16.0 + Companion 0.12.0, безопасный absolute set

- Sources implementation commit: `b927871`.
- `BalanceSetRequest` связывает idempotency key с expected/target. MariaDB сначала
  фиксирует intent, затем под блокировкой счёта либо проводит точную разницу, либо пишет
  terminal `CONFLICT`; повтор конфликта не оживает, повтор успеха сообщает отдельно
  неизменяемый результат операции и свежий `currentBalance`.
- Нулевой и no-op set создают долговечную квитанцию. Financial policies обходятся
  намеренно, чтобы target оставался абсолютным; активные holds всё ещё запрещают
  небезопасное уменьшение доступных средств.
- Companion добавил strict-native `POST /economy/native/balance/{uuid}/set`. API панели
  добавил ADMIN-only endpoint и аудит, карточка игрока — кнопку set с EN/RU/PL текстами.
  При timeout браузер сохраняет тот же expected/key; при stale view показывает конфликт
  и обновляет баланс, не перезаписывая счёт.
- Проверка: Core 74 теста, Companion 170, API 693, web 111; production builds
  shared/Nest/Vite успешны. `AurumCore-0.16.0.jar` SHA-256
  `334D99D6E5E01906EEE694D85CE03E00A7BB90E9D2225439429AC949AAB45D6D`;
  `AurumCompanion-0.12.0.jar` SHA-256
  `B0E1B8207AF6646D28A30706C19CAB6D998A3006327066B7E3A18AA3A8D7BB58`.
  Оба лежат в `outputs`; Addons release отложен до общего staging.

### 2026-09-12 — Codex, AddonsNPC 2.1.0 и контракт команд оферт

- Sources implementation commit: `deb36af`.
- Plain и `once:` пост-команды магазинов/скупщиков стали at-most-once: claim cursor
  фиксируется до Bukkit dispatch, поэтому рестарт не создаёт дубликат. Возможный пропуск
  в узком аварийном окне задокументирован.
- `idempotent:` требует `{idempotency_key}`; при создании claim он заменяется уникальным
  стабильным ключом команды. Ошибка получателя делает defer/retry, а дедупликацию обязан
  реализовать сам получатель. CLI скупщика отклоняет неправильную строку заранее.
- Schema payload поднята до 2 и сохраняет режим; schema 1/plain строки читаются как
  at-most-once. Бесплатные магазины тоже создают claim. При abandon конечного склада
  возвращается полное количество покупки, а не одна единица.
- Проверка: Maven/JDK 25 `clean package`, 58 тестов. Собранный
  `AddonsNPC-2.1.0.jar` имеет SHA-256
  `7445134B97F06565078D5DBA34904F8E5FACC727E2A92877DACEBB9280861BF0`;
  Addons release отложен до общего Paper/MariaDB fault-injection.

### 2026-09-12 — Codex, панель экономики переведена на strict-native Core

- Companion 0.11.0 получил отдельные `/economy/native` и
  `/economy/native/balance/...`: без active AurumCore они отвечают
  `requires-aurumcore` и никогда не читают/не изменяют Vault. Старые маршруты
  оставлены для совместимости протокола.
- Денежная масса, казна, налоги и Richest убраны из общей полосы CPU/RAM и
  перенесены в capability-вкладку экономики. Snapshot запрашивается при открытии,
  кэшируется пять минут и обновляется вручную; polling и OfflinePlayer scan не добавлены.
- Баланс в карточке online/offline игрока сохранён и показывается только при включённом
  AurumCore. `minecraft.economy.view` остаётся у ADMIN/MODERATOR, но поля изменения
  требуют `minecraft.economy.admin` (ADMIN); OWNER/GM получает общий wildcard.
- Проверка: Companion clean test/jar, 690 API Jest, 111 web Jest и production
  builds shared/Nest/Vite. Собранный `AurumCompanion-0.11.0.jar` имеет SHA-256
  `88206109251D2D36C374A04D4A5FE30701E3162E5793344DDCF762EABDC2E455`;
  Addons release пока не публиковался. Следующий этап остаётся прежним —
  idempotency команд оферт.

### 2026-09-12 — Codex, экономика перенесена в capability Minecraft-модуля

- Удалены прямые карточки аудита и редактора из общего `ServerDetailPage`.
  Minecraft-манифест теперь объявляет capability `economy`, frontend registry
  связывает её с отдельной вкладкой `Экономика` рядом с консолью и core-вкладками.
- AurumCore добавлен в `KNOWN_PLUGINS` с коротким EN/PL/RU описанием. Вкладка
  создаётся только при праве `minecraft.economy.view` и включённом AurumCore;
  сам редактор внутри дополнительно требует `minecraft.economy.admin`.
- Проверка использует тот же живой либо допустимо запомненный снимок `PluginsPanel`.
  Второго запроса и нового polling нет; список монтируется независимо от числа вкладок,
  поэтому отсутствует циклическая блокировка первого определения Core.
- Проверка: shared CJS/ESM, Nest API и Vite production builds; 689 API Jest и
  111 web Jest. Добавлены регрессии на manifest и все состояния доступности Core.
- Следующий этап остаётся прежним: idempotency-контракт произвольных команд оферт.

### 2026-09-12 — Codex, Core 0.15.0 + безопасный редактор финансовых правил

- Добавлен узкий `AurumRulesAdminApi` для policies и exchange rules. Preview проверяет
  полный канонический документ, current revision, лимиты и доменные ограничения и выдаёт
  actor-bound одноразовый токен с TTL пять минут.
- Apply требует причину, потребляет токен до записи и через row lock атомарно сверяет
  expected revision, сохраняет следующую ревизию и audit row. Одновременное изменение
  возвращает conflict; потерянный успешный ответ не может создать ещё одну ревизию.
- Companion 0.10.0 публикует token-protected list/preview/apply без Vault fallback.
  HTTP worker ожидает существующий executor Core; Paper thread, scheduler и polling не
  добавлены. Статус `applied_reload_failed` означает уже состоявшийся DB commit.
- API панели сам формирует автора из аутентифицированной сессии и требует
  `minecraft.economy.admin`; одноразовый токен не записывается в panel audit body.
  Веб-редактор EN/PL/RU показывает current/proposed diff, предупреждения и применяет
  изменение лишь после явного подтверждения с причиной.
- Проверка: 63 Core engine tests и Paper tests; Companion clean test/jar; shared CJS/ESM,
  Nest API и Vite production builds; 689 API Jest и 109 web Jest. Живой MariaDB/Paper
  fault-injection ещё относится к финальному staging.
- JAR и Addons release пока не публиковались. Следующий этап — idempotency-контракт
  произвольных команд оферт.

### 2026-09-12 — Codex, единый пакет установки Aurum

- Панель больше не предлагает собирать экосистему галочками: одна кнопка ставит
  AurumCompanion, Core, Auth, Guilds, NPC, Arena и Slots отдельными JAR.
- Состав принадлежит API, а не браузеру. Уже имеющиеся файлы пропускаются; зависимости
  раскрываются транзитивно; Core идёт раньше NPC, а ошибка Core блокирует только NPC.
- VaultUnlocked не перепаковывается: наличие Vault-подобного JAR проверяется по `plugins/`,
  при отсутствии панель объясняет, что нативный API работает, а сторонним Vault-плагинам
  нужен отдельный мост.
- Legacy-поведение каждого компонента проверено по `plugin.yml`, конфигу и мостам и
  записано в `docs/aurum-plugin-compatibility.md`. Важное: AddonsNPC сейчас hard-depend
  от Core; Guilds имеет Vault fallback; Arena/Slots автономны только в item mode.
- Проверка этапа: 21 unit-тест AddonsService, 689 API Jest, 109 web Jest и
  shared/API/web production builds.
- Следующий шаг не изменился: mutation API и редактор версионированных rules/rates.

### 2026-09-12 — Codex, Core 0.14.0 + ретроспективный аудит панели

- Новый read-only `AurumAuditApi` отдаёт ровно одну из шести секций: overview, ledger,
  policies, exchanges, holds или quarantined claims. Размер ограничен 200 строками и
  2 KiB на поле; ошибки БД возвращают unavailable, а не ложный пустой аудит.
- Для истории добавлен stable account selector (`player:<uuid>`, `guild:<id>`,
  `treasury:global` и другие `AccountType`). Фильтрация делается индексированным SQL до
  `LIMIT`, postings последних транзакций забираются одним `IN`-запросом без N+1.
- Migration 11 добавляет индексы currency/time для transactions и holds. Overview считает
  оборот, эмиссию, изъятие и налоги агрегатами; запрос выполняется только при открытии.
- Companion 0.9.0 публикует token-protected GET route без Vault fallback. API панели
  повторно ограничивает тип секции, account selector и limit; доступ требует существующего
  `minecraft.economy.view`.
- Веб-панель EN/PL/RU свёрнута по умолчанию, грузит только выбранную вкладку, умеет
  фильтровать ledger по счёту и обновляется только вручную/при переключении.
- Проверка: Core clean test/jar, Companion clean test/jar, shared CJS+ESM, Nest build,
  686 API Jest, web TypeScript, 109 web Jest и production Vite build.
- Следующий шаг: mutation API и редактор версионированных policies/exchange rules.

### 2026-09-12 — Codex, AurumUI 0.7.0: trade и карантин выдач

- Core 0.13.0 публикует два узких request-driven provider: состояние гарантированной
  сделки и административный карантин claims. Действия делегируются существующим
  `TradeService`/`ClaimService`; правила, ревизии и ledger не дублируются в UI.
- В trade игрок приглашает, принимает, задаёт денежную часть, открывает защищённый
  Bukkit-стол, подтверждает именно показанную ревизию и отменяет с подтверждением.
- Карантин показывает до 50 проблемных выдач; `retry` возвращает запись в очередь,
  `drop` требует подтверждения и оставляет статус в неизменяемом аудите.
- Companion 0.8.0 умеет передавать асинхронные snapshots без ожидания на Paper thread.
  Фонового polling и нового обхода игроков нет: запрос только при открытии scope,
  завершении действия, ручном refresh либо редком повторе потерянного ответа пока окно открыто.
- Права: trade — `aurum.trade`; карантин — одновременно `aurumui.admin` и
  `aurum.admin.claims`, с повторной проверкой Core. Формат protocol 4 не менялся;
  заняты последние два бита capability-byte, старому протоколу они маскируются.
- Проверка: clean test/jar Core, clean test/jar Companion и clean build AurumUI на JDK 25.
  Живого Minecraft/Paper прогона не было, поэтому `trading.enabled` остаётся `false`.
- Следующий шаг: native read routes и ретроспективные экраны веб-панели.

### 2026-09-12 — Codex, Core 0.12.0 + native Companion economy writes

- Core migration 10 добавляет `request_hash`: общий ledger idempotency key теперь связан
  с from/to/currency/canonical amount/category/sorted metadata. Старые строки с `NULL`
  сохраняют прежнюю duplicate-семантику, новые отвергают другой intent кодом
  `IDEMPOTENCY_KEY_REUSED`.
- Companion 0.7.0 направляет deposit/withdraw прямо в `AurumEconomyApi` при `ACTIVE`:
  `SYSTEM_SOURCE → PLAYER` или `PLAYER → SYSTEM_SINK`, категория `ADMIN_ADJUSTMENT`.
  После таймаута активного Core Vault не вызывается, потому что мог потеряться только
  ответ уже сохранённой проводки.
- Панель требует UUID операции, автора текущей сессии и непустую причину. После
  неопределённого ответа UI повторяет тот же UUID; ledger и аудит получают source,
  duplicate и код результата. Доступ отделён правом `minecraft.economy.admin`.
- Vault остаётся fallback только при отсутствующем/неактивном Core и не обещает
  exactly-once. HTTP `set` не добавлен: безопасная абсолютная операция нуждается в
  отдельном контракте, учитывающем промежуточные изменения баланса.
- Проверка: Core clean test/jar; Companion clean test/jar; API Nest build и 684 Jest;
  shared TypeScript; web TypeScript, 109 Jest и production Vite build.
- Следующий шаг: AurumUI — окна торговли и разбора claims quarantine.

### 2026-09-12 — Codex, outgoing trade escrow receipt

- Core поднят до 0.11.0; migration 9 добавляет `aurum_trade_offer_operations`.
- Перед изъятием сериализуются полная новая оферта и исходный stack. Receipt и отсутствие
  предмета принудительно сохраняются одним `player.dat`, затем `offerIdempotent` одной
  транзакцией двигает revision/offer и пишет marker.
- Потерянный ответ оставляет receipt и повторяет тот же operation key; окончательный
  conflict создаёт стабильный `trade-deposit-refund:<operation>` claim и только после его
  записи очищает receipt. Другой похожий stack из инвентаря больше не удаляется.
- Recovery работает на join и в существующем 10-секундном sweep, но обращается к БД только
  если у конкретного online-player есть receipt. На пустом сервере и обычных игроках новых
  запросов нет; обязательный `saveData()` происходит один раз на фактический item deposit.
- Operation journal ограничен одной последней строкой на owner: новый durable receipt
  делает старый невосстановимым и удаляет прежние marker-строки в той же DB-транзакции.
- Unit-тесты проверяют, что повтор ключа не меняет revision дважды, а ключ нельзя
  переиспользовать для другой оферты. Нужен живой fault-injection Paper/MariaDB перед
  включением `trading.enabled`.

### 2026-09-12 — Codex, persisted guild disband plan

- Для `leader`, `split`, `treasury` и `keep` до первой выплаты сохраняются режим,
  баланс в целых копейках и immutable список получателей. Смена config после начала
  роспуска план не меняет.
- Новые таблицы `<prefix>_disband_plans` и `<prefix>_disband_shares` обычно пусты и не
  читаются HUD/чатом. Незаконченный план загружается один раз на старте и повторяется
  только минутным housekeeping либо сразу после готовности AurumCore.
- Доля и bank-log фиксируются одной MariaDB-транзакцией. Crash после ledger transfer,
  но до отметки строки, безопасен за счёт stable idempotency key AurumCore. Vault не
  умеет дать такую гарантию, это оставлено явным ограничением fallback-режима.
- Пока план существует, изменение состава, настроек, бонусов и банка блокируется.
  Удаление guild row и плана атомарно и допускается только без неоплаченных долей.
- Исправлен опасный fallback: уже мигрированный `GUILD:<id>` не откроется через старое
  Vault-зеркало, если AurumCore временно отсутствует при рестарте.
- При удалении очищаются in-memory bonuses/regions, которые раньше оставались до restart.
- Регрессионные тесты останавливают split после первой доли и имитируют потерю DB-ответа
  уже после ledger transfer: restart/повтор завершают план без повторной выплаты.

### 2026-09-12 — Codex, player-data receipts для предметных claim

- Shop give, buyer take и trade delivery получили receipt на claim/step в PDC игрока.
  После изменения инвентаря один `player.saveData()` делает предмет и receipt durable;
  только затем двигается cursor в MariaDB. Retry с receipt повторяет лишь `advance`.
- После успешного `advance` receipt удаляется; редкий stale receipt после аварии между
  `advance` и удалением очищается по актуальному списку owed claims.
- При частичной ошибке добавления восстанавливается точный снимок storage inventory,
  а не удаляется похожий стак, который мог принадлежать игроку раньше.
- Это точечный sync I/O только на реальную предметную операцию. На join и sweep нет
  записи на диск; обычные balance/placeholder reads по-прежнему работают из памяти.
- Ограничение исходящего trade из этого старого этапа закрыто Core 0.11.0; произвольные
  console commands по-прежнему требуют idempotency от целевого плагина, а live Paper
  fault injection остаётся частью staging.
- Проверка: AddonsNPC Maven/JDK 25 `clean test`; Core полный Gradle `clean test`.

### 2026-09-12 — Codex, Core/claims hardening после аудита Claude

- Устранён self-deadlock: `TradeService` больше не делает `join()` экономики на том же
  однопоточном DB executor. Регрессионный тест воспроизводит именно эту топологию.
- Встречные денежные оферты одной валюты сворачиваются в одну net-проводку, поэтому нет
  половинного capture. Hold key включает ревизию и имеет детерминированный expiry.
- `SETTLING` возобновляется после рестарта; запись всех item-claims ожидается и проверяется
  до перехода в `SETTLED`. При нехватке средств сделка возвращается в новую OPEN-ревизию
  без подтверждений.
- Повтор hold key с иным from/to/amount/purpose/reference/expiry/metadata отклоняется и в
  сервисе, и внутри MariaDB repository (защита межсерверной гонки).
- Claim finish требует действующую аренду; settle — полный cursor; admin drop не крадёт
  активную заявку; `advance` использует настроенный max lease; добавлен `pause` без attempts.
  Worker id NPC и trade основан на UUID мира, а не только на порте Pterodactyl.
- Исправлено завышенное обещание документации: claim даёт durable at-least-once delivery,
  не exactly-once внешний эффект. Торговля остаётся выключенной до item escrow/receipts.
- Проверка: Core `clean test :paper:jar`, AddonsNPC Maven/JDK 25 `clean test package`.

### 2026-09-12 — Codex, Guild disband guard + неблокирующее чтение Companion

- `deleteGuild` больше не игнорирует отказ расчёта банка. При недоступной экономике или
  отказе leader/treasury-проводки строка, состав и LP hooks остаются на месте; повтор
  безопасен благодаря стабильному ключу. Добавлен регрессионный тест.
- Сводка и баланс AurumCore в Companion больше не запускаются внутри `callSync`: provider
  кешируется при `onEnable`, MariaDB futures ждёт HTTP worker. В главный поток уезжает
  только короткое разрешение UUID → имя; Vault fallback остаётся синхронным по контракту.
- В `companion-plugin/local-repo` добавлен отсутствовавший `aurum-api-0.10.0.jar`, из-за
  которого чистая сборка Claude не воспроизводилась.
- Ограничение: `bank.on-disband: split` всё ещё multi-transaction и требует persisted plan;
  native записи Companion вместо Vault входят в следующий отдельный API-этап.
- Проверка: Guilds Gradle `test`; Companion Gradle `test :paper:jar`.

### 2026-09-11 — Codex

- Создан общий handoff-файл после релизов Core 0.7.0 и NPC 1.9.0.
- Оба репозитория на момент создания были чистыми.
- Следующая работа: аудит и миграция AurumSlots; код этапа ещё не менялся.

### 2026-09-11 — Codex, AurumSlots 1.4.0

- Завершена и выпущена миграция денежного режима Slots на Core 0.7.0 holds.
- Подтверждено: `SlotMachine.pool` — очередь физических ставок, а не денежный призовой пул.
- Добавлены WAL/recovery, полный policy-aware refund, idempotent payout и защита от гонок
  reload/remove/logout/повторного клика.
- Сохранён автономный item mode и существующие YAML машин; сложная миграция не нужна.
- Следующий шаг: начать аудит AurumArena по плану выше.

### 2026-09-11 — Claude, AurumArena 1.5.0

- Ветка: `claude/pterodactyl-admin-panel-core-984zye`; на момент начала её содержимое
  уже было влито в `main`, работа продолжена от `main`.
- Аудит показал два расхождения с планом этапа: entry fee в арене не существует вовсе
  (регистрация бесплатна), а комиссия казино испарялась — `distributable` уменьшался,
  и разница не доставалась никому.
- Комиссия теперь реальная проводка. По решению владельца проекта все комиссии и
  подобные удержания идут в казну сервера; для арены добавлен выбор «казна или
  чемпионский пул», потому что это разные по смыслу решения.
- При чтении `docs/aurum-holds.md` найдена и исправлена ошибка: Core сверяет резерв и
  capture по metadata ЦЕЛИКОМ (`HoldService.sameIntent`), а у них различался один ключ.
  Capture отказал бы уже после резервирования денег игрока. Инвариант закреплён тестом.
- Локализация арены устроена подстановками русских строк; правка текста молча ломает
  перевод. Добавлен тест, требующий, чтобы каждая подстановка en/pl находила свою
  строку в исходниках. Заодно вычищены четыре мёртвые записи в pl.
- Тест версии плагина больше не хранит её константой, а сверяет `plugin.yml` с pom.
- Следующий шаг: выпустить `arena-v1.5.0` в Addons, затем начать аудит AurumGuilds.

### 2026-09-11 — Claude, AurumGuilds 0.4.0

- Аудит показал, что план этапа был точнее, чем нужно, в двух местах, и обе правки
  сделаны по факту, а не по плану:
  - **holds здесь не нужны.** Hold защищает случай, когда одна сторона операции —
    не ledger (предметы, бонус, состояние Minecraft). У гильдий обе стороны денежные:
    кошелёк игрока и счёт `GUILD:<id>`. Достаточно одной атомарной `transfer`.
  - **durable journal и recovery тоже не нужны** по той же причине: после падения нет
    промежуточного состояния, которое надо было бы досводить. Единственное место, где
    стабильный ключ обязателен, — выдача долей при роспуске, потому что список
    получателей длиннее одного.
- Ещё два пункта плана оказались пустыми: бонусы гильдии за счёт казны НЕ существуют
  (`grantBonus` денег не трогает), а entry fee и подобных списаний в гильдиях нет.
- `EconomyBridge` переписан со счёта гильдии как адресата: `deposit`/`withdraw` по
  `guildId`, `BankResult` вместо boolean (причину отказа теперь знает мост, а не
  вызывающий), отдельные `disburse`/`toTreasury`/`seed` со стабильными ключами.
- Мосты: `AurumCoreBridge` (ledger) рядом с прежним `VaultBridge`, выбор между ними —
  в `GuildEconomy`, по тому же правилу, на котором уже обжигались с Vault: сервис может
  появиться позже нас, поэтому ловится `ServiceRegisterEvent`. Обратно на Vault
  переключения нет намеренно.
- Перенос старых балансов защищён ДВУМЯ вещами: идемпотентным ключом проводки и
  отметкой в новой таблице `<префикс>_bank_migrated`. Ключа одного мало — после первого
  же вклада сумма станет другой, и второй перенос выглядел бы законной операцией.
- Роспуск с ненулевым общаком стал настройкой `bank.on-disband` (leader / split /
  treasury / keep). Это изменение поведения: в 0.3.0 деньги просто исчезали, что на
  ledger означало бы сумму, запертую на недостижимом счёте. Прежнее поведение —
  `treasury`.
- Попутно исправлено: `deposit`/`withdraw` брали гильдию как `guilds.get(memberOf.get(p))`,
  а `ConcurrentHashMap.get(null)` бросает NPE — игрок без гильдии получал «внутреннюю
  ошибку» вместо «вы не в гильдии».
- Следующий шаг: выпустить `guilds-v0.4.0` и `arena-v1.5.0` в Addons, затем начать
  claim/quarantine layer.

### 2026-09-11 — Claude, AurumCore 0.8.0

- Аудит AddonsNPC назвал два окна аварии точнее, чем запись в ограничениях:
  - предметы выданы, WAL ещё HELD → recovery ОСВОБОЖДАЕТ резерв, и товар достаётся
    бесплатно. Это не «нет строгой гарантии», а потеря денег;
  - пост-команды идут после capture и исчезают вместе с процессом.
    Третье, тише остальных: `transactions.yml` переписывается целиком без fsync, и
    обрыв записи теряет ВСЕ незавершённые saga разом.
- Слой доставки положен в Core, а не в плагин: хранилище должно быть транзакционным
  (MariaDB уже есть) и общим — тот же механизм нужен гарантированной торговле.
- `AurumClaimApi` — отдельный сервис, а не часть `AurumEconomyApi`: доставка это не
  деньги, и плагину, который только доставляет, незачем держать интерфейс, умеющий
  двигать балансы.
- Аренда вместо блокировки. Упавший работник не оставляет заявку запертой навсегда:
  аренда истекает, заявка возвращается в очередь с сохранённым курсором. Ceiling на
  аренду в конфиге — это ровно «сколько упавший сервер держит чужой товар».
- Прогресс — один курсор, а не таблица шагов. Это накладывает на плагин обязанность
  описывать доставку строго упорядоченным списком, и это честная цена: у предметов и
  команд порядок и так есть.
- Все переходы — один условный UPDATE, чей WHERE описывает состояние, в котором
  вызывающий считал заявку. Количество изменённых строк и решает, кто победил.
  Чтение с последующей записью было бы гонкой с удобным на вид API.
- Проверка этапа: 8 новых тестов на гонку взятия, истёкшую аренду, движение курсора
  только вперёд, автокарантин и потолок аренды; сборка Gradle/JDK 25.
- Следующий шаг: перевести AddonsNPC на claims (план в «Текущем этапе»).

### 2026-09-11 — Claude, AddonsNPC 2.0.0

- Порядок покупки перевёрнут: раньше предметы выдавались до отметки APPLIED, теперь заявка
  пишется, пока деньги только зарезервированы, а списание стало ШАГОМ 0 доставки.
- Почему списание — шаг, а не предусловие: запись обязана существовать ДО движения денег.
  Если бы деньги двигались первыми, авария между ними и записью оставила бы списанного
  игрока без записи о долге — тот же баг, сдвинутый на шаг раньше.
- Capture делается ИЗ СНИМКА, который вернул Core (`hold(key)`), а не из значений,
  сохранённых плагином: Core отклоняет capture, отличающийся от резерва хотя бы одной
  записью metadata, и узнать об этом после резервирования денег игрока — худший момент.
- Предмет кладётся в payload штатной сериализацией Bukkit и не ищется заново в конфиге:
  админ, правящий shops.yml между оплатой и выдачей, — обычное дело, а игрок, заплативший
  вчера, должен получить вчерашнюю витрину.
- Команды подставляются в момент покупки: их плейсхолдеры описывают цену, которая была
  уплачена, а не баланс завтрашнего дня.
- Найдена и исправлена собственная ошибка порядка: карантин требует держать аренду, поэтому
  payload разбирается ПОСЛЕ `take`, а не до. Иначе нечитаемая заявка навсегда осталась бы
  PENDING и перевыдавалась при каждом входе, ни разу не дойдя до администратора.
- Без AurumCore платные покупки теперь не проводятся вовсе: записать долг некуда, а выдавать
  товар без записи — ровно то, от чего избавляет 2.0.0.
- Тест версии больше не хранит её константой, а сверяет `plugin.yml` с pom — как в Arena.
- Тест локалей усилен до полного сравнения ключей с эталонным en, а не точечных проверок.
- Проверка этапа: 39 тестов (было 34), Maven/JDK 25 `clean package`.
- Следующий шаг: тем же приёмом перевести скупщиков и гильдейских торговцев.

### 2026-09-11 — Claude, AddonsNPC 2.0.0, скупщики

- У продажи необратимая половина — ИЗЪЯТИЕ предметов, а не выдача, поэтому порядок шагов
  обратный магазинному: запись, предметы, выплата. Прежнее окно между «забрали» и
  «отметили» теряло имущество игрока, и восстанавливать его было не из чего.
- **Резерв у продаж убран полностью, и это не упрощение.** Он доказывал наличие денег —
  доказывать нечего: скупщик платит из `SYSTEM_SOURCE:npc-buyers`, а системный источник в
  ledger обеспечен всегда (см. `MariaDbHoldRepository.reserve`). На деле резерв работал
  обещанием, и обещание с TTL истекало ровно тогда, когда сервер лежал. Заявка — то самое
  обещание, и она не истекает; выплата стала обычной идемпотентной проводкой.
- Плечо доставки обобщено: `ClaimDelivery` держит аренду, курсор и ответы шага, а что
  именно делает шаг, знает `DeliveryPlan` (`ShopPlan`, `BuyerPlan`). Тот же каркас нужен
  гильдейским торговцам и будущей гарантированной торговле.
- `Outcome` каждого шага одноразовый и жалуется в лог при повторном ответе: план, ответивший
  дважды, сдвинул бы курсор за шаг, которого не было.
- Срок годности есть только у продажи и защищает игрока, а не деньги. У покупки срока нет:
  за неё уже заплачено, и купленное причитается сколько бы времени ни прошло.
- Проверка этапа: 44 теста (было 39), Maven/JDK 25 `clean package`.
- Следующий шаг: гильдейские торговцы, затем удаление `transactions.yml` и `NpcSaga`.

### 2026-09-12 — Claude, AddonsNPC 2.0.0, гильдейские торговцы и конец WAL

- Порядок тот же, что у покупки: списать, потом выдать. Обратный порядок оставлял бы
  гильдию с усилением, которое никто не оплатил, — и именно ради этого случая прежняя
  версия опознавала выданный бонус по подписи `guild-actor`. Приём переставал работать,
  стоило бонусу истечь: истёкший выглядел невыданным, резерв освобождался, усиление
  доставалось даром. Курсор заявки от существования бонуса не зависит.
- Подпись `guild-actor` сохранена, но сменила назначение: не восстановление, а журнал
  бонусов гильдии, где должно быть видно, какой NPC и по какой покупке выдал усиление.
- Распущенная между оплатой и выдачей гильдия — карантин, а не повтор: деньги ушли, и
  повторять тут нечего.
- `transactions.yml` НЕ удалён вместе с кодом, и это осознанно. На живом сервере в нём
  вполне может лежать резерв, сделанный за секунду до остановки; удалить файл значило бы
  оставить деньги игрока заблокированными до истечения TTL и ни разу об этом не сказать.
  Теперь он только дочищается, удаляется сам, когда пуст, и на новой установке не
  создаётся. Периодическая задача дочистки заводится, только если в журнале что-то есть.
- Проверка этапа: 50 тестов (было 44), Maven/JDK 25 `clean package`.
- Следующий шаг: гарантированная торговля между игроками — первый случай, где предметы
  идут в обе стороны.

### 2026-09-12 — Claude, AurumCore 0.9.0, гарантированный обмен

- Сверился с `docs/aurum-core-architecture.md` ПЕРЕД тем, как проектировать: там уже были
  записаны и место сделки (Core), и таблицы, и изъятие предметов в TRADE_ESCROW. Три
  вопроса, которые я записал в прошлый раз как открытые, оказались решёнными год назад.
  `aurum_trades` и `aurum_trade_offers` лежали с migration 1 и не использовались ни одной
  строчкой — теперь используются.
- Главное свойство — ревизия: любая правка обнуляет оба подтверждения, подтверждение
  называет ревизию, за которую его дали. Проверка стоит в самом UPDATE: решать по чтению,
  сделанному на одну правку раньше, — ровно та ошибка, от которой ревизия защищает.
- Предмет уходит из инвентаря в тот же тик, когда записывается оферта. Живой копии нет
  нигде: два места, которые могут разойтись, — это два места, которые могут задвоиться.
- Любой финал сделки — расчёт, отмена, таймаут — заканчивается тем, что вещи кому-то
  должны, и долг этот записан заявкой. Поэтому полный инвентарь, офлайн и перезапуск
  откладывают выдачу, а не теряют её.
- Отступление от плана: деньги резервируются в момент расчёта, а не при выкладывании.
  Гарантия та же (ничего не двигается, пока не обеспечены обе стороны), но без создания и
  освобождения резерва на каждую правку суммы.
- Предметы сериализуются штатным потоком Bukkit с версией формата. Своя кодировка не
  падает громко на зачарованиях и модификаторах — она возвращает меч, который тихо их
  потерял, и игрок, заплативший за него, ничего не докажет.
- Окно намеренно оставлено на потом: безопасность живёт в ревизии, а не в пикселях, и
  команды прогоняют ровно ту машинерию, поверх которой окно потом сядет.
- Проверка этапа: 9 новых тестов на машину состояний (гонка приглашений, сброс
  подтверждений, отказ устаревшему подтверждению, расчёт только при совпадении ревизий),
  сборка Gradle/JDK 25.
- Следующий шаг: GUI одной сделки поверх `TradeCoordinator`.

### 2026-09-12 — Claude, AurumCore 0.9.0, окно сделки

- Окно сделано ОБОЛОЧКОЙ, как и обещал: правила остались в `TradeCoordinator`, файл окна
  решает только, как это выглядит. Каждый клик отменяется первой строкой и превращается в
  вызов того же сервиса, которым пользуются команды.
- В слотах лежат копии для показа. Настоящие вещи ушли из инвентаря при выкладывании и
  существуют записью в Core; окно с настоящими стаками было бы вторым местом, способным
  разойтись с первым, — а разошлись значит задвоились.
- Кнопка подтверждения отправляет ревизию, С КОТОРОЙ ОКНО БЫЛО НАРИСОВАНО, а не текущую.
  Отправлять текущую значило бы подтверждать за игрока то, чего он не видел.
- Закрытие окна = отмена сделки: на столе чужие вещи, и молчаливый уход оставил бы их
  висеть до таймаута.
- Найдена и исправлена своя же ошибка по дороге: первая версия клика клала предмет в руку
  и вызывала команду `item`, которая читает руку ПОСЛЕ асинхронного похода в базу. Между
  этим предмет в руке мог смениться, и на стол ушло бы не то, по чему кликнули. Теперь стак
  передаётся по значению, а из инвентаря изымается только когда оферта готова к записи.
- Проверка этапа: сборка Gradle/JDK 25, тесты машины состояний. Bukkit-слой проверен
  только компиляцией — живого сервера в этой сессии нет.
- Следующий шаг: по очереди AurumUI, но веб-панель на AurumCore API выглядит срочнее.

### 2026-09-12 — Claude, Core 0.10.0 + Companion 0.6.0 + панель

- Core: `AurumEconomyApi.richest(currencyId, limit)` — доска богатства одним
  индексированным запросом по `aurum_accounts` вместо обхода всех офлайн-игроков.
  Ранжируются только счета `PLAYER`; чужая валюта возвращает пустой список, а не
  список своей: молча выдать не то, о чём спросили, хуже, чем не выдать ничего.
- Companion: `AurumCoreEconomyIntegration` — баланс и сводка из ledger строго при
  `EconomyMode.ACTIVE` (в `SHADOW` деньгами распоряжается старая экономика, и числа
  ledger не совпадут с тем, что видит игрок). Vault остаётся откатом, а не удалён.
- Ответ `/economy` получил `source` (`aurum`/`vault`). У ledger нет `playersCounted` —
  поле отсутствует, а не равно нулю: ноль означал бы «на сервере никого нет».
- Казна, денежная масса и собранные налоги приходят только при `source: aurum`, и
  панель рисует эти плитки только тогда: у Vault таких величин не существует, а нули
  на их месте были бы неправдой.
- Записи (deposit/withdraw) намеренно оставлены через Vault: в `ACTIVE` Core сам
  зарегистрирован провайдером `Economy` с наивысшим приоритетом, и запись и так
  попадает в ledger. Второй путь к деньгам — это второй способ ошибиться.
- Проверено: сборка Core и Companion, `tsc` для web и api, 683 теста API, 108 тестов
  web (jest, включая парность ключей i18n), и оба состояния экрана в браузере —
  с ledger плитки «Казна»/«Налоги» есть, на Vault их нет, а подсказка показывает
  число игроков.

### 2026-09-12 — Claude, AurumUI 0.6.0: экономика в игре

- Денежные правила вынесены из обработчика команды в `EconomyOperations`: лимиты
  перевода, задержка, запрет платить себе и разбор отказа ledger теперь в одном
  месте, и окно пользуется им же. `PaymentRules` — чистый класс с тестом.
- Задержка между переводами стала атомарной: было «посмотреть и положить», и два
  запроса в одну миллисекунду оба проходили. Тест на двадцать одновременных
  попыток закрепляет, что проходит ровно одна.
- `EconomyUiBridge` собирает снимок только из кэша: Companion вызывает его в
  главном потоке сервера. Чужой баланс приносит асинхронное «выбрать игрока».
  Пустое поле означает «ещё не знаем» и рисуется как `…`, а не как ноль.
- Права проверяются на каждый запрос и в Companion, и в Core. Разбор действий в
  мосте идёт по имени действия, а не по id объекта: id приходит от клиента.
- Две ошибки совместимости, найденные по дороге и исправленные здесь же:
  в `admin_state` писалась версия сборки сервера (клиент сверяет её со своей и
  отверг бы), а в hello клиента не было ступени 3 — сервер протокола 3 не понял
  бы 4 и откатился бы на 2, потеряв все админские вкладки.
- Ставки налогов, комиссии и курсы в окно намеренно не вынесены: они остаются за
  панелью, см. `docs/aurum-core-architecture.md`, раздел «Кто чем управляет».
- Проверено сборкой: AurumCore, Companion (тесты протокола зелёные), AurumUI.
  Игрового прогона в этой сессии не было — Minecraft здесь запустить негде.

### 2026-09-13 — Codex, конечные bankroll Slots и бюджеты NPC buyers

- По решению владельца live smoke/fault-injection пока отложен, а миграция панели на
  NestJS 12 исключена как ненужная. Следующий продуктовый этап начат сразу с конечных
  источников выплат.
- AurumSlots 1.6.0 больше не выигрывает деньги из `SYSTEM_SOURCE:slot-payouts` по
  умолчанию. У каждой машины источник `SLOTS:<id>`; `TREASURY:<fund>` настраивается
  отдельно, а `LEGACY` оставлен только для явного отката. До ставки проверяется запас
  `ставка × economy.bankroll-check-multiplier` (100 по умолчанию — максимальный штатный
  множитель). Выбранный источник записывается в durable spin journal: изменение конфига
  или рестарт не перенаправит уже обещанную выплату. Если счёт был внешне опустошён между
  проверкой и результатом, выплата остаётся pending debt, а не исчезает.
- AddonsNPC 2.2.0 даёт каждому скупщику счёт `NPC_BUYER:<id>`. До записи claim и изъятия
  предметов Core резервирует полный policy-adjusted debit с выбранного конечного счёта.
  При нехватке средств игрок сохраняет предметы. Claim schema 3 хранит account + hold key;
  старые schema 1/2 продолжают рассчитываться из исторического system source, чтобы
  обновление не бросило ранее записанный долг. Удаление buyer сначала закрывает профиль
  и переводит остатки в настроенную казну.
- Команды: `/slots bankroll ...`, `/npc buyer budget ...` и общий
  `/aaccount transfer <from-profile> <from-role> <to-profile> <to-role> <amount>
  [currency:<id>] [reason]`. Последний использует тот же registry transfer, что панель,
  и не обходит lifecycle/insufficient-funds.
- AurumUI 0.8.0 показывает эффективный счёт выплаты машины/скупщика и позволяет менять
  mode + treasury через типизированные серверные действия. Нового polling нет: поля
  приходят в уже существующем admin snapshot при открытии или refresh.
- Проверено: Gradle `clean build` Core (все engine/Paper tests), Maven/JDK 25
  `clean package` AddonsNPC и AurumSlots, Gradle/JDK 25 `clean build` AurumUI; 263 ключа
  каждой клиентской локали совпадают. Source commit `832438e`, Addons commit `013eba2`.
  Releases `core-v0.18.0`, `npc-v2.2.0`, `slots-v1.6.0` успешно созданы workflow;
  заменённые releases/tags 0.17.3/2.1.0/1.5.0 удалены. SHA-256 соответственно:
  `441B559184CAD0D3A1DC23D8E889C6C80B7DDC9CEE16329524440125E647431F`,
  `902DB8F506295B43B4D023C050139E0324A224F57D39EBD45F7272DE78B36477`,
  `35B67A1CE87A1B821AA6D6F6BDB6B0D7B1550BA306AD8536B1BAC04D212E64DA`.
- Следующий пункт: администрирование госзакупок и поддержки гильдий. Городские/региональные
  казны остаются только возможностью реестра до отдельного игрового дизайна.

### 2026-09-13 — Codex, граница доступного live-тестирования

- Владелец отложил госзакупки, поддержку гильдий и интеграцию городских/региональных
  казн. NestJS 12 по-прежнему не является задачей.
- Базовый live smoke Core/managed accounts уже подтверждён предыдущим прогоном: transfer,
  idempotency, freeze/unfreeze, restart persistence, close sweep, Vault/PAPI и idle Spark.
- Продолжать предметный fault-injection сейчас нельзя достоверно: нет двух одновременно
  доступных тестеров и созданных расходных Arena/Slots/NPC/Guild. Консольная имитация не
  проверит инвентари, GUI, disconnect и физическую передачу предметов.
- Остаток не объявлен пройденным и не является найденным дефектом. Полная матрица и
  prerequisites сохранены в `docs/aurum-live-validation-todo.md`; пункт roadmap 9 остаётся
  частично выполненным. `trading.enabled` должен оставаться `false` до отдельного прогона.

### 2026-09-14 — Claude/Codex, быстрые действия счетов и читаемый AurumUI

- Claude добавил в карточку любого активного нетехнического managed account быстрые
  `credit`, `debit`, перевод и freeze/unfreeze. Core проводит credit/debit отдельной
  сбалансированной `ADMIN_ADJUSTMENT` через global system source/sink; actor, причина и
  idempotency обязательны. Панель по умолчанию скрывает CLOSED-профили, не удаляя аудит.
- AurumUI 0.9.0 исправляет перенос вкладок, пересечение status/buttons, ширину колонок,
  перевод серверных ключей и бесполезную кнопку Back в корневых social-экранах. Новых
  серверных capabilities или polling нет.
- При ревью исправлены релизные дефекты: изменённый Core/Companion больше не используют
  номера уже опубликованных JAR (`0.18.1` и `0.13.2`), dependency Companion поднята до
  актуального API, случайно добавленный старый `aurum-api-0.10.0.jar` удалён. Добавлен
  отдельный HTTP regression test на обе операции `credit`/`debit`.
- Локально успешны Gradle clean build Core, Companion и AurumUI с JDK 25. Артефакты:
  `AurumCore-0.18.1.jar` (`179E32FDA2478BEC0E4DB8A294DE03CC8E8764F58F02F08A1C491150D718C667`),
  `AurumCompanion-0.13.2.jar` (`62DF89E59CD11D98420839E3A472392E0EFDDFE9ABE6A0C2F4A8C0545BA2187D`),
  `AurumUI-0.9.0.jar` (`36E999BC15B38D36AEDEC50B917F5D38403EA7D38BFCE7723042BCBE3160CA9D`).

### 2026-09-14 — Codex, хотбар в открытом UI и перенос оферт

- AurumUI 0.10.0 перехватывает колесо во всех своих экранах, формах и окнах
  подтверждения. Выбранный vanilla hotbar slot меняется клиенту и сразу отправляется
  серверу, поэтому действие `item from hand` не успевает увидеть старую руку. Цифровые
  клавиши намеренно не перехватываются.
- В карточках shop/buyer offer поле GUI slot стало действием редактирования. Клиент
  предупреждает о занятой цели, а AddonsNPC 2.3.0 повторно проверяет диапазон и требует
  `replace=true`; перенос сохраняет предмет, цену, stock/bulk, имя и promotion.
- Проверено JDK 25: AddonsNPC `clean package`, 64/64 теста; AurumUI `clean build`.
  Артефакты: `AddonsNPC-2.3.0.jar`
  (`B91BE444A12BF1E27B0240174B442F2E8118DAEEC9FD66C797BBBECBADAAD190`) и
  `AurumUI-0.10.0.jar`
  (`47489C9C34AAD7B17C8B66761FA417EC341DAAFFC7ACA6DF12716DD6F5D8C641`).

### 2026-09-14 — Codex, фонд финала Arena синхронизирован с Core

- Исправлена вторая точка истины: AurumArena 1.6.0 показывала и выплачивала локальный
  `arenas.<id>.finalPool`, хотя панель меняла настоящий member-счёт
  `ARENA_ESCROW:final:<arena>`. Поэтому перевод 500 coins на роль `final` был виден в
  панели, но голограмма продолжала писать 0.
- AurumArena 1.6.1 асинхронно читает авторитетный balance Core, не чаще одного запроса
  на арену за интервал и без работы с MariaDB в Paper tick. По умолчанию интервал —
  пять секунд (`economy.final-pool-refresh-seconds`, допустимо 1..300). После старта,
  регистрации счёта и создания финальной голограммы выполняется раннее обновление.
- Одновременно выполняется только один запрос; revision guard не позволяет запоздалому
  ответу затереть только что принятый взнос или комиссию. Во время боя/выплат polling
  остановлен, чтобы незавершённые асинхронные выплаты не вернули на экран старый пул.
- В AurumCore money mode действие `set_final_pool` теперь закрыто: локальное изменение
  создавало бы необеспеченный приз. AurumUI 0.10.1 показывает фонд read-only в списке;
  пополнение и списание делаются реальной проводкой по роли `final` в панели.
- Regression test проверяет точный account id `ARENA_ESCROW:final:colosseum`, сумму 500
  и отказ от неавторитетного snapshot. JDK 25: AurumArena `clean package` — 27/27 тестов,
  AurumUI `clean build`; во всех локалях UI по 275 одинаковых ключей. Артефакты:
  `AurumArena-1.6.1.jar`
  (`F6E5927D92BC4DC7931C0DD165A8A5B3C0A611AC2A3B8EFF40103A7E0E6E4B8B`) и
  `AurumUI-0.10.1.jar`
  (`59E9155C88FD37649CC1493DE9BD36A3D45CA704E9BAAC4A938A512361F74F2A`).
- Live-проверка после замены JAR: уже внесённые 500 coins должны появиться без повторного
  перевода максимум через пять секунд после перехода арены в WAITING. Затем отдельно
  проверить, что финальный бой списывает этот же счёт и платит победителю.
- Sources commit `de68617`, Addons commit `3f82e63`; release `arena-v1.6.1` проверен,
  заменённый release/tag `arena-v1.6.0` удалён.

### 2026-09-14 — Codex, получатель выручки и пополнение ассортимента

- AddonsNPC 2.4.0: магазину задаются revenue.profile и revenue.role. Дефолт —
  treasury:global/primary. Предыдущая версия фактически направляла выручку на
  NPC_SHOP:<shop>, а не в казну; старые остатки автоматически не мигрируются.
  Новая покупка проверяет активного нетехнического получателя и фиксирует точный
  AccountId в hold; изменение настройки не меняет ранее записанный hold/claim.
  Налоги Core продолжают действовать с прежними категориями.
- OfferStock общий для shops/buyers: remaining, maximum, interval-seconds, due-at,
  cycle. Максимум общий для всех игроков. Configure заполняет максимум и сбрасывает
  таймер; off сохраняет остаток. Первая зарезервированная сделка запускает таймер,
  последующие не продлевают; expiry однократно восстанавливает максимум. Если сделка
  отклонена до claim, её объём возвращается, при полном запасе таймер гаснет.
  Выкуп скупщика ограничен квотой; bulk учитывает только целые партии.
- Состояние сохраняется в shops.yml schema 5 / buyers.yml schema 3. Старые YAML
  читаются; для проданного до нуля finite-запаса сохраняется infinite:false.
  Срок абсолютный, учитывает время офлайн. Hidden offers обновляются лениво;
  одна задача раз в секунду касается только открытых GUI и отправляет изменившиеся
  иконки. Фоновых SQL-запросов для запасов нет.
- PurchaseClaim schema 3 и SaleClaim schema 4 сохраняют cycle; возврат старой
  операции не добавляет запасы после restock или замены. Асинхронный rollback
  магазина теперь прибавляет только собственное количество с cap, не восстанавливает
  устаревший stockBefore поверх чужих покупок. Старые claim-схемы читаются как раньше.
  Незавершённая скупка со сменившимся циклом отменяется до изъятия предметов.
- AurumUI 0.11.0: NPC → магазин → счёт выручки, выбор существующего профиля/роли,
  подтверждение; отдельные поля restock для shops/buyers. Companion 0.13.3 маршрутизирует
  shop-accounts:<shop>:<offset>, async snapshots/actions уже поддерживались. Запрос по
  20 профилей только при открытии страницы, прежние addonsnpc.admin/aurumui.admin.
- Команды и YAML-примеры находятся в addons-npc/README.md. Новые строки EN/RU/PL.
  Восстановление квоты не создаёт деньги на счёте скупщика.
- Проверено: JDK 25 Maven clean package NPC — 74/74, Gradle Companion clean test
  :paper:jar — 173/173, UI clean build. В каждой UI-локали 281 одинаковый ключ.
  Новые тесты покрывают старт/expiry/перезапуск таймера, rollback и конкурентный остаток,
  ограничение unit/bulk по квоте, сохранение cycle в claim, выбор final vs bet,
  отказ при missing/frozen/closed/technical получателе. Игровой GUI/двух игроков
  и fault-injection этого обновления локальные тесты не заменяют.
- Артефакты: AddonsNPC-2.4.0.jar
  84D28A269CA17980697F20D6D326B7B3D21E2B8B8C15E0F564370FA6A0E5CD8D;
  AurumCompanion-0.13.3.jar
  849EB70E4C95F69DE3BD61ABC5B7ADB955E4509B4823DABE52E6DFC87C3E1BA6;
  AurumUI-0.11.0.jar
  4582B78ADCBD1EE2362CF6FE1CB0858C149E67DFEABC25466214E033A7BD0193.
- Sources commit `f0a7012`, Addons commit `94bb446` отправлены в main.
  Releases `npc-v2.4.0` и `companion-v0.13.3` опубликованы; digest каждого JAR
  совпадает с локальным SHA-256, sidecar приложен. Заменённые releases/tags
  `npc-v2.3.0` и `companion-v0.13.2` удалены. Старые локальные JAR/sidecar
  NPC/Companion/UI сохранены в `archive/commerce-2026-09-14`, вне Git.
  Новые три JAR находятся в outputs. На игровой сервер обновление не ставилось.

### 2026-09-15 — Codex, стартовый баланс и карточка экономики панели

- Core 0.19.0: migration 13, singleton StartingBalanceSettings и журнал immutable
  revisions с actor/reason. starting-balance.enabled=false/currency=coins/amount=100
  импортируются один раз при первом ACTIVE запуске, дальше настройки только в БД.
  Это отдельный RuleType.STARTING_BALANCE, не policy на каждую транзакцию.
- Первый join пишет решение PENDING/SKIPPED с замороженными amount/currency/revision.
  Existing PLAYER ledger/profile members при установке получают SKIPPED; старый
  Bukkit firstPlayed также исключает backfill. Timestamp после installedAt позволяет
  восстановить нового игрока, если первый SQL был прерван, а player.dat уже сохранился.
  Выключенная настройка и ноль создают SKIPPED; последующее включение его не меняет.
  Изменение/выключение относится к НОВЫМ обещаниям, записанные PENDING не отзываются.
- StartingBalanceCoordinator хранит только онлайн-ожидающих. Join/quit и один clock
  раз в секунду; SQL/ledger на DB executor. Ошибки повторяются через 60 сек с
  ограничением логирования. Выплата ждёт AurumAuth.isAuthenticated через optional
  service; установленный, но недоступный/disabled Auth закрывает выплату.
  Другие login-плагины специально не интегрированы; без AurumAuth ждём только join.
- SYSTEM_SOURCE:global → PLAYER, категория STARTING_BALANCE, stable key
  starting-balance:<uuid>. Остаток не является признаком нового игрока. После SUCCESS
  или DUPLICATE решение становится PAID; потеря ответа/ошибка finish повторяет тот же
  intent. Редкий DUPLICATE этой категории обновляет кэш from/to из ТЕКУЩЕГО ledger,
  чтобы потерянный ответ commit не оставлял клиенту кэш ноль. Policy/status checks
  сохранены; policy, явно затрагивающая категорию, может изменить net или отказать.
- Panel Economy → Starting balance: включение, сумма, ID валюты, preview diff и
  подтверждение с причиной. БД CAS защищает конфликт параллельных редакторов; старый
  actor-bound token не применим другим автором. MODERATOR читает, ADMIN/GM изменяет
  через существующие minecraft.economy.view/admin и ServerScoped. Companion 0.13.4
  разрешает starting_balance в прежних GET/preview/apply; Vault fallback/polling нет.
- EN/RU/PL. Киты Essentials и Fabric UI не менялись. Обновлять нужно два JAR и панель
  (API + web). Настройки применяются без рестарта, но установка JAR требует рестарт.
- Проверено: Core 98 тестов (включая SQL persistence/CAS на H2 MySQL mode, не MariaDB),
  Companion 173, API 695, web 111. Production build панели успешен.
  Browser mock-прогон реального React-компонента: preview/apply/reason, read-only
  moderator, отсутствие polling, EN/RU/PL, 390px без overflow. Скриншоты в outputs.
  Live Paper + MariaDB + AurumAuth не выполнялся, сервер/production panel не менялись.
- JAR SHA-256: Core 4C5603BE4D95DB4828C35673F7C896CB2BFB589955FD048B8F83BCFE62897655;
  Companion 8F4AC9E0DAA9C66FE40C943939E8F00AEB5E09E9D96081F990BBB4A7622339DF.
- Источники `e26731f`, Addons `aa687ca` отправлены в main. Releases/tags
  `core-v0.19.0` / `companion-v0.13.4` опубликованы с JAR и sidecar;
  GitHub asset digests совпадают с указанными SHA-256. Старые releases/tags
  `core-v0.18.1` / `companion-v0.13.3` удалены; локальные предыдущие
  артефакты перенесены в `archive/starting-balance-2026-09-15` для восстановления.

### 2026-09-15 — Codex, деплой стартового баланса по запросу владельца

- Production /opt/aurum-panel был чистым на main/bf54a3f; fast-forward до d98b9c7,
  prisma:generate и полный npm run build прошли. package-lock/dependencies и
  Prisma migrations между этими коммитами не менялись, npm ci и DB deploy не нужны.
  aurum-api перезапущен, ready=true, database=ok, redis=ok, NRestarts=0.
  HTTPS manage.aurumgg.ovh отдаёт новый index-C-sBb58E.js / index-CiJ20z-d.css.
- Minecraft Community #1 (fa9d9622) проверен OFFLINE через Pterodactyl до/после
  замены. Загружены Core 0.19.0 и Companion 0.13.4; SHA-256 перечитанных с игрового
  сервера JAR совпали с релизами. NPC 2.4.0 / Arena 1.6.1 и остальные уже актуальны.
- Старые Core 0.18.1 / Companion 0.13.3 сохранены на игровом сервере в
  /aurum-update-20260915 с суффиксом .previous, вне plugins. Конфиги, мир, балансы
  и Essentials kits не менялись, полная резервная копия игрового сервера не создавалась.
- Minecraft намеренно НЕ запускался. Migration 13 и первое ACTIVE bootstrap
  выполнятся при следующем запуске. Пока нет live-подтверждения инициализации Core,
  работы новой карточки с реальным Companion и стартовой выплаты после AurumAuth.

### 2026-09-15 — Codex, AurumGuilds 0.5.2: вход игрока без гильдии

- Исправлен `GuildService.touchUsername`: отсутствующее членство больше не передаёт
  `null` в `ConcurrentHashMap.get`. До исправления каждый вход игрока без гильдии
  ронял `PlayerJoinEvent` с NPE; игрок при этом входил, но обработчик гильдий
  не завершался штатно.
- Для такого игрока путь теперь заканчивается после одного lookup в памяти. SQL и
  другие фоновые операции не запускаются. Добавлен регрессионный тест.
- Проверено `guilds-plugin/gradlew clean test build`: все core/paper тесты прошли,
  JAR `AurumGuilds-0.5.2.jar`, SHA-256
  `FA59954968FBDA3D3B3B29612ECD42F63676E5555252422A68769121E74D8F46`.
- Эта NPE сама по себе не объясняет несколько секунд с 5–6 MSPT: исключение и
  печать stack trace дают краткий дополнительный шум в join tick, но для вывода о
  главной причине нужен профиль Spark именно интервала подключения после установки
  0.5.2.
