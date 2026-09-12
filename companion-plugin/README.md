# Aurum Companion

## Release 0.11.0

The web panel's balance card, money-supply overview and Richest list now use
dedicated native AurumCore routes. These routes fail closed when Core is not
ACTIVE and never scan or mutate Vault accounts. Legacy endpoints remain for
protocol compatibility, but the panel no longer calls them. The overview was
moved out of host CPU/RAM metrics into the plugin-gated Minecraft Economy tab;
it is loaded on open and cached by the panel, with no background polling.

## Release 0.10.0

The web panel can now list, preview and apply versioned AurumCore financial
policies and exchange rules. Companion exposes only the typed two-phase Core
API: a preview is revision-guarded and actor-bound, while apply consumes its
short-lived token and requires an audit reason. There is no YAML/SQL write,
arbitrary command transport or Vault fallback. HTTP workers wait for Core's
existing database executor; the Paper thread is never blocked and no polling
was added. In the panel these tools live in the Minecraft module's Economy tab;
the tab is omitted when the shared supported-plugin snapshot has no enabled
AurumCore.

## Release 0.9.0

The panel can now request one bounded AurumCore audit section at a time:
overview, ledger history, financial policies, exchange rules, holds, or
quarantined claims. The endpoint is read-only, token-protected, available only
with active AurumCore, and never falls back to Vault because Vault has no
authoritative ledger history. Requests run on the existing HTTP/database
workers; there is no Paper-thread wait or background polling.

## Release 0.8.0

AurumUI can now open a player's guaranteed trade and the administrative
delivery quarantine. The new `trade` scope requires `aurum.trade`; the
`claims-admin` scope requires both `aurumui.admin` and
`aurum.admin.claims`. Companion only transports bounded typed actions. Trade
revisions, money, item escrow and claim state remain owned and revalidated by
AurumCore.

Database-backed snapshots are asynchronous all the way through the plugin
message channel. No database future is joined on the Paper thread, and no new
periodic player or database scan was added: state is requested when the screen
opens, after an action, or when the player explicitly refreshes it.

## Release 0.7.0

Начисления и списания панели при активном AurumCore теперь идут прямо через
`AurumEconomyApi`. Панель создаёт UUID операции и повторяет тот же ключ после
неопределённого сетевого отказа; Core связывает ключ с точными участниками,
суммой, валютой, категорией и metadata. Повтор не платит второй раз, а тот же
ключ с другим смыслом получает `idempotency-conflict`.

Автор и обязательная причина попадают в ledger вместе с проводкой. После
таймаута активного Core отката на Vault нет: проводка могла сохраниться, даже
если ответ потерялся. Vault остаётся совместимым fallback только когда Core
отсутствует или не находится в режиме `ACTIVE`; exactly-once он обеспечить не
может. В панели запись защищает новое ADMIN-only право
`minecraft.economy.admin`.

## Release 0.6.0

Экономика берётся из AurumCore, когда он активен: баланс и общая картина —
одним запросом к ledger вместо обхода всех, кто когда-либо заходил. Вместе с
этим появляются казна сервера, денежная масса и собранные налоги — величины,
которых у Vault не существует в принципе. Если Core нет или он в shadow-режиме,
всё работает как раньше через Vault, и панель об этом знает: в ответе
`/economy` есть поле `source` (`aurum` или `vault`).

## Release 0.5.0

Includes upstream `cdebb2c` (offline-player EssentialsX jail status) and optional
social menus for AurumUI 0.5.0 / AurumGuilds 0.3.0. Protocol 3 gains a SOCIAL
capability bit; older clients safely ignore it. Social scopes use a separate
player-authorized provider and never inherit administrator privileges from the
transport. Async guild results are awaited off the server thread and refreshed
on the server thread. Requests require login when AurumAuth is installed,
are rate-limited, and allow only one outstanding mutation per player.


Плагин-компаньон для Paper. Даёт панели то, чего нельзя получить через RCON:
инвентари игроков, их UUID и пинг, команду `/ticket` в игре, а также
интеграцию со сторонними плагинами сервера.

Начиная с 0.2.0 Companion также служит локальным транспортом для
необязательного Fabric-мода AurumUI. Канал идёт внутри соединения Minecraft и
не использует HTTP-порт, токен или доступность веб-панели. Клиенты без мода
продолжают видеть обычные sidebar плагинов.

С версии 0.4.0 Companion передаёт AurumUI списки уже созданных Arena, NPC,
магазинов, скупщиков и Slots и маршрутизирует их типизированные настройки.
Для каждой вкладки одновременно проверяются `aurumui.admin`, профильное право,
наличие плагина и совместимого provider API. Клиент не может прислать
произвольную серверную команду: разрешённые действия повторно проверяет и
сохраняет сам игровой плагин.

С 0.6.0 (протокол 4) добавлены два scope экономики. `economy` — собственный
баланс и перевод: это не админская вкладка, она доступна всем вошедшим, как
гильдии и пати, а права `aurum.balance` и `aurum.pay` проверяет AurumCore на
каждый вызов. `economy-admin` требует `aurumui.admin` и `aurum.admin.economy`,
и Core проверяет второе ещё раз. Ставки налогов, комиссии и курсы через этот
канал не редактируются — они принадлежат веб-панели, см.
[`../docs/aurum-core-architecture.md`](../docs/aurum-core-architecture.md).

С 0.8.0 тот же протокол 4 использует оставшиеся capability-биты для `trade` и
`claims-admin`; формат пакета не менялся. Сделка управляется обычным правом
`aurum.trade`, а карантин требует одновременно `aurumui.admin` и
`aurum.admin.claims`. Старым клиентам незнакомые биты безопасно маскируются.

В ответе admin_state пишется **согласованная** версия протокола, а не версия
сборки сервера: клиент сверяет её со своей и отказывается от любой другой, так
что более новый номер молча сломал бы админские вкладки всем, кто не обновил
мод.

Сборка: [`BUILD-WINDOWS.md`](BUILD-WINDOWS.md) (Windows) или
[`../docs/companion.md`](../docs/companion.md) (Linux, macOS, установка и
настройка).

---

## Что плагин отдаёт панели

| Эндпоинт | Что делает | Чем обеспечивается |
| --- | --- | --- |
| `GET /players` | список онлайн: UUID, ник, здоровье, мир, координаты, пинг | Paper API |
| `GET /players/{uuid}/inventory` | инвентарь игрока | Paper API, для офлайн — InvSee++ |
| `POST /players/{uuid}/inventory/{slot}` | положить предмет в слот или очистить | Paper API |
| `POST /players/{uuid}/inventory/give` | выдать список предметов, каждый в свободный слот | Paper API |
| `POST /players/{uuid}/inventory/clear` | очистить выбранные слоты или инвентарь целиком | Paper API |
| `GET /plugins` | все установленные плагины: имя, версия, включён ли | Paper API |
| `GET /players/{uuid}/permissions` | группы и права игрока | LuckPerms |
| `POST /players/{uuid}/permissions` | выдать или снять группу либо право | LuckPerms |
| `GET /economy/native/balance/{uuid}` | баланс игрока панели (в том числе офлайн) | только active AurumCore |
| `POST /economy/native/balance/{uuid}/{deposit\|withdraw}` | безопасно начислить/списать из панели | только active AurumCore |
| `GET /economy/native?top=N` | денежная масса, казна, налоги и богатейшие | только active AurumCore |
| `GET /players/{uuid}/balance`, `POST .../{deposit\|withdraw}`, `GET /economy` | старый совместимый контракт | AurumCore, иначе Vault |
| `GET /economy/audit/{section}` | один ограниченный срез ledger/правил/holds/claims | только active AurumCore |
| `GET /economy/rules/{policy\|exchange}` | актуальные ревизии финансовых правил | только active AurumCore |
| `POST /economy/rules/{policy\|exchange}/preview` | проверить полную новую ревизию и получить одноразовый токен | только active AurumCore |
| `POST /economy/rules/apply` | применить подтверждённую ревизию с причиной | только active AurumCore |
| `POST /webtoken/{code}` | обменять одноразовый код игрока на его UUID и ник | AurumAuth |
| `POST /auth/reset/{ник}` | выдать игроку одноразовый токен сброса пароля | AurumAuth |

Все запросы требуют заголовок `Authorization: Bearer <токен>` из
`config.yml`. Порт плагина наружу выставлять не нужно — панель ходит к нему
через приватный туннель.

---

## Поддерживаемые сторонние плагины

Ни один из них не обязателен. Плагин работает и на сервере, где не стоит
ничего, кроме него самого.

### AurumAuth — «вошёл ли игрок» для /webtoken

Команда `/webtoken` выдаёт игроку одноразовый код для входа в панель, а панель
обменивает его на личность игрока через `POST /webtoken/{code}`. Код
одноразовый и живёт несколько минут.

Выдавать такой код можно только тому, кто действительно вошёл: до авторизации
«игрок Стив» — это всего лишь тот, кто набрал ник Стива при подключении.

Раньше на этот вопрос отвечал SQL-запрос в таблицу AuthMe (`isLogged`,
`hasSession`). Теперь — публичный API AurumAuth через `ServicesManager`, тем же
приёмом, что и с Vault. Ни SQL, ни чужого пароля от базы в конфиге, а схема
таблицы стала внутренним делом плагина авторизации.

Через тот же API идёт и сброс пароля: `POST /auth/reset/{ник}` возвращает
одноразовый токен на 20 минут, а панель показывает его администратору в
карточке игрока. Токен приходит в ответе и нигде не логируется — это временный
ключ к чужому аккаунту.

Ответ на «нет плагина авторизации» и «нет такого аккаунта» одинаковый (404,
`reset-unavailable`): различать их значит помогать перебирать ники.

**Если не установлен.** Companion работает; `/webtoken` продолжает выдавать
коды, но подтвердить вход не у кого — на сервере без авторизации понятия «не
вошёл» попросту нет. Сброс пароля отвечает отказом. Плагин пишет об этом
предупреждение при старте.

### LuckPerms — права игроков

**Что даёт.** Вкладку «Права» в карточке игрока: основная группа, список
групп, отдельные права. Оттуда же их можно выдавать и снимать.

**Как подключён.** `softdepend` в `plugin.yml` плюс `compileOnly`-зависимость
на `net.luckperms:api` с Maven Central. Работа идёт через Developer API
(`LuckPermsProvider`), а не сборкой текстовой команды `lp user … parent add`.

**Почему через API, а не RCON.** API отвечает структурой, а не строкой для
разбора регулярками, которая меняется от версии к версии. Через него видно,
существует ли группа, — попытка выдать несуществующую отклоняется с внятной
причиной, а не проходит вхолостую. И сразу после записи читается актуальное
состояние, без второго похода на сервер.

**Если не установлен.** Вкладка «Права» в панели не появляется. Эндпоинты
отвечают `404` с кодом `requires-luckperms` — панель показывает «нужен
LuckPerms», а не ошибку.

### EssentialsX — быстрые действия

**Что даёт.** Кнопки в панели: вылечить, бессмертие, полёт, выдать кит,
сменить режим игры, телепорт к игроку.

**Как подключён.** Никак — действия выполняются обычными командами через
RCON. Зависимости на сборке нет, `softdepend` не нужен. Панель лишь смотрит
в ответ `GET /plugins`, стоит ли EssentialsX, и показывает кнопки только
тогда.

> **Имя в Bukkit — `Essentials`, не `EssentialsX`.** Форк сохранил имя
> заброшенного предшественника ради совместимости. Проверка по строке
> «EssentialsX» не сработала бы никогда, и кнопки просто не появлялись бы.

**Если не установлен.** Кнопки EssentialsX не показываются. Ванильные
действия (сохранить мир, погода, объявление) остаются — они работают везде.

### InvSee++ — инвентари офлайн-игроков

**Что даёт.** Инвентарь игрока, которого нет в сети.

**Как подключён.** `softdepend` в `plugin.yml`; вызовы идут рефлексией.

**Почему рефлексией, а не зависимостью на сборке.** Артефакт InvSee++ лежит
в GitHub Packages, а тот требует токен даже для публичных пакетов. Прописать
такой репозиторий значит сделать сборку плагина невозможной без учётной
записи GitHub — цена, несоразмерная одной необязательной функции. Рефлексия
здесь безопаснее, чем кажется: ею добывается только объект инвентаря, а
читается он штатным Bukkit API, потому что `MainSpectatorInventory`
наследует `org.bukkit.inventory.Inventory`.

**Если не установлен.** Запрос инвентаря офлайн-игрока возвращает `404` с
кодом `offline-requires-invsee`, и панель пишет «установите InvSee++».
Инвентари тех, кто в сети, работают как работали — через Paper API.

### AurumCore — экономика, когда он активен

**Что даёт.** Тот же блок «Валюта» и ту же статистику, но из ledger: баланс
игрока, денежная масса сервера, казна, собранные налоги и доска богатства.

**Как подключён.** `softdepend` в `plugin.yml` плюс `compileOnly`-зависимость
на `ovh.aurumgg:aurum-api`. Провайдер берётся так же штатно, как у Vault:
`getServicesManager().getRegistration(AurumEconomyApi.class)`.

**Почему не всегда.** У Core два режима. В `SHADOW` деньгами по-прежнему
распоряжается старая экономика, а ledger только наблюдает — и отвечать из него
значило бы показывать в панели не те числа, что видит игрок в игре. Поэтому
ledger используется строго при `EconomyMode.ACTIVE`, иначе — Vault.

**Одним запросом, а не обходом.** Доска богатства и сумма денег берутся
`richest()` и снимком экономики: один индексированный запрос вместо похода в
базу на каждого из тысяч офлайн-игроков. Поэтому же в ответе нет
`playersCounted` — считать игроков незачем, а подставлять ноль значило бы
утверждать, что на сервере никого нет.

**Казна и налоги — только здесь.** У Vault их нет не потому, что мы их не
спрашиваем, а потому что там нет ни счёта сервера, ни понятия налога: деньги
просто лежат в кошельках. Панель показывает эти плитки только при
`source: aurum` — пустые нули на их месте были бы неправдой.

**Ответ ждём с таймаутом.** API асинхронный, HTTP-обработчик — нет. Ожидание
ограничено тремя секундами, и истёкшее ожидание значит «не смогли спросить»,
а не «ноль»: панель получит тот же ответ, что и при выключенной экономике.

**Записи идут прямо в ledger.** При `ACTIVE` начисление — проводка
`SYSTEM_SOURCE → PLAYER`, списание — `PLAYER → SYSTEM_SINK`, категория —
`ADMIN_ADJUSTMENT`. Ключ операции, автор и причина записываются в одной
транзакции. Истёкший таймаут не включает Vault fallback: ответ мог потеряться
после commit, поэтому панель повторяет тот же ключ и получает duplicate вместо
второй выплаты.

### Vault — валюта игроков

**Что даёт.** То же самое на серверах без активного AurumCore: блок «Валюта» в
карточке игрока (баланс, начислить, списать) и общий баланс сервера с доской
богатства в его статистике. Сумма здесь — сложенные кошельки всех, кто когда-либо
заходил, а не денежная масса: это разные величины, и панель их не путает.

**Как подключён.** `softdepend` в `plugin.yml` плюс `compileOnly`-зависимость
на `com.github.MilkBowl:VaultAPI` с JitPack. Провайдер берётся штатно:
`Bukkit.getServer().getServicesManager().getRegistration(Economy.class)`.

**Vault сам денег не хранит.** Это прослойка: настоящий плагин экономики
(EssentialsX, CMI, любой другой) регистрирует у Vault провайдера `Economy`, а
мы берём того, кто зарегистрирован. Поэтому панель работает с любой экономикой
и про конкретный плагин не знает вовсе. Из этого же следует, что «Vault стоит»
и «валюта работает» — не одно и то же: HTTP-слой различает `requires-vault`
(нет Vault) и `no-provider` (Vault есть, экономики за ним нет).

**Про выбор перегрузок.** У `Economy` по две версии каждого метода: старые
принимают ник строкой и помечены `@Deprecated` с VaultAPI 1.4, новые принимают
`OfflinePlayer`. Используются только новые — сверено по исходникам интерфейса.
Кроме устарелости, строковые перегрузки ломаются при смене ника, а UUID не
меняется.

**Провайдер не кэшируется в поле.** Плагин экономики могут перезагрузить на
живом сервере, и ссылка на старый — верный способ получить тихо неработающие
начисления. Он запрашивается на каждую операцию; это дешёвый вызов.

**Fallback ограничен совместимостью.** Когда активного Core нет, причина и
автор остаются в аудите панели, а запись выполняет Vault. У Vault нет ключей
идемпотентности, поэтому после неопределённого сетевого ответа exactly-once
гарантируется только в режиме `ACTIVE` AurumCore.

**Если нет ни active Core, ни Vault.** Экономические маршруты отвечают кодом
доступности, и панель показывает блок «Валюта» серым с точной подсказкой:
установить Vault либо подключить его провайдер.

### Что намеренно не подключено

**AdvancedBan и прочие бан-плагины.** Баны ведёт сама панель: своя таблица
с причиной, сроком и модератором плюс RCON-команды `ban`/`kick` для
немедленного эффекта. Это решение принято сознательно, чтобы источник правды
о наказаниях был один.

---

## Язык плагина

`language: ru` в `config.yml` — `ru`, `en` или `pl`. Тексты лежат в папке
`lang/`, по файлу на язык, и правятся руками.

Их здесь немного: companion — мост между панелью и сервером, а не плагин с
интерфейсом. В чат он пишет сам только двумя командами — `/ticket` и
`/webtoken`. **Ответ администрации приходит её собственными словами** и ничем
отсюда не переводится: это переписка живых людей, а не сообщение плагина.

Язык один на сервер, а не на игрока, все три файла распаковываются сразу и при
обновлении не перезаписываются — ровно так же, как в AurumAuth и AurumGuilds.

### Что уезжает в панель ключом, а не фразой

Ответ на вмешательство в гильдии (`message`) и название вида бонуса (`title`)
— **ключи словаря панели**, а не готовые строки. Игровой сервер знает свой
язык, а не язык сотрудника, который смотрит в панель: раньше сюда приезжало
русское предложение, и на английском интерфейсе оно так русским и оставалось.

Подстановки едут двумя картами: `values` — то, что переводить нельзя (ник, имя
гильдии), `keys` — то, что само является ключом (вид бонуса). Подробности и
пример — в `docs/companion.md`.

### Что остаётся русским намеренно

**Журнал сервера.** Его читает администратор в консоли, вперемешку с
сообщениями Paper и других плагинов; по логу ищут причину аварии.

**Ошибки HTTP-протокола** (`error` в теле отказа). Их читает не человек, а
панель — и показывает уже своими словами.

## Мягкая деградация

Правило простое: **отсутствие стороннего плагина — не ошибка**.

- Плагин грузится и работает при любом наборе соседей.
- Мост к игре (`GameBridge`) в таких случаях возвращает «пусто», а не бросает
  исключение. Формулировкой занимается HTTP-слой — так текст один на все
  реализации.
- Ответ содержит машиночитаемый `code`, а не только русский текст: панель
  различает «нет LuckPerms», «нет данных об игроке» и «плагин не ответил» и
  показывает разное.
- Классы сторонних API собраны в отдельных файлах (`LuckPermsIntegration`,
  `InvSeeIntegration`, `VaultEconomyIntegration`, `AurumCoreEconomyIntegration`)
  и не загружаются, пока не
  проверено наличие плагина. Поэтому `NoClassDefFoundError` на голом сервере
  не возникает.

Проверить, что видит панель:

```
GET /plugins
```

Тот же список показан в интерфейсе на странице сервера, блоком
«Поддерживаемые плагины», с отметкой напротив каждого.

---

## Структура

```
core/   чистая Java без Bukkit: HTTP-сервер, JSON, авторизация, тикеты,
        одноразовые коды входа. Собирается и тестируется где угодно.
paper/  адаптер к Bukkit/Paper: точка входа, команды /ticket и /webtoken,
        интеграции. Требует JDK 25 и репозиторий repo.papermc.io.
```

Сборка составная: `settings.gradle.kts` подключает соседний `../auth-plugin`,
чтобы companion собирался против `ovh.aurumgg:auth-api`. Артефакт нигде не
публикуется — Gradle подставляет соседний проект напрямую.

Тесты логики (без Minecraft и без сети):

```bash
./gradlew :core:test
```
