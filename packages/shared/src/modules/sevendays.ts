/**
 * Контракт модуля 7 Days to Die между apps/api и apps/web.
 *
 * ПРО ПРОТОКОЛ — ГЛАВНОЕ. Игра НЕ поддерживает Source RCON. В
 * serverconfig.xml, который поставляется вместе с сервером, нет ни одного
 * свойства со словом rcon; удалённое администрирование — это встроенный
 * telnet: TelnetEnabled (по умолчанию true), TelnetPort (8081),
 * TelnetPassword (пустой — тогда сервер слушает только loopback). Второй
 * интерфейс, WebDashboard на 8080, по умолчанию выключен и сделан для
 * просмотра, а не для управления.
 *
 * Поэтому общий RCON-транспорт Minecraft здесь не переиспользуется — он бы
 * просто не подключился. «RCON» в документации хостеров 7DTD означает то же
 * самое telnet-подключение, названное привычным словом.
 *
 * ВАЖНО: ни адрес, ни пароль telnet-консоли в этих типах не появляются — они
 * не покидают бэкенд, наружу отдаются только флаги «настроено / нет».
 */

/**
 * Игрок 7 Days to Die — как его отдаёт команда `lp` (она же `listplayers`).
 *
 * Полный формат строки задан в самой игре (ConsoleCmdListPlayers):
 *
 *   0. id=171, Lost, pos=(342.4, 49.0, -541.9), rot=(0.0, 194.1, 0.0),
 *   remote=True, health=112, deaths=10, zombies=225, players=3, score=175,
 *   level=12, pltfmid=Steam_7656…, crossid=EOS_0002…, ip=94.226.230.80, ping=13
 *
 * Идентификаторов три, и путать их нельзя:
 *   entityId — номер сущности в текущем мире. Живёт до выхода игрока и
 *              годится для команд, пока он в сети;
 *   platformId — идентификатор аккаунта платформы (Steam_…);
 *   crossId  — кроссплатформенный идентификатор (EOS_…). Именно он и
 *              platformId принимаются `ban add` для игроков НЕ в сети.
 *
 * Любой из трёх сервер вправе отдать как «<unknown>» — например, когда
 * клиент отвалился в момент выполнения команды. Такие поля здесь null.
 */
export interface SevenDaysPlayerDto {
  entityId: number;
  name: string;
  platformId: string | null;
  crossId: string | null;
  ip: string | null;
  ping: number | null;
  health: number | null;
  deaths: number | null;
  /** Убито зомби — в этой игре это основная мера прогресса. */
  zombieKills: number | null;
  /** Убито игроков. На PvE-сервере ненулевое значение — повод посмотреть. */
  playerKills: number | null;
  score: number | null;
  level: number | null;
  /** Координаты в мире: у 7DTD их три, высота значима (подземелья, башни). */
  position: { x: number; y: number; z: number } | null;
}

export interface SevenDaysPlayersResponse {
  players: SevenDaysPlayerDto[];
  /** Число из строки «Total of N in the game» — сервер считает сам. */
  online: number;
}

/**
 * Бан 7 Days to Die — строка из `ban list`.
 *
 * В отличие от Palworld список банов отдаёт сам игровой сервер, поэтому
 * своей таблицы панель не заводит и миграции для этого модуля не нужны:
 * дублировать чужое состояние значит рано или поздно с ним разойтись.
 */
export interface SevenDaysBanDto {
  /** Идентификатор, по которому бан снимается: platform id или cross id. */
  id: string;
  /** Отображаемое имя, если сервер его запомнил. */
  displayName: string | null;
  /** До какого момента, как это напечатал сервер. */
  until: string | null;
  reason: string | null;
}

/** Запись белого списка — строка из `whitelist list`. */
export interface SevenDaysWhitelistEntryDto {
  id: string;
  displayName: string | null;
}

/**
 * Единицы длительности бана. Заданы игрой (ConsoleCmdBan), и список
 * закрытый: на чужое значение сервер отвечает «is not an allowed duration
 * unit», то есть отказом уже после отправки.
 *
 * «Навсегда» в игре нет — бессрочный бан выражается большим сроком.
 */
export const SEVENDAYS_BAN_UNITS = ['minutes', 'hours', 'days', 'weeks', 'months', 'years'] as const;
export type SevenDaysBanUnit = (typeof SEVENDAYS_BAN_UNITS)[number];

/**
 * Состояние сервера: время в игре и версия.
 *
 * Аналога TPS у 7DTD нет — сервер не тикает фиксированной частотой и наружу
 * такой показатель не отдаёт. Зато отдаёт игровое время, а оно здесь важнее
 * обычного: на седьмой день приходит орда, и «какой сегодня день» —
 * первое, что спрашивают.
 */
export interface SevenDaysStateDto {
  available: boolean;
  /** Причина недоступности — показывается как есть. */
  reason?: string;
  /** Номер игрового дня. */
  day?: number | null;
  /** Время суток в игре, «19:53». */
  time?: string | null;
  /** До кровавой луны, в игровых днях. null — посчитать не удалось. */
  daysToBloodMoon?: number | null;
  version?: string | null;
  onlineCount?: number | null;
}

/** Флаги настройки подключения. Ни адрес, ни пароль наружу не уезжают. */
export interface SevenDaysConfigStatusDto {
  telnetConfigured: boolean;
  /** Последняя успешная команда, ISO-строка. */
  lastSeenAt: string | null;
}

/**
 * Быстрое действие — закрытый набор, а не произвольная команда.
 *
 * Произвольную команду можно выполнить в консоли: она есть у модуля как
 * возможность ядра. Здесь — то, что делают часто и хочется в одно нажатие.
 */
export interface SevenDaysActionDto {
  id: string;
  label: string;
  description: string;
  /** Право, без которого действие не показывается и не выполняется. */
  permission: string;
  /** Поля ввода, если действию нужны аргументы. */
  args: { name: string; label: string; placeholder?: string; required: boolean }[];
  /** true — действие заметно для игроков и подтверждается отдельно. */
  destructive?: boolean;
}

export const SEVENDAYS_PERMISSIONS = {
  playersView: 'sevendays.players.view',
  kick: 'sevendays.kick',
  ban: 'sevendays.ban',
  pardon: 'sevendays.ban.pardon',
  whitelist: 'sevendays.whitelist',
  quickActions: 'sevendays.quick-actions',
  shutdown: 'sevendays.shutdown',
  configure: 'sevendays.configure',
} as const;

/**
 * Кровавая луна приходит каждый седьмой игровой день — это правило самой
 * игры, по нему она и названа. Считается от текущего дня, а не хранится:
 * настройка частоты у сервера есть (BloodMoonFrequency), но через консоль
 * она не читается, и выдумывать её значение нельзя.
 */
export const SEVENDAYS_BLOOD_MOON_EVERY = 7;

/** Сколько игровых дней осталось до ближайшей кровавой луны. */
export function daysToBloodMoon(day: number, every = SEVENDAYS_BLOOD_MOON_EVERY): number {
  if (!Number.isFinite(day) || day <= 0 || every <= 0) return 0;
  const remainder = day % every;
  // День, кратный семи, — это и есть ночь орды: ноль, а не семь.
  return remainder === 0 ? 0 : every - remainder;
}
