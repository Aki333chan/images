import {
  BadRequestException,
  Injectable,
  Logger,
  ServiceUnavailableException,
} from '@nestjs/common';
import {
  parseInventory,
  parseSavedPlayers,
  unavailableInventory,
  validateInventoryPlayerId,
} from './sevendays-inventory';
import { request } from 'undici';
import { SEVENDAYS_COMPANION_CAPABILITIES, type SevenDaysCompanionCapability } from '@aurum/shared';
import { SevenDaysConfigService } from './sevendays-config.service';

/**
 * Исходящее направление: панель → companion-мод.
 *
 * Мод слушает внутри приватного туннеля, и его адрес с токеном не покидают
 * бэкенд — как и пароль telnet-консоли. Порт мода наружу не выставляется:
 * он такой же чувствительный, как RCON.
 *
 * Мод не обязателен. Поэтому каждый метод здесь делится на два вида:
 * `require…` — вызывающий знает, что мод нужен, и хочет внятную ошибку;
 * `try…` — вызывающему достаточно «не получилось», потому что без мода
 * панель просто показывает меньше.
 */
@Injectable()
export class SevenDaysCompanionService {
  private readonly logger = new Logger(SevenDaysCompanionService.name);

  /** Мод отвечает мгновенно — он в той же сети и ничего не считает. */
  private static readonly TIMEOUT_MS = 4000;
  private static readonly MAX_RESPONSE_BYTES = 1024 * 1024;

  constructor(private readonly config: SevenDaysConfigService) {}

  private inventoryReads = 0;
  itemSearch(serverId: string, query: string): Promise<unknown> {
    return this.call(serverId, 'GET', `/items/${encodeURIComponent(query)}`, undefined, false);
  }
  itemDrop(serverId: string, playerId: string, payload: unknown): Promise<unknown> {
    return this.call(
      serverId,
      'POST',
      `/players/${encodeURIComponent(playerId)}/item-drop`,
      payload,
      false,
    );
  }
  savedStack(serverId: string, playerId: string, payload: unknown): Promise<unknown> {
    return this.call(
      serverId,
      'POST',
      `/players/${encodeURIComponent(playerId)}/saved-stack`,
      payload,
      false,
    );
  }
  async inventory(serverId: string, playerId: string, saved = false) {
    validateInventoryPlayerId(playerId);
    const source = saved ? 'saved_file' : 'client_snapshot';
    if (this.inventoryReads >= 4) throw new ServiceUnavailableException('inventory_busy');
    this.inventoryReads++;
    try {
      const ping = await this.ping(serverId);
      if (!ping) return unavailableInventory('mod_unavailable', source);
      if (
        !ping.compatible ||
        !ping.capabilities?.includes(saved ? 'inventory-saved-read' : 'inventory-read')
      )
        return unavailableInventory('mod_update', source);
      const inventory = parseInventory(
        await this.call<unknown>(
          serverId,
          'GET',
          `/players/${encodeURIComponent(playerId)}/${saved ? 'saved-inventory' : 'inventory'}`,
          undefined,
          false,
        ),
        source,
      );
      return {
        ...inventory,
        canReplace: saved && ping.capabilities?.includes('inventory-saved-replace') === true,
      };
    } finally {
      this.inventoryReads--;
    }
  }

  async savedPlayers(serverId: string, query: string, offset: number) {
    if (
      typeof query !== 'string' ||
      query.length > 80 ||
      !Number.isInteger(offset) ||
      offset < 0 ||
      offset > 10000
    )
      throw new BadRequestException('invalid_saved_players_query');
    if (this.inventoryReads >= 4) throw new ServiceUnavailableException('inventory_busy');
    this.inventoryReads++;
    try {
      const ping = await this.ping(serverId);
      if (!ping || !ping.compatible || !ping.capabilities?.includes('inventory-saved-read'))
        return {
          ready: false,
          players: [],
          hasMore: false,
          truncated: false,
          reason: !ping ? 'mod_unavailable' : 'mod_update',
        };
      return parseSavedPlayers(
        await this.call(
          serverId,
          'GET',
          `/saved-players/${encodeURIComponent(query || '_')}/${offset}`,
          undefined,
          false,
        ),
      );
    } finally {
      this.inventoryReads--;
    }
  }

  /**
   * Проверка связи: жив ли мод и какой у него контракт.
   *
   * Возвращает null вместо исключения, потому что «мода нет» — это обычное
   * состояние, а не ошибка: модуль рассчитан на голый сервер.
   */
  async ping(serverId: string): Promise<CompanionHandshake | null> {
    try {
      const body = await this.call<unknown>(serverId, 'GET', '/ping', undefined, false);
      const handshake = parseHandshake(body);
      if (!handshake) return null;
      await this.config.markCompanionSeen(serverId);
      return handshake;
    } catch (e) {
      this.logger.debug(`Companion не ответил: ${(e as Error).message}`);
      return null;
    }
  }

  /**
   * Состояние мира от самой игры.
   *
   * Ради двух полей это и нужно: идёт ли кровавая луна на самом деле (панель
   * без мода вынуждена считать «день кратен семи», хотя частота
   * настраивается) и FPS сервера.
   */
  async state(serverId: string): Promise<CompanionWorldState | null> {
    try {
      return await this.call<CompanionWorldState>(serverId, 'GET', '/state');
    } catch {
      return null;
    }
  }

  /**
   * Личное сообщение игроку в игровой чат.
   *
   * Ванильная консоль 7 Days to Die этого не умеет вовсе — есть только say
   * на весь сервер. Ответ модератора на жалобу иначе пришлось бы зачитывать
   * всему серверу.
   *
   * `delivered: false` означает «игрок не в сети» — это не ошибка: модератор
   * отвечает, когда удобно ему.
   */
  async sendPrivateMessage(serverId: string, playerId: string, text: string): Promise<boolean> {
    const body = await this.call<{ delivered?: boolean }>(
      serverId,
      'POST',
      `/players/${encodeURIComponent(playerId)}/message`,
      { text },
    );
    return body.delivered === true;
  }

  /** Fixed, validated map routes only; tile reads do not write lastSeenAt per image. */
  async mapRequest(serverId: string, path: string): Promise<unknown> {
    if (!/^\/map\/(info|markers|pois|tile\/\d+\/-?\d+\/-?\d+)$/.test(path))
      throw new BadRequestException('invalid_map_path');
    return this.call<unknown>(serverId, 'GET', path, undefined, false);
  }

  async zoneRequest(serverId: string, payload?: unknown): Promise<unknown> {
    const ping = await this.ping(serverId);
    if (!ping?.compatible || !ping.capabilities?.includes('zones-v2'))
      throw new BadRequestException('zones_mod_update_required');
    return this.call<unknown>(
      serverId,
      payload === undefined ? 'GET' : 'POST',
      '/zones',
      payload,
      false,
    );
  }

  private async call<T>(
    serverId: string,
    method: 'GET' | 'POST',
    path: string,
    payload?: unknown,
    markSeen = true,
  ): Promise<T> {
    const creds = await this.config.readCompanion(serverId);
    const url = `http://${creds.host}:${creds.port}${path}`;

    let text: string;
    let statusCode: number;
    const abort = new AbortController();
    const deadline = setTimeout(() => abort.abort(), SevenDaysCompanionService.TIMEOUT_MS);
    try {
      const response = await request(url, {
        method,
        headers: {
          authorization: `Bearer ${creds.token}`,
          ...(payload === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: payload === undefined ? undefined : JSON.stringify(payload),
        headersTimeout: SevenDaysCompanionService.TIMEOUT_MS,
        bodyTimeout: SevenDaysCompanionService.TIMEOUT_MS,
        signal: abort.signal,
        maxRedirections: 0,
      });
      statusCode = response.statusCode;
      const chunks: Buffer[] = [];
      let bytes = 0;
      try {
        for await (const chunk of response.body) {
          const data = Buffer.from(chunk);
          bytes += data.length;
          if (bytes > SevenDaysCompanionService.MAX_RESPONSE_BYTES) {
            throw new Error('response_limit');
          }
          chunks.push(data);
        }
        text = Buffer.concat(chunks, bytes).toString('utf8');
      } finally {
        response.body.destroy();
      }
    } catch {
      // В тексте ошибки undici бывает адрес — а он приватный и секретный,
      // поэтому саму ошибку наружу не пускаем и не связываем с ответом.
      throw new BadRequestException('Companion-мод не отвечает');
    } finally {
      clearTimeout(deadline);
    }

    if (statusCode < 200 || statusCode >= 300) {
      // Причину мода показываем как есть: она написана для человека.
      const reason = safeMessage(text) ?? `код ${statusCode}`;
      throw new BadRequestException(`Companion-мод отказал: ${reason}`);
    }

    let parsed: T;
    try {
      parsed = (text ? JSON.parse(text) : {}) as T;
    } catch {
      throw new BadRequestException('Некорректный ответ companion-мода');
    }
    if (markSeen) await this.config.markCompanionSeen(serverId);
    return parsed;
  }
}

export interface CompanionHandshake {
  version: string;
  contract: string;
  compatible: boolean;
  capabilities: SevenDaysCompanionCapability[] | null;
  language: 'en' | 'ru' | 'pl' | null;
}

/** Validate identity before showing an arbitrary HTTP service as our companion. */
export function parseHandshake(value: unknown): CompanionHandshake | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  if (
    body.ok !== true ||
    body.mod !== 'aurum-companion' ||
    typeof body.version !== 'string' ||
    !body.version ||
    body.version.length > 64 ||
    typeof body.contract !== 'string' ||
    !body.contract ||
    body.contract.length > 32
  )
    return null;
  if (
    body.capabilities !== undefined &&
    (!Array.isArray(body.capabilities) ||
      body.capabilities.length > 64 ||
      body.capabilities.some((c) => typeof c !== 'string' || c.length > 64))
  )
    return null;
  const compatible = body.contract === '1';
  return {
    version: body.version,
    contract: body.contract,
    compatible,
    capabilities: !compatible
      ? []
      : body.capabilities === undefined
        ? null
        : SEVENDAYS_COMPANION_CAPABILITIES.filter((c) =>
            (body.capabilities as string[]).includes(c),
          ),
    language:
      body.language === 'en' || body.language === 'ru' || body.language === 'pl'
        ? body.language
        : null,
  };
}

/** Состояние мира, как его отдаёт мод. */
export interface CompanionWorldState {
  ready?: boolean;
  day: number;
  hour: number;
  minute: number;
  /** Идёт ли орда прямо сейчас — факт от игры, а не расчёт по номеру дня. */
  bloodMoonActive: boolean;
  /** Частота орды из настроек сервера. null — сервер не сказал. */
  bloodMoonFrequency: number | null;
  bloodMoonRange?: number | null;
  bloodMoonNextDay?: number | null;
  fps: number;
  zombies: number;
  maxZombies: number;
  animals: number;
  onlinePlayers: number;
  maxPlayers: number;
  version: string | null;
}

/** Достаёт поле error из ответа мода, не падая на не-JSON. */
function safeMessage(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { error?: string };
    return typeof parsed.error === 'string' ? parsed.error : null;
  } catch {
    return null;
  }
}
