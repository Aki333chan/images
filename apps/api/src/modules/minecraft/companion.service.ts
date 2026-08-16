import { Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import type {
  MinecraftInventoryItemDto,
  MinecraftInventoryResponse,
  MinecraftPermissionChangeDto,
  MinecraftPermissionsDto,
  MinecraftPlayerDto,
} from '@aurum/shared';
import { MinecraftConfigService } from './minecraft-config.service';

/** Ссылка на инструкцию по установке плагина. */
export const COMPANION_DOCS_URL =
  'https://github.com/Aki333chan/images/blob/main/docs/companion.md';

/** Сырые ответы плагина (см. companion-plugin/core/.../PayloadWriter.java). */
interface RawPlayer {
  uuid?: string;
  name?: string;
  health?: number;
  maxHealth?: number;
  world?: string;
  x?: number;
  y?: number;
  z?: number;
  ping?: number;
}

interface RawItem {
  slot?: number;
  id?: string;
  count?: number;
  displayName?: string | null;
  enchantments?: Record<string, number>;
  lore?: string[];
}

interface RawInventory {
  items?: RawItem[];
  armor?: RawItem[];
  offhand?: RawItem | null;
}

/**
 * Клиент companion-плагина: даёт структурированные данные, которых нет в RCON
 * (UUID, пинг, координаты, инвентарь). Плагин опционален — без него модуль
 * работает на чистом RCON, а инвентарь показывает инструкцию по установке.
 *
 * Адрес и токен плагина не попадают ни в ответы API, ни в логи.
 */
@Injectable()
export class CompanionService {
  private readonly logger = new Logger(CompanionService.name);

  constructor(private readonly config: MinecraftConfigService) {}

  async isConfigured(serverId: string): Promise<boolean> {
    return !!(await this.config.read(serverId)).companion;
  }

  private async call<T>(serverId: string, path: string): Promise<T | null> {
    const result = await this.callRaw<T>(serverId, path);
    return result.ok ? result.body : null;
  }

  /**
   * Как call, но с кодом ответа и телом ошибки.
   *
   * Нужен там, где отказ информативен: плагин отвечает машиночитаемым кодом
   * («нет LuckPerms», «нет данных офлайн»), и схлопывать это в null значит
   * терять единственное, что панель может показать человеку.
   */
  private async callRaw<T>(
    serverId: string,
    path: string,
    init?: { method?: 'GET' | 'POST'; body?: unknown },
  ): Promise<
    | { ok: true; body: T }
    | { ok: false; status: number | null; code: string | null; error: string | null }
  > {
    const creds = await this.config.read(serverId);
    if (!creds.companion) return { ok: false, status: null, code: null, error: null };
    try {
      const res = await request(`${creds.companion.baseUrl}${path}`, {
        method: init?.method ?? 'GET',
        headers: {
          authorization: `Bearer ${creds.companion.token}`,
          accept: 'application/json',
          ...(init?.body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: init?.body === undefined ? undefined : JSON.stringify(init.body),
        headersTimeout: 6000,
        bodyTimeout: 6000,
      });
      const text = await res.body.text();
      if (res.statusCode >= 400) {
        // Ни адреса, ни токена в сообщении — только код ответа.
        this.logger.warn(`Companion-плагин сервера ${serverId} ответил ${res.statusCode}`);
        const parsed = safeJson(text);
        return {
          ok: false,
          status: res.statusCode,
          code: typeof parsed?.code === 'string' ? parsed.code : null,
          error: typeof parsed?.error === 'string' ? parsed.error : null,
        };
      }
      return { ok: true, body: (text ? JSON.parse(text) : {}) as T };
    } catch (e) {
      this.logger.warn(`Companion-плагин сервера ${serverId} недоступен: ${(e as Error).message}`);
      return { ok: false, status: null, code: null, error: null };
    }
  }

  /** Установленные на сервере плагины; null — companion не настроен или молчит. */
  async getInstalledPlugins(
    serverId: string,
  ): Promise<{ name: string; version: string; enabled: boolean }[] | null> {
    const data = await this.call<{ plugins?: RawPlugin[] }>(serverId, '/plugins');
    if (!data?.plugins) return null;
    return data.plugins
      .filter((p): p is RawPlugin & { name: string } => typeof p.name === 'string')
      .map((p) => ({
        name: p.name,
        version: typeof p.version === 'string' ? p.version : '—',
        enabled: p.enabled !== false,
      }));
  }

  /**
   * Настоящее автодополнение от Bukkit: то же, что видит игрок по Tab в игре,
   * включая команды и аргументы сторонних плагинов.
   *
   * null — companion-плагин не настроен или не ответил; тогда вызывающая
   * сторона остаётся на статическом словаре, а не показывает ошибку: сломанное
   * автодополнение не должно мешать вводить команды руками.
   */
  async complete(serverId: string, line: string): Promise<string[] | null> {
    const data = await this.call<{ suggestions?: unknown }>(
      serverId,
      `/complete?line=${encodeURIComponent(line)}`,
    );
    if (!Array.isArray(data?.suggestions)) return null;
    return data.suggestions.filter((s): s is string => typeof s === 'string');
  }

  /** Права игрока через LuckPerms. */
  async getPermissions(serverId: string, uuid: string): Promise<MinecraftPermissionsDto> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        code: 'no-companion',
        reason: 'Для работы с правами нужен companion-плагин на игровом сервере',
      };
    }
    const result = await this.callRaw<RawPermissions>(serverId, `/players/${uuid}/permissions`);
    if (!result.ok) return permissionsFailure(result.code, result.error);
    return {
      available: true,
      primaryGroup: result.body.primaryGroup ?? 'default',
      groups: Array.isArray(result.body.groups) ? result.body.groups : [],
      permissions: Array.isArray(result.body.permissions)
        ? result.body.permissions.map((n) => ({ permission: n.permission, value: n.value !== false }))
        : [],
    };
  }

  /** Одно изменение прав; в ответе — актуальное состояние. */
  async changePermission(
    serverId: string,
    uuid: string,
    change: MinecraftPermissionChangeDto,
  ): Promise<MinecraftPermissionsDto> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        code: 'no-companion',
        reason: 'Для работы с правами нужен companion-плагин на игровом сервере',
      };
    }
    const result = await this.callRaw<RawPermissions>(serverId, `/players/${uuid}/permissions`, {
      method: 'POST',
      body: change,
    });
    if (!result.ok) return permissionsFailure(result.code, result.error);
    return {
      available: true,
      primaryGroup: result.body.primaryGroup ?? 'default',
      groups: Array.isArray(result.body.groups) ? result.body.groups : [],
      permissions: Array.isArray(result.body.permissions)
        ? result.body.permissions.map((n) => ({ permission: n.permission, value: n.value !== false }))
        : [],
    };
  }

  /** Список игроков с UUID, пингом и позицией; null — плагин не настроен или недоступен. */
  async getPlayers(serverId: string): Promise<MinecraftPlayerDto[] | null> {
    const data = await this.call<{ players: RawPlayer[] }>(serverId, '/players');
    if (!data?.players) return null;
    return data.players
      .filter((p): p is RawPlayer & { name: string } => typeof p.name === 'string')
      .map((p) => ({
        name: p.name,
        uuid: p.uuid ?? null,
        ping: numberOrNull(p.ping),
        health: numberOrNull(p.health),
        maxHealth: numberOrNull(p.maxHealth),
        world: p.world ?? null,
        position:
          typeof p.x === 'number' && typeof p.y === 'number' && typeof p.z === 'number'
            ? { x: p.x, y: p.y, z: p.z }
            : null,
      }));
  }

  async getInventory(serverId: string, player: string): Promise<MinecraftInventoryResponse> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        code: 'no-plugin',
        reason: 'Для просмотра инвентаря нужен companion-плагин на игровом сервере',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    // Плагин работает по UUID: ник в него не годится.
    const uuid = await this.resolveUuid(serverId, player);
    if (!uuid) {
      return {
        available: false,
        code: 'player-offline',
        reason: `Игрок ${player} сейчас не в сети — инвентарь доступен только для онлайн-игроков`,
      };
    }

    // Ник передаём параметром: он нужен InvSee++, чтобы поднять инвентарь
    // игрока, которого нет в сети.
    const result = await this.callRaw<RawInventory>(
      serverId,
      `/players/${uuid}/inventory?name=${encodeURIComponent(player)}`,
    );
    if (!result.ok) {
      if (result.code === 'offline-requires-invsee') {
        return {
          available: false,
          code: 'player-offline',
          reason:
            `Игрок ${player} не в сети. Чтобы смотреть инвентари офлайн-игроков, ` +
            'установите на сервер плагин InvSee++',
          docsUrl: COMPANION_DOCS_URL,
        };
      }
      if (result.code === 'offline-no-data') {
        return {
          available: false,
          code: 'player-offline',
          reason: `Игрок ${player} не в сети, и InvSee++ не нашёл сохранённых данных о нём`,
        };
      }
      return {
        available: false,
        code: 'plugin-unreachable',
        reason: 'Companion-плагин не ответил — проверьте, что сервер запущен и плагин активен',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    const data = result.body;
    return {
      available: true,
      player,
      items: (data.items ?? []).map(toItemDto),
      armor: (data.armor ?? []).map(toItemDto),
      offhand: data.offhand ? toItemDto(data.offhand) : null,
    };
  }

  /**
   * Ник -> UUID по текущему списку онлайн. Регистр ника не важен.
   *
   * Ограничение: для игрока, которого нет в сети, UUID отсюда не получить.
   * Инвентари офлайн через InvSee++ поэтому работают только для тех, чей
   * UUID панель уже знает — например, из открытой карточки игрока.
   */
  private async resolveUuid(serverId: string, player: string): Promise<string | null> {
    const players = await this.getPlayers(serverId);
    if (!players) return null;
    const match = players.find((p) => p.name.toLowerCase() === player.toLowerCase());
    return match?.uuid ?? null;
  }
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function toItemDto(raw: RawItem): MinecraftInventoryItemDto {
  return {
    slot: typeof raw.slot === 'number' ? raw.slot : 0,
    id: raw.id ?? 'minecraft:air',
    count: typeof raw.count === 'number' ? raw.count : 1,
    displayName: raw.displayName ?? null,
    enchantments: raw.enchantments ?? {},
    lore: raw.lore ?? [],
  };
}

interface RawPlugin {
  name?: string;
  version?: string;
  enabled?: boolean;
}

interface RawPermissions {
  primaryGroup?: string;
  groups?: string[];
  permissions?: { permission: string; value: boolean }[];
}

/** Разбор тела ошибки: плагин мог ответить и не-JSON. */
function safeJson(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * Отказ по правам в виде, пригодном для показа.
 *
 * Код от плагина сохраняем: «поставьте LuckPerms» и «плагин не ответил» —
 * разные ситуации, и интерфейс реагирует на них по-разному.
 */
function permissionsFailure(code: string | null, error: string | null): MinecraftPermissionsDto {
  if (code === 'requires-luckperms') {
    return {
      available: false,
      code: 'requires-luckperms',
      reason: 'Работа с правами требует плагина LuckPerms на игровом сервере',
    };
  }
  return {
    available: false,
    code: 'error',
    reason: error ?? 'Companion-плагин не ответил — проверьте, что сервер запущен и плагин активен',
  };
}
