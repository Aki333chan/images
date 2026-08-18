import type { PalworldPlayerDto } from '@aurum/shared';

/**
 * Разбор ответов REST API Palworld.
 *
 * Ответы приходят готовым JSON, а не текстом, как у RCON, — но доверять им
 * без проверки всё равно нельзя: версии сервера отличаются набором полей,
 * а моды и прокси (PalDefender и подобные) отдают свои варианты. Поэтому
 * разбор терпимый: чего не хватает — становится null, и это честно видно
 * в интерфейсе, а не превращается в NaN или «undefined» на экране.
 */

interface RawPlayer {
  name?: unknown;
  playerId?: unknown;
  userId?: unknown;
  ping?: unknown;
  level?: unknown;
  location_x?: unknown;
  location_y?: unknown;
}

/** Конечное число или null. Строку с числом тоже принимаем: версии разнятся. */
export function numberOrNull(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/** Непустая строка или null. */
export function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

/**
 * GET /v1/api/players.
 *
 * Игрок без имени отбрасывается: показывать в списке пустую строку хуже,
 * чем не показывать запись вовсе. Всё остальное может быть null.
 */
export function parsePlayers(body: unknown): PalworldPlayerDto[] {
  const raw = (body as { players?: unknown })?.players;
  if (!Array.isArray(raw)) return [];

  const players: PalworldPlayerDto[] = [];
  for (const item of raw) {
    if (typeof item !== 'object' || item === null) continue;
    const player = item as RawPlayer;
    const name = stringOrNull(player.name);
    if (!name) continue;

    const x = numberOrNull(player.location_x);
    const y = numberOrNull(player.location_y);
    players.push({
      name,
      userId: stringOrNull(player.userId),
      playerId: stringOrNull(player.playerId),
      ping: numberOrNull(player.ping),
      level: numberOrNull(player.level),
      // Координаты имеют смысл только парой: одна половина бесполезна.
      position: x !== null && y !== null ? { x, y } : null,
    });
  }
  return players;
}

export interface ParsedMetrics {
  fps: number | null;
  frameTimeMs: number | null;
  onlineCount: number | null;
  maxPlayers: number | null;
  uptimeSeconds: number | null;
}

/** GET /v1/api/metrics. */
export function parseMetrics(body: unknown): ParsedMetrics {
  const raw = (body ?? {}) as Record<string, unknown>;
  return {
    fps: numberOrNull(raw.serverfps),
    frameTimeMs: numberOrNull(raw.serverframetime),
    onlineCount: numberOrNull(raw.currentplayernum),
    maxPlayers: numberOrNull(raw.maxplayernum),
    uptimeSeconds: numberOrNull(raw.uptime),
  };
}

export interface ParsedInfo {
  serverName: string | null;
  version: string | null;
  description: string | null;
}

/** GET /v1/api/info. */
export function parseInfo(body: unknown): ParsedInfo {
  const raw = (body ?? {}) as Record<string, unknown>;
  return {
    // Именно servername в одно слово — так называет поле сам сервер.
    serverName: stringOrNull(raw.servername),
    version: stringOrNull(raw.version),
    description: stringOrNull(raw.description),
  };
}

/**
 * Идентификатор игрока для kick/ban/unban.
 *
 * Сервер принимает userId вида `steam_0110000100000000`. Проверка нужна
 * потому, что значение приходит из интерфейса и уходит в тело запроса к
 * игровому серверу: пробелы и управляющие символы туда попасть не должны.
 * Диапазон символов намеренно широкий — платформы кроме Steam дают свои
 * форматы, и запрещать их наугад значит ломать работу на них.
 */
const USER_ID_RE = /^[A-Za-z0-9_:.-]{3,128}$/;

export function isValidUserId(value: string): boolean {
  return USER_ID_RE.test(value);
}

/**
 * Текст, уходящий игрокам (объявление, причина кика).
 *
 * У Palworld нет экранирования: сообщение уходит полем JSON, поэтому кавычки
 * безопасны — их закроет JSON.stringify. Вычищаем управляющие символы и
 * переводы строк (в игровом чате они всё равно не отображаются) и режем
 * длину, чтобы случайная вставка простыни не ушла на сервер целиком.
 */
export function sanitizeMessage(value: string, maxLength = 200): string {
  return (
    value
      // eslint-disable-next-line no-control-regex
      .replace(/[\x00-\x1f\x7f]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, maxLength)
  );
}
