import { Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import type { MinecraftInventoryResponse, MinecraftPlayerDto } from '@aurum/shared';
import { MinecraftConfigService } from './minecraft-config.service';

/** Ссылка на инструкцию по установке плагина (см. промт 3 — сам плагин). */
export const COMPANION_DOCS_URL = 'https://github.com/aki333chan/images/blob/main/docs/companion.md';

interface CompanionPlayer {
  name: string;
  uuid?: string;
  ping?: number;
}

/**
 * Клиент companion-плагина: даёт структурированные данные, которых нет в RCON
 * (UUID, пинг, инвентарь). Плагин опционален — при его отсутствии модуль
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
        // В сообщение не попадает ни адрес, ни токен.
        this.logger.warn(`Companion-плагин сервера ${serverId} ответил ${res.statusCode}`);
        return null;
      }
      return (await res.body.json()) as T;
    } catch (e) {
      this.logger.warn(`Companion-плагин сервера ${serverId} недоступен: ${(e as Error).message}`);
      return null;
    }
  }

  /** Список игроков с UUID и пингом; null — плагин не настроен или недоступен. */
  async getPlayers(serverId: string): Promise<MinecraftPlayerDto[] | null> {
    const data = await this.call<{ players: CompanionPlayer[] }>(serverId, '/players');
    if (!data?.players) return null;
    return data.players.map((p) => ({
      name: p.name,
      uuid: p.uuid ?? null,
      ping: typeof p.ping === 'number' ? p.ping : null,
    }));
  }

  async getInventory(serverId: string, player: string): Promise<MinecraftInventoryResponse> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        reason: 'Для просмотра инвентаря нужен companion-плагин на игровом сервере',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    const data = await this.call<Omit<MinecraftInventoryResponse, 'available'>>(
      serverId,
      `/inventory/${encodeURIComponent(player)}`,
    );
    if (!data) {
      return {
        available: false,
        reason: 'Companion-плагин не ответил — проверьте, что сервер запущен и плагин активен',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    return { available: true, player, ...data };
  }
}
