/**
 * Контракт модуля Palworld между apps/api и apps/web.
 *
 * ПРО ПРОТОКОЛ. Palworld умеет Source RCON — тот же, что и Minecraft, и его
 * транспорт можно было бы переиспользовать. Но Pocketpair пометил RCON
 * устаревшим и объявил, что в одном из следующих обновлений он перестанет
 * работать, рекомендуя вместо него собственный REST API. Строить модуль на
 * транспорте с объявленной датой смерти не стоит, поэтому здесь REST:
 * HTTP + JSON, Basic-аутентификация, порт по умолчанию 8212.
 *
 * ВАЖНО: ни адрес сервера, ни пароль администратора здесь не появляются —
 * они не покидают бэкенд, наружу отдаются только флаги «настроено/нет».
 */

/**
 * Игрок Palworld — как его отдаёт GET /v1/api/players.
 *
 * Идентификаторов два, и путать их нельзя:
 *   userId   — идентификатор аккаунта платформы (напр. steam_0110000...),
 *              именно он принимается командами kick/ban/unban;
 *   playerId — идентификатор персонажа в текущем мире, для команд не годится.
 */
export interface PalworldPlayerDto {
  name: string;
  /** Ключ для kick/ban/unban. null — сервер не отдал (ломаный ответ). */
  userId: string | null;
  playerId: string | null;
  ping: number | null;
  /** Уровень персонажа. */
  level: number | null;
  /** Координаты на карте: у Palworld их две, высоты в ответе нет. */
  position: { x: number; y: number } | null;
}

export interface PalworldPlayersResponse {
  players: PalworldPlayerDto[];
  online: number;
  /** Из метрик сервера; null — метрики получить не удалось. */
  max: number | null;
}

/**
 * Состояние сервера: GET /v1/api/info + GET /v1/api/metrics.
 *
 * Аналог TPS/MSPT из Minecraft, но показатели у Palworld свои: сервер
 * рисует кадры, а не тики, поэтому здесь FPS и время кадра.
 */
export interface PalworldServerStateDto {
  available: boolean;
  /** Причина недоступности — показывается как есть. */
  reason?: string;
  serverName?: string;
  version?: string;
  description?: string;
  /** Кадров в секунду. Норма — около 60, ниже 30 игроки замечают. */
  fps?: number | null;
  /** Время кадра в миллисекундах. Бюджет при 60 fps — 16.7 мс. */
  frameTimeMs?: number | null;
  onlineCount?: number | null;
  maxPlayers?: number | null;
  /** Аптайм сервера в секундах. */
  uptimeSeconds?: number | null;
}

/**
 * Бан Palworld.
 *
 * REST API умеет банить и разбанивать, но НЕ умеет отдавать список банов —
 * поэтому список ведёт панель: причина, кто забанил и когда. Без своей
 * таблицы вкладка банов могла бы только «забанить в пустоту».
 */
export interface PalworldBanDto {
  id: string;
  serverId: string;
  playerName: string;
  /** userId платформы — то, чем оперирует сам сервер. */
  userId: string;
  reason: string;
  createdAt: string;
  createdByName: string | null;
  pardonedAt: string | null;
  pardonedByName: string | null;
  active: boolean;
}

/** Статус подключения к REST API. Ни адреса, ни пароля — только флаги. */
export interface PalworldConfigStatusDto {
  /** Заданы ли адрес и пароль администратора. */
  configured: boolean;
  /** Последний успешный ответ сервера, ISO-строка. */
  lastSeenAt: string | null;
}

export interface PalworldCommandResultDto {
  ok: boolean;
  /** Короткое человеческое подтверждение — сервер тела ответа не возвращает. */
  message: string;
}

/**
 * Быстрое действие Palworld.
 *
 * У Palworld нет консоли команд в REST API: набор действий закрытый и задан
 * самим API (объявление, сохранение мира, остановка с предупреждением).
 * Поэтому здесь не шаблоны команд, как в Minecraft, а перечень эндпоинтов.
 */
export interface PalworldQuickActionDto {
  id: string;
  label: string;
  description: string;
  permission: string;
  args: PalworldQuickActionArg[];
  /** Заметное для игроков действие — панель спросит подтверждение. */
  destructive: boolean;
}

export interface PalworldQuickActionArg {
  name: string;
  label: string;
  required: boolean;
  placeholder?: string;
  /** Числовое поле (секунды до остановки). */
  kind?: 'number';
}

export const PALWORLD_PERMISSIONS = {
  playersView: 'palworld.players.view',
  kick: 'palworld.kick',
  ban: 'palworld.ban',
  pardon: 'palworld.ban.pardon',
  quickActions: 'palworld.quick-actions',
  /** Остановка сервера через API — отдельно от обычных действий. */
  shutdown: 'palworld.shutdown',
  configure: 'palworld.configure',
} as const;
