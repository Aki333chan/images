import { Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import type {
  MinecraftInventoryItemDto,
  MinecraftInventoryResponse,
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
    const creds = await this.config.read(serverId);
    if (!creds.companion) return null;
    try {
      const res = await request(`${creds.companion.baseUrl}${path}`, {
        method: 'GET',
        headers: {
          authorization: `Bearer ${creds.companion.token}`,
          accept: 'application/json',
        },
        headersTimeout: 4000,
        bodyTimeout: 4000,
      });
      if (res.statusCode >= 400) {
        // Ни адреса, ни токена в сообщении — только код ответа.
        this.logger.warn(`Companion-плагин сервера ${serverId} ответил ${res.statusCode}`);
        return null;
      }
      return (await res.body.json()) as T;
    } catch (e) {
      this.logger.warn(`Companion-плагин сервера ${serverId} недоступен: ${(e as Error).message}`);
      return null;
    }
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

    const data = await this.call<RawInventory>(serverId, `/players/${uuid}/inventory`);
    if (!data) {
      return {
        available: false,
        code: 'plugin-unreachable',
        reason: 'Companion-плагин не ответил — проверьте, что сервер запущен и плагин активен',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    return {
      available: true,
      player,
      items: (data.items ?? []).map(toItemDto),
      armor: (data.armor ?? []).map(toItemDto),
      offhand: data.offhand ? toItemDto(data.offhand) : null,
    };
  }

  /** Ник -> UUID по текущему списку онлайн. Регистр ника не важен. */
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
