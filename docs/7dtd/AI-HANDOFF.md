# Aurum 7 Days to Die — handoff и дорожная карта

Обновлено: 2026-09-18. Эта ветка работы НЕ меняет Minecraft-плагины.
Основной репозиторий: Aki333chan/images. Рабочая копия:
`E:/Codex/2026-08-26/new-chat/work/images-repo`; артефакты — соседний `../../outputs`.
После этапа обновлять этот файл: что сделано, проверки, ограничения, следующий пункт.

## Замысел

Расширяем имеющиеся C# `companion-mod` и модуль панели `sevendays`, не поднимаем
ещё одну публичную веб-панель. Панель отвечает за авторизацию/RBAC/audit,
Pterodactyl — за процесс, файлы и backups; companion — за игровые данные/действия.
Игрокам на ПК не нужен клиентский мод. Console crossplay/EAC ещё не подтверждены.
Harmony пока не нужен; отдельные патчи возможны позже лишь там, где нет public API.

Ориентиры: ServerKit (MIT), CSMM/CPM по функционалу. Не копировать CSMM GPL-код
в проект без отдельного решения о лицензии. Для карты сначала проверить штатные
TFP_MapRendering/TFP_WebServer, существующие REST/SSE и тайлы, не делать дорогой
рендер всего мира на каждом запросе. Не открывать эти backend-порты публично.

## Этап 0 — выполнена локальная часть, live-check открыт

Основа до изменений: images `350ebdf` (2026-09-17); origin/main совпадал 2026-09-18.
Пользователь предоставил Managed в `archive-2026-09-18T110605Z.tar.gz`.
SHA256 архива: `BE612BF060E4E420EC392A29CB80EB5EAB425EA5803CD0A919CF60F6BD439D27`.
SHA256 Assembly-CSharp.dll: `3737EEDC9F143D428A69030317428C45D342BE4F11006A46D72BCD7B1D0FC64D`.
Игровые Constants: release V, major 3, minor 20, build 10; VersionInformation
форматирует это как **V3.2.0 (b10)**. DLL лежат вне git:
`E:/Codex/2026-08-26/new-chat/work/7dtd-reference-20260918/Managed`.
Никакие игровые DLL/декомпилированные исходники в git или ZIP не включать.

### Исправлено

- Реальная сборка прежде не проходила при 76 зелёных Core-тестах: event DTO и enum
  оказались вложенными в ModEvents, WorldState/GameEvent конфликтовали с именами игры,
  NetPackageChat.Setup принимал List<int> вместо имени отправителя.
- Уточнены сигнатуры и заглушки, добавлены collision sentinels.
- Native ThreadManager вместо SynchronizationContext.Send: максимум 32 callbacks,
  ожидание 2 секунды; вызов из игрового потока выполняется прямо.
- Просроченная queued-работа отменяется и не срабатывает позже. Слот занимает до
  потребления callback игрой, иначе таймауты бесконечно раздували бы очередь игры.
- Начатая работа не прерывается; 503/504 сообщает not_started или outcome_unknown.
  Это не универсальная exactly-once система; финансовые/item операции сюда пока не добавлять.
- Broadcast ждёт передачи пакета игровому сетевому слою, не отдаёт преждевременное ok.
- На неудачном запуске освобождаются HTTP и sender; shutdown сначала будит game waiters.
- Ищем игрока также по CrossplatformId. В общий поток панели не отправляем private/party/friends chat.
- Build принимает внешний GameManagedDirectory; build-package.ps1 собирает только
  whitelist собственных файлов и не перезаписывает существующий ZIP.

### Проверено

- .NET SDK 8.0.425 установлен локально в `work/tooling/dotnet8`.
- Core net48/netstandard2.0; Game net48 против предоставленных настоящих DLL;
  StubCheck netstandard2.0; Core tests (89 случаев после изменений).
- Тесты dispatch: direct/main-thread, worker result, exception propagation, timeout,
  отказ при полной очереди, отсутствие позднего side effect, stop, неизвестный исход
  уже начатой работы, scheduler failure; router 503/504 semantics.
- Пакет `AurumCompanion-7DTD-v1.0.1-rc.1.zip` + SHA256, инструкция внутри.
  ModInfo имеет числовую версию 1.0.1 (игра использует System.Version, не semver).
- Панель/production/Minecraft не изменены и не развёрнуты. Это RC, не verified stable.

## Осталось — актуальный порядок

1. **Завершить live smoke этапа 0:** startup, /ping/state/players, ticket/report и приватный
   ответ, public/private chat, join/leave/death, broadcast, restart и занятый порт на тестовом
   сервере. Инструкция: [SMOKE-TEST](../../companion-mod/SMOKE-TEST.ru.md).
   Сборка DLL не доказывает выполнение в Unity/Mono и не требует объявлять релиз stable.
2. **Этап 1 — укрепление сетевой основы:** early auth до чтения request body; byte limit;
   deadline чтения; bounded concurrency; ограничение response body исходящего транспорта;
   единая защита ticket/report от in-flight спама + bounded workers; capabilities в /ping
   и распознавание в панели; EN/RU/PL. Добавить настоящие loopback HTTP-тесты.
   Сейчас HTTP serial и ReadToEnd без лимита; поэтому RC только в приватной сети.
3. **Надёжные события/состояние:** stable event UUID + дедупликация (Prisma migration),
   batch retention cleanup; не обещать сохранность in-memory очереди при рестарте.
   Исправить прогноз Blood Moon: frequency=0 НЕ подставлять как default 7; учесть random range
   или отдавать неизвестное/оценочное время. Кэш короткоживущих world/player snapshots,
   чтобы карта не сканировала все сущности на каждый запрос.
4. **Этап 2 — карта:** read-only explored terrain, игроки, LCB, POI, маркеры и карточки;
   scoped RBAC и скрытие приватной информации; bounded tile cache, incremental/delta updates;
   сначала адаптер доступных TFP API, без полного рендера мира по запросу пользователя.
5. **Этап 3 — игроки:** online/offline карточки, инвентарь read-only, история событий.
6. **Этап 4 — серверные функции:** home/TPA/warp, kits/rewards, safe delivery, зоны,
   permissions/cooldowns/audit. Изменяющие операции проектировать с request-id/receipt;
   нельзя просто повторить выдачу после network timeout.
7. **Этап 5 — экономика/автоматизация:** отдельная предметная модель для 7DTD,
   задания/магазин/Discord по согласованному объёму. Не переносить Minecraft Core вслепую.
8. **Этап 6 — опасные админ-действия:** region/POI reset, backups/restore, prefab editor;
   preview/подтверждение/ограничения на области и права до доступа к кнопке.
9. Позже: optional admin client; игра и обычные пользовательские функции без установки мода.

## Ориентиры в коде

- `companion-mod/src/Aurum.Companion.Core`: transport/router, tickets, events, DTO,
  тестируемый GameThreadDispatcher; не зависит от игровых классов.
- `companion-mod/src/Aurum.Companion.Game`: ModEvents, native ThreadManager, chat packages,
  SdtdGameBridge. Любые изменения проверять реальной компиляцией.
- `apps/api/src/modules/sevendays`: RBAC/ServerScoped, конфиг, companion proxy, events, telnet.
- `apps/web/src/modules/sevendays`, `packages/shared/src/modules/sevendays.ts`: UI/контракты.
- Reference-only ServerKit клонирован вне repo в `work/serverkit-reference` и
  `work/serverkit-webui-reference`. Их старые DLL не заменяют DLL целевого сервера.
- Полный исследовательский отчёт: `outputs/Aurum-7DTD-Research-and-Plan.ru.md`.

## Ссылки

- https://github.com/IceCoffee1024/7DaysToDie-ServerKit
- https://github.com/IceCoffee1024/7DaysToDie-ServerKit-webui
- https://7dtd.illy.bz/wiki/Server%20fixes
- https://community.thefunpimps.com/threads/v3-2-0-stable-b10-hotfix.48799/
