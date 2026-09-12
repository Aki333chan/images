import {
  Injectable,
  Logger,
  NotFoundException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { request } from 'undici';
import type {
  MinecraftBalanceChangeDto,
  MinecraftBalanceDto,
  MinecraftPasswordResetDto,
  MinecraftEconomyDto,
  MinecraftEconomyAuditDto,
  MinecraftEconomyAuditSection,
  MinecraftEconomyRuleApplyDto,
  MinecraftEconomyRuleDto,
  MinecraftEconomyRulePreviewDto,
  MinecraftEconomyRuleType,
  MinecraftGiveItemDto,
  MinecraftGiveResponse,
  MinecraftGuildBonusDto,
  MinecraftGuildDto,
  MinecraftGuildMembershipDto,
  MinecraftGuildRank,
  MinecraftInventoryClearDto,
  MinecraftInventoryItemDto,
  MinecraftInventoryResponse,
  MinecraftKnownPlayerDto,
  MinecraftKnownPlayersResponse,
  MinecraftPlayerIpsResponse,
  MinecraftPermissionChangeDto,
  MinecraftPermissionsDto,
  MinecraftPlayerDto,
  MinecraftPlayerJailDto,
} from '@aurum/shared';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

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

interface RawKnownPlayer {
  uuid?: string;
  name?: string;
  alias?: string | null;
  op?: boolean;
  online?: boolean;
  registered?: boolean | null;
  lastSeen?: number;
}

interface RawKnownPlayers {
  players?: RawKnownPlayer[];
  total?: number;
  authAvailable?: boolean;
}

interface RawIpRecord {
  ip?: string;
  firstSeen?: number;
  lastSeen?: number;
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

interface RawGiveResult {
  id?: string;
  requested?: number;
  given?: number;
  error?: string | null;
}

interface RawGuildBonus {
  type?: string;
  title?: string;
  magnitude?: number;
  multiplier?: boolean;
  /** 0 — постоянный. */
  expiresAt?: number;
  grantedBy?: string;
  grantedAt?: number;
}

interface RawGuildMember {
  uuid?: string;
  name?: string;
  rank?: string;
  joinedAt?: number;
}

interface RawGuild {
  id?: number;
  name?: string;
  tag?: string;
  leaderUuid?: string;
  leaderName?: string;
  memberCount?: number;
  bankBalance?: number;
  createdAt?: number;
  members?: RawGuildMember[];
}

interface RawGuildMembership {
  membership?: {
    guildId?: number;
    guildName?: string;
    guildTag?: string;
    rank?: string;
    joinedAt?: number;
  } | null;
}

interface RawJailedPlayer {
  uuid?: string;
  name?: string;
  jail?: string;
  /** Момент выхода, миллисекунды эпохи; 0 — до отмены. Момента посадки нет. */
  releaseAt?: number;
}

interface RawPlayerJail {
  known?: boolean;
  jailed?: boolean;
  jail?: string;
  releaseAt?: number;
  online?: boolean;
}

interface RawJails {
  available?: boolean;
  jails?: string[];
  jailed?: RawJailedPlayer[];
}

interface RawGuildOutcome {
  ok?: boolean;
  /** Ключ сообщения. Плагин присылает именно ключ — см. GuildOutcome. */
  message?: string;
  values?: Record<string, string>;
  keys?: Record<string, string>;
}

/**
 * Ответ плагина гильдий, разобранный.
 *
 * Ключ и подстановки едут дальше врозь: переводит их либо фильтр ошибок (при
 * отказе), либо сам браузер (при успехе), и оба знают язык читателя. Панель
 * тут ничего не собирает — она бы собрала не на том языке.
 */
export interface GuildOutcome {
  ok: boolean;
  message: string;
  values: Record<string, string>;
  keys: Record<string, string>;
}

/** Ответ companion про тюрьмы, разобранный. */
export interface CompanionJails {
  available: boolean;
  jails: string[];
  jailed: { uuid: string; name: string; jail: string; releaseAt: number }[];
}

function outcome(
  ok: boolean,
  message: string,
  values: Record<string, string> = {},
  keys: Record<string, string> = {},
): GuildOutcome {
  return { ok, message, values, keys };
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
    init?: { method?: 'GET' | 'POST' | 'DELETE'; body?: unknown; timeoutMs?: number },
  ): Promise<
    | { ok: true; body: T }
    | {
        ok: false;
        status: number | null;
        code: string | null;
        error: string | null;
        // Разобранное тело отказа целиком. Нужно там, где плагин объясняет
        // отказ не кодом, а полями: гильдии отвечают на 409 тем же ключом и
        // подстановками, что и на успех, и терять их здесь значило бы
        // показать «сервер промолчал» вместо «такой гильдии нет».
        body: Record<string, unknown> | null;
      }
  > {
    const creds = await this.config.read(serverId);
    if (!creds.companion) return { ok: false, status: null, code: null, error: null, body: null };
    try {
      const res = await request(`${creds.companion.baseUrl}${path}`, {
        method: init?.method ?? 'GET',
        headers: {
          authorization: `Bearer ${creds.companion.token}`,
          accept: 'application/json',
          ...(init?.body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: init?.body === undefined ? undefined : JSON.stringify(init.body),
        headersTimeout: init?.timeoutMs ?? 6000,
        bodyTimeout: init?.timeoutMs ?? 6000,
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
          body: parsed ?? null,
        };
      }
      return { ok: true, body: (text ? JSON.parse(text) : {}) as T };
    } catch (e) {
      this.logger.warn(`Companion-плагин сервера ${serverId} недоступен: ${(e as Error).message}`);
      return { ok: false, status: null, code: null, error: null, body: null };
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
   * Горячее включение/выключение плагина через PluginManager сервера.
   *
   * Отказ приходит с кодом 409 и человеческой причиной — её и показываем,
   * не подменяя своей: «плагин отказался переключиться» и «плагина нет»
   * требуют от человека разных действий.
   */
  async setPluginEnabled(
    serverId: string,
    pluginName: string,
    enabled: boolean,
  ): Promise<{ ok: boolean; enabled?: boolean; error?: string }> {
    if (!(await this.isConfigured(serverId))) {
      return {
        ok: false,
        error: 'mc.err.toggleNeedsCompanion',
      };
    }
    const result = await this.callRaw<{ ok?: boolean; enabled?: boolean }>(
      serverId,
      `/plugins/${encodeURIComponent(pluginName)}/enabled`,
      { method: 'POST', body: { enabled } },
    );
    if (!result.ok) {
      return {
        ok: false,
        error: result.error ?? 'mc.err.companionSilent',
      };
    }
    return { ok: true, enabled: result.body.enabled !== false };
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
        reason: 'mc.err.permsNeedCompanion',
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
        reason: 'mc.err.permsNeedCompanion',
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

  /**
   * Все, кто когда-либо заходил на сервер.
   *
   * Постранично и с фильтром по нику — намеренно. Список растёт вместе с
   * возрастом сервера, а игровой сервер читает ник каждой записи отдельным
   * обращением к диску; «отдай всех сразу» на живом сервере с тысячами
   * игроков означает заметную паузу в игре.
   */
  async getKnownPlayers(
    serverId: string,
    options: { query?: string; offset?: number; limit?: number } = {},
  ): Promise<MinecraftKnownPlayersResponse> {
    const empty = { players: [], total: 0, authAvailable: false };
    if (!(await this.isConfigured(serverId))) {
      return {
        ...empty,
        available: false,
        code: 'no-companion',
        reason: 'mc.err.knownNeedsCompanion',
        docsUrl: COMPANION_DOCS_URL,
      };
    }

    const params = new URLSearchParams();
    if (options.query) params.set('query', options.query);
    if (options.offset) params.set('offset', String(options.offset));
    if (options.limit) params.set('limit', String(options.limit));
    const suffix = params.toString() ? `?${params}` : '';

    const data = await this.call<RawKnownPlayers>(serverId, `/players/known${suffix}`);
    if (!data) {
      return {
        ...empty,
        available: false,
        code: 'plugin-unreachable',
        reason: 'mc.err.companionSilent',
        docsUrl: COMPANION_DOCS_URL,
      };
    }

    return {
      available: true,
      players: (data.players ?? [])
        .filter((p): p is RawKnownPlayer & { uuid: string; name: string } =>
          typeof p.uuid === 'string' && typeof p.name === 'string',
        )
        .map(toKnownPlayer),
      total: numberOr(data.total, 0),
      authAvailable: data.authAvailable === true,
    };
  }

  /**
   * Известные адреса игрока.
   *
   * Историю ведёт плагин авторизации, companion лишь спрашивает у него.
   * Пустой список без плагина — не ошибка: ванильный сервер адреса не
   * хранит вовсе, и панель должна сказать об этом словами, а не молчать.
   */
  async getIpHistory(serverId: string, uuid: string): Promise<MinecraftPlayerIpsResponse> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        addresses: [],
        code: 'no-companion',
        reason: 'mc.err.ipsNeedCompanion',
        docsUrl: COMPANION_DOCS_URL,
      };
    }

    const data = await this.call<{ addresses?: RawIpRecord[] }>(serverId, `/players/${uuid}/ips`);
    if (!data) {
      return {
        available: false,
        addresses: [],
        code: 'plugin-unreachable',
        reason: 'mc.err.companionSilent',
        docsUrl: COMPANION_DOCS_URL,
      };
    }

    return {
      available: true,
      addresses: (data.addresses ?? [])
        .filter((r): r is RawIpRecord & { ip: string } => typeof r.ip === 'string')
        .map((r) => ({
          ip: r.ip,
          firstSeen: new Date(numberOr(r.firstSeen, 0)).toISOString(),
          lastSeen: new Date(numberOr(r.lastSeen, 0)).toISOString(),
        })),
    };
  }

  async getInventory(serverId: string, player: string): Promise<MinecraftInventoryResponse> {
    if (!(await this.isConfigured(serverId))) {
      return {
        available: false,
        code: 'no-plugin',
        reason: 'mc.err.invNeedCompanion',
        docsUrl: COMPANION_DOCS_URL,
      };
    }
    // Плагин работает по UUID: ник в него не годится.
    const uuid = await this.resolveUuid(serverId, player);
    if (!uuid) {
      return {
        available: false,
        code: 'player-offline',
        reason: 'mc.err.invOnlineOnly',
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
            'mc.err.invOfflineNeedsInvsee',
          docsUrl: COMPANION_DOCS_URL,
        };
      }
      if (result.code === 'offline-no-data') {
        return {
          available: false,
          code: 'player-offline',
          reason: 'mc.err.invOfflineNoData',
        };
      }
      return {
        available: false,
        code: 'plugin-unreachable',
        reason: 'mc.err.companionSilent',
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
   * Выдать игроку список предметов.
   *
   * Идентификаторы проверяет игровой сервер, а не панель: перечень материалов
   * зависит от версии и установленных модов, и зашитый сюда список устарел бы
   * к следующему обновлению. Поэтому неизвестный предмет — это не 400, а
   * строка результата с причиной; остальные строки при этом выдаются.
   */
  async giveItems(
    serverId: string,
    player: string,
    items: MinecraftGiveItemDto[],
  ): Promise<MinecraftGiveResponse> {
    const uuid = await this.resolveTarget(serverId, player);
    // Ник параметром: он нужен InvSee++, если игрока нет в сети.
    const result = await this.callRaw<{ results?: RawGiveResult[] }>(
      serverId,
      `/players/${uuid}/inventory/give?name=${encodeURIComponent(player)}`,
      { method: 'POST', body: { items } },
    );
    if (!result.ok) throw this.inventoryEditFailure(result, player);
    return {
      results: (result.body.results ?? []).map((r) => ({
        id: typeof r.id === 'string' ? r.id : '—',
        requested: numberOr(r.requested, 0),
        given: numberOr(r.given, 0),
        error: typeof r.error === 'string' ? r.error : null,
      })),
    };
  }

  /**
   * Очистить выбранные слоты или инвентарь целиком.
   *
   * Полная очистка передаётся отдельным флагом `all`, и пустой выбор плагин
   * отвергает: разница необратимая, и поле, потерянное по дороге, не должно
   * оборачиваться стёртым инвентарём.
   */
  async clearInventory(
    serverId: string,
    player: string,
    selection: MinecraftInventoryClearDto,
  ): Promise<void> {
    const uuid = await this.resolveTarget(serverId, player);
    const result = await this.callRaw<unknown>(
      serverId,
      `/players/${uuid}/inventory/clear?name=${encodeURIComponent(player)}`,
      { method: 'POST', body: selection },
    );
    if (!result.ok) throw this.inventoryEditFailure(result, player);
  }

  /**
   * UUID игрока, инвентарь которого собираются менять.
   *
   * Быть в сети больше не требуется: сохранённый инвентарь правится через
   * InvSee++ — он же и записывает изменения обратно в файл игрока, так что
   * следующий вход их не затирает, а подхватывает. Игровой сервер сам
   * скажет, если InvSee++ не стоит или такого игрока он не помнит, —
   * ответ приедет сюда кодом offline-requires-invsee/offline-no-data.
   */
  private async resolveTarget(serverId: string, player: string): Promise<string> {
    if (!(await this.isConfigured(serverId))) {
      throw new ServiceUnavailableException('mc.err.invEditNeedCompanion');
    }
    const uuid = await this.resolveUuid(serverId, player);
    if (!uuid) {
      throw new NotFoundException({
        message: 'mc.err.playerUnknown',
        i18nValues: { player },
      });
    }
    return uuid;
  }

  /** Отказ плагина на правку инвентаря — своими словами, без адресов и токенов. */
  private inventoryEditFailure(
    result: { status: number | null; code: string | null; error: string | null },
    player: string,
  ): Error {
    if (result.code === 'offline-requires-invsee') {
      return new NotFoundException({
        message: 'mc.err.editOfflineNeedsInvsee',
        i18nValues: { player },
      });
    }
    if (result.code === 'offline-no-data') {
      return new NotFoundException({
        message: 'mc.err.editOfflineNoData',
        i18nValues: { player },
      });
    }
    if (result.code === 'unknown-item') {
      return new NotFoundException('mc.err.unknownItem');
    }
    if (result.code === 'player-offline') {
      return new NotFoundException({
        message: 'mc.err.playerLeft',
        i18nValues: { player },
      });
    }
    if (result.error) return new ServiceUnavailableException(result.error);
    return new ServiceUnavailableException('mc.err.companionSilent');
  }

  // ---------------------------------------------------------- Экономика
  //
  // Панель экосистемы работает строго через active ledger AurumCore. Vault
  // остаётся совместимым мостом для сторонних игровых плагинов, но не является
  // запасным источником данных панели: молчаливый fallback мог бы показать
  // или изменить другой баланс.

  /** Баланс игрока по UUID. Работает и для тех, кого сейчас нет в сети. */
  async getBalance(serverId: string, uuid: string): Promise<MinecraftBalanceDto> {
    if (!(await this.isConfigured(serverId))) return economyFailure('no-companion', null);
    const result = await this.callRaw<RawBalance>(serverId, `/economy/native/balance/${uuid}`);
    if (!result.ok) return economyFailure(result.code, result.error);
    return {
      available: true,
      balance: numberOr(result.body.balance, 0),
      formatted: result.body.formatted ?? undefined,
      currency: result.body.currency ?? undefined,
    };
  }

  /**
   * Начисление или списание.
   *
   * Возвращает либо результат операции (в том числе отказ провайдера —
   * с ok:false и его текстом), либо отказ на уровне доступности экономики.
   * Разделение важно для журнала: отказ «не хватило денег» — это состоявшаяся
   * попытка с балансом до и после, а недоступный backend — вообще не
   * подтверждённая операция. Один и тот же idempotency key можно повторить.
   */
  async changeBalance(
    serverId: string,
    uuid: string,
    direction: 'deposit' | 'withdraw',
    amount: number,
    idempotencyKey: string,
    actor: string,
    reason: string,
  ): Promise<
    | { ok: true; change: MinecraftBalanceChangeDto }
    | {
        ok: false;
        failure: MinecraftBalanceDto;
        operationCode?: string | null;
        status?: number | null;
      }
  > {
    if (!(await this.isConfigured(serverId))) {
      return { ok: false, failure: economyFailure('no-companion', null) };
    }
    const result = await this.callRaw<RawBalanceChange>(
      serverId,
      `/economy/native/balance/${uuid}/${direction}`,
      { method: 'POST', body: { amount, idempotencyKey, actor, reason } },
    );
    if (!result.ok) {
      return {
        ok: false,
        failure: economyFailure(result.code, result.error),
        operationCode: result.code,
        status: result.status,
      };
    }
    if (result.body.source !== 'aurum') {
      return {
        ok: false,
        failure: economyFailure('requires-aurumcore', null),
        operationCode: 'economy-unavailable',
        status: 503,
      };
    }
    return {
      ok: true,
      change: {
        ok: result.body.ok === true,
        error: result.body.error ?? undefined,
        balanceBefore: numberOr(result.body.balanceBefore, 0),
        balanceAfter: numberOr(result.body.balanceAfter, 0),
        formatted: result.body.formatted ?? undefined,
        code: result.body.code ?? undefined,
        idempotencyKey: result.body.idempotencyKey ?? idempotencyKey,
        source: 'aurum',
        duplicate: result.body.duplicate === true,
      },
    };
  }

  /**
   * Сброс пароля игрока: выдать одноразовый токен.
   *
   * Токен возвращается РОВНО ОДИН РАЗ — плагин авторизации хранит только его
   * хеш и повторить не сможет. Поэтому и не кэшируется здесь, и не пишется в
   * журнал: журнал фиксирует сам факт сброса и кто его сделал, а сам токен
   * ему знать незачем.
   *
   * null — сбросить нечего: нет companion, нет плагина авторизации или нет
   * аккаунта с таким ником. Плагин намеренно не различает эти случаи в
   * ответе, и панель их тоже не различает.
   */
  async resetPassword(serverId: string, username: string): Promise<MinecraftPasswordResetDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<RawPasswordReset>(
      serverId,
      `/auth/reset/${encodeURIComponent(username)}`,
      { method: 'POST' },
    );
    if (!result.ok || !result.body.token) return null;
    return {
      username: result.body.username ?? username,
      token: result.body.token,
      expiresAt: new Date(numberOr(result.body.expiresAt, Date.now())).toISOString(),
    };
  }

  // ------------------------------------------------------------- гильдии
  //
  // Плагин гильдий — отдельный от companion, и его может не быть. Ответ 503 с
  // кодом guilds-unavailable означает именно это, и панель по нему прячет
  // раздел, а не показывает ошибку.

  /** Список гильдий с поиском по имени и тегу. null — раздел недоступен. */
  async getGuilds(serverId: string, query: string | null): Promise<MinecraftGuildDto[] | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const suffix = query ? `?query=${encodeURIComponent(query)}` : '';
    const result = await this.callRaw<{ guilds?: RawGuild[] }>(serverId, `/guilds${suffix}`);
    if (!result.ok) return null;
    return (result.body.guilds ?? []).map(toGuild);
  }

  /** Гильдия вместе с составом. null — нет такой или раздел недоступен. */
  async getGuild(serverId: string, guildId: number): Promise<MinecraftGuildDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<RawGuild>(serverId, `/guilds/${guildId}`);
    return result.ok ? toGuild(result.body) : null;
  }

  /**
   * Гильдия игрока.
   *
   * null здесь означает и «не состоит», и «раздела нет». Для карточки игрока
   * разницы нет: в обоих случаях блок про гильдию просто не показывается.
   */
  async getPlayerGuild(
    serverId: string,
    uuid: string,
  ): Promise<MinecraftGuildMembershipDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<RawGuildMembership>(serverId, `/players/${uuid}/guild`);
    if (!result.ok || !result.body.membership) return null;
    const raw = result.body.membership;
    return {
      guildId: numberOr(raw.guildId, 0),
      guildName: raw.guildName ?? '',
      guildTag: raw.guildTag ?? '',
      rank: toRank(raw.rank),
      joinedAt: new Date(numberOr(raw.joinedAt, Date.now())).toISOString(),
    };
  }

  /** Действующие бонусы гильдии. null — companion или плагин гильдий недоступны. */
  async getGuildBonuses(
    serverId: string,
    guildId: number,
  ): Promise<MinecraftGuildBonusDto[] | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<{ bonuses?: RawGuildBonus[] }>(
      serverId,
      `/guilds/${guildId}/bonuses`,
    );
    if (!result.ok) return null;
    return (result.body.bonuses ?? []).map(toBonus);
  }

  /**
   * Выдать или снять бонус.
   *
   * Возвращает тот же вид ответа, что и остальное вмешательство администрации:
   * текст отказа приходит из самого плагина, где он написан рядом с условием,
   * при котором возникает. Панели остаётся показать его человеку.
   */
  async guildBonusAction(
    serverId: string,
    path: string,
    method: 'POST' | 'DELETE',
    body: Record<string, unknown>,
  ): Promise<GuildOutcome> {
    if (!(await this.isConfigured(serverId))) {
      return outcome(false, 'mc.err.companionNotConfigured');
    }
    const result = await this.callRaw<RawGuildOutcome>(serverId, path, { method, body });
    if (result.ok) {
      return outcome(
        true,
        result.body.message ?? 'common.done',
        result.body.values,
        result.body.keys,
      );
    }
    if (result.code === 'guilds-unavailable') return outcome(false, 'mc.err.noGuildsPlugin');
    // 409 от плагина приносит ту же тройку: ключ и обе карты. Отказ «такой
    // гильдии нет» переводится ровно так же, как успех.
    const refusal = result.body as RawGuildOutcome | null;
    return outcome(
      false,
      refusal?.message ?? result.error ?? 'mc.err.serverSilent',
      refusal?.values,
      refusal?.keys,
    );
  }

  /**
   * Вмешательство администрации.
   *
   * Возвращается и текст отказа: он приходит из самого плагина гильдий, где
   * написан рядом с условием, при котором возникает. Панели остаётся показать
   * его человеку, а не придумывать свою формулировку.
   */
  async guildAction(
    serverId: string,
    path: string,
    body: Record<string, unknown>,
  ): Promise<GuildOutcome> {
    if (!(await this.isConfigured(serverId))) {
      return outcome(false, 'mc.err.companionNotConfigured');
    }
    const result = await this.callRaw<RawGuildOutcome>(serverId, path, {
      method: 'POST',
      body,
    });
    if (result.ok) {
      return outcome(
        true,
        result.body.message ?? 'common.done',
        result.body.values,
        result.body.keys,
      );
    }
    if (result.code === 'guilds-unavailable') return outcome(false, 'mc.err.noGuildsPlugin');
    const refusal = result.body as RawGuildOutcome | null;
    return outcome(
      false,
      refusal?.message ?? result.error ?? 'mc.err.serverSilent',
      refusal?.values,
      refusal?.keys,
    );
  }

  /**
   * Экономика сервера целиком.
   *
   * Нативный snapshot — индексированные агрегаты ledger и ограниченный top.
   * Никакого обхода OfflinePlayer и Vault fallback этот маршрут не делает.
   */
  async getEconomy(serverId: string, top: number): Promise<MinecraftEconomyDto> {
    if (!(await this.isConfigured(serverId))) return economyFailure('no-companion', null);
    const result = await this.callRaw<RawEconomy>(serverId, `/economy/native?top=${top}`, {
      timeoutMs: 8_000,
    });
    if (!result.ok) return economyFailure(result.code, result.error);
    const ledger = result.body.source === 'aurum';
    if (!ledger) return economyFailure('requires-aurumcore', null);
    return {
      available: true,
      source: 'aurum',
      total: numberOr(result.body.total, 0),
      totalFormatted: result.body.totalFormatted ?? undefined,
      currency: result.body.currency ?? undefined,
      // Отсутствие счётчика игроков — это «не считали», а не ноль: у ledger
      // сумма берётся запросом, и подставлять сюда ноль значит утверждать,
      // что на сервере нет ни одного игрока.
      ...(typeof result.body.playersCounted === 'number'
        ? { playersCounted: result.body.playersCounted }
        : {}),
      treasury: numberOr(result.body.treasury, 0),
      treasuryFormatted: result.body.treasuryFormatted ?? undefined,
      moneySupply: numberOr(result.body.moneySupply, 0),
      moneySupplyFormatted: result.body.moneySupplyFormatted ?? undefined,
      taxesCollected: numberOr(result.body.taxesCollected, 0),
      taxesFormatted: result.body.taxesFormatted ?? undefined,
      top: (result.body.top ?? [])
        .filter((e): e is Required<RawTopEntry> => typeof e.uuid === 'string' && typeof e.name === 'string')
        .map((e) => ({
          name: e.name,
          uuid: e.uuid,
          balance: numberOr(e.balance, 0),
          formatted: e.formatted ?? '',
        })),
    };
  }

  /** One bounded native Core section. There is deliberately no Vault approximation. */
  async getEconomyAudit(
    serverId: string,
    section: MinecraftEconomyAuditSection,
    currency = '',
    account = '',
    limit = 50,
  ): Promise<MinecraftEconomyAuditDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const query = new URLSearchParams({ limit: String(Math.max(1, Math.min(200, limit))) });
    if (currency.trim()) query.set('currency', currency.trim().toLowerCase());
    if (account.trim()) query.set('account', account.trim());
    const result = await this.callRaw<RawEconomyAudit>(
      serverId,
      `/economy/audit/${section}?${query.toString()}`,
      { timeoutMs: 8_000 },
    );
    if (!result.ok || result.body.section !== section || typeof result.body.currency !== 'string') return null;
    return {
      section,
      currency: result.body.currency,
      generatedAt: new Date(numberOr(result.body.generatedAt, Date.now())).toISOString(),
      summary: stringRecord(result.body.summary),
      records: (result.body.records ?? [])
        .filter((record): record is RawEconomyAuditRecord =>
          typeof record?.type === 'string' && record.fields !== null && typeof record.fields === 'object')
        .slice(0, 200)
        .map((record) => ({ type: record.type!, fields: stringRecord(record.fields) })),
    };
  }

  async getEconomyRules(
    serverId: string,
    type: MinecraftEconomyRuleType,
  ): Promise<MinecraftEconomyRuleDto[] | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<{ rules?: RawEconomyRule[] }>(
      serverId,
      `/economy/rules/${type}`,
      { timeoutMs: 8_000 },
    );
    if (!result.ok || !Array.isArray(result.body.rules)) return null;
    return result.body.rules.map(toEconomyRule).filter((rule): rule is MinecraftEconomyRuleDto => rule !== null);
  }

  async previewEconomyRule(
    serverId: string,
    type: MinecraftEconomyRuleType,
    input: { id: string; expectedRevision: number; fields: Record<string, string>; actor: string },
  ): Promise<MinecraftEconomyRulePreviewDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<RawEconomyRulePreview>(
      serverId,
      `/economy/rules/${type}/preview`,
      { method: 'POST', body: input, timeoutMs: 8_000 },
    );
    return toRulePreview(result.body);
  }

  async applyEconomyRule(
    serverId: string,
    input: { token: string; actor: string; reason: string },
  ): Promise<MinecraftEconomyRuleApplyDto | null> {
    if (!(await this.isConfigured(serverId))) return null;
    const result = await this.callRaw<RawEconomyRuleApply>(serverId, '/economy/rules/apply', {
      method: 'POST',
      body: input,
      timeoutMs: 8_000,
    });
    return toRuleApply(result.body);
  }

  // ------------------------------------------------------------- Тюрьмы

  /**
   * Тюрьмы EssentialsX и кто в них сидит; null — плагина панели нет или молчит.
   *
   * Читаем через companion, а сажаем и выпускаем командой по RCON. Так
   * задумано: у EssentialsX посадка — это ещё и телепорт, событие для других
   * плагинов, сообщение игроку и оповещение персонала. Дёрнув его внутренний
   * метод, мы получили бы игрока в тюрьме, о котором никто не узнал.
   *
   * ВАЖНО про состав списка. Плагин отдаёт только тех, кто СЕЙЧАС в сети:
   * узнать, кто сидит из офлайновых, можно лишь прочитав файл каждого игрока
   * на диске, а их бывают десятки тысяч. Недостающих панель добирает из
   * собственных записей — см. JailsService.
   */
  async getJails(serverId: string): Promise<CompanionJails | null> {
    const data = await this.call<RawJails>(serverId, '/jails');
    if (!data) return null;
    return {
      available: data.available === true,
      jails: (data.jails ?? []).filter((name): name is string => typeof name === 'string'),
      jailed: (data.jailed ?? [])
        .filter((e): e is Required<RawJailedPlayer> => typeof e.uuid === 'string' && typeof e.name === 'string')
        .map((e) => ({
          uuid: e.uuid,
          name: e.name,
          jail: typeof e.jail === 'string' ? e.jail : '',
          releaseAt: numberOr(e.releaseAt, 0),
        })),
    };
  }

  /**
   * Сидит ли ОДИН игрок — по нику, включая тех, кого сейчас нет в сети.
   *
   * Дополняет getJails там, где тот бессилен: список сидящих плагин собирает
   * по игрокам в сети и иначе не может, а сажать и выпускать панель должна и
   * офлайн-игроков. EssentialsX это умеет и телепортирует человека в тюрьму
   * при следующем входе.
   *
   * Стоит это одно чтение файла игрока, поэтому зовётся по одному на действие
   * и никогда — списком.
   *
   * null — companion не настроен или молчит. Это НЕ «не сидит»: на таком
   * ответе панель отправила бы команду посадки тому, кто уже сидит, и
   * получила бы в консоли отказ вместо действия.
   */
  async getPlayerJail(serverId: string, player: string): Promise<MinecraftPlayerJailDto | null> {
    const data = await this.call<RawPlayerJail>(
      serverId,
      `/players/${encodeURIComponent(player)}/jail`,
    );
    if (!data) return null;
    return {
      known: data.known === true,
      jailed: data.jailed === true,
      jail: typeof data.jail === 'string' ? data.jail : '',
      releaseAt: numberOr(data.releaseAt, 0),
      online: data.online === true,
    };
  }

  /**
   * Ник -> UUID. Регистр ника не важен.
   *
   * Сначала по списку онлайн — это один дешёвый запрос и попадание в
   * большинстве случаев. Если не нашлось, спрашиваем исторический список с
   * фильтром по этому же нику: так находится и тот, кого сейчас нет в сети.
   *
   * Второй запрос делается ТОЛЬКО при промахе по первому: исторический
   * список дороже, и платить за него на каждом обращении незачем.
   */
  async resolveUuid(serverId: string, player: string): Promise<string | null> {
    const needle = player.toLowerCase();

    const players = await this.getPlayers(serverId);
    const online = players?.find((p) => p.name.toLowerCase() === needle);
    if (online?.uuid) return online.uuid;

    const known = await this.getKnownPlayers(serverId, { query: player, limit: 25 });
    // Совпадение только точное: query — это подстрока, и «Ste» не должен
    // молча превратиться в «Steve».
    return known.players.find((p) => p.name.toLowerCase() === needle)?.uuid ?? null;
  }
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * Бонус из ответа плагина.
 *
 * Ноль в expiresAt означает «постоянный» — в панели это null, потому что для
 * интерфейса «нет срока» и «срок в эпохе ноль» это разные вещи, а для JSON
 * ноль был проще отсутствующего поля.
 */
function toBonus(raw: RawGuildBonus): MinecraftGuildBonusDto {
  return {
    type: raw.type ?? 'unknown',
    title: raw.title ?? raw.type ?? 'mc.g.bonusFallback',
    magnitude: numberOr(raw.magnitude, 1),
    multiplier: raw.multiplier !== false,
    expiresAt: raw.expiresAt ? new Date(raw.expiresAt).toISOString() : null,
    grantedBy: raw.grantedBy ?? '—',
    grantedAt: new Date(numberOr(raw.grantedAt, Date.now())).toISOString(),
  };
}

/**
 * Запись исторического списка.
 *
 * Пустой алиас приравнивается к его отсутствию: ник из пробелов игроку
 * ничего не говорит, а рисовать рядом с именем пустые скобки — хуже, чем
 * не рисовать ничего.
 */
function toKnownPlayer(raw: RawKnownPlayer & { uuid: string; name: string }): MinecraftKnownPlayerDto {
  const alias = typeof raw.alias === 'string' && raw.alias.trim() ? raw.alias : null;
  return {
    uuid: raw.uuid,
    name: raw.name,
    // Алиас, совпадающий с настоящим именем, не показываем: он ничего не
    // добавляет, а «Steve (Steve)» выглядит поломкой.
    alias: alias && alias !== raw.name ? alias : null,
    op: raw.op === true,
    online: raw.online === true,
    // Именно строгая проверка на boolean: null значит «плагина авторизации
    // нет», и превращать его в false нельзя — это разные утверждения.
    registered: typeof raw.registered === 'boolean' ? raw.registered : null,
    lastSeen: raw.lastSeen ? new Date(raw.lastSeen).toISOString() : null,
  };
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
      reason: 'mc.err.permsNeedLuckPerms',
    };
  }
  return {
    available: false,
    code: 'error',
    reason: error ?? 'mc.err.companionSilent',
  };
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

interface RawBalance {
  balance?: number;
  formatted?: string | null;
  currency?: string | null;
}

interface RawPasswordReset {
  username?: string;
  token?: string;
  expiresAt?: number;
}

interface RawBalanceChange {
  ok?: boolean;
  code?: string | null;
  error?: string | null;
  balanceBefore?: number;
  balanceAfter?: number;
  formatted?: string | null;
  idempotencyKey?: string | null;
  source?: string | null;
  duplicate?: boolean;
}

interface RawTopEntry {
  name?: string;
  uuid?: string;
  balance?: number;
  formatted?: string;
}

interface RawEconomy {
  source?: string | null;
  total?: number;
  totalFormatted?: string | null;
  currency?: string | null;
  playersCounted?: number;
  treasury?: number;
  treasuryFormatted?: string | null;
  moneySupply?: number;
  moneySupplyFormatted?: string | null;
  taxesCollected?: number;
  taxesFormatted?: string | null;
  top?: RawTopEntry[];
}

interface RawEconomyAuditRecord {
  type?: string;
  fields?: Record<string, unknown>;
}

interface RawEconomyAudit {
  section?: string;
  currency?: string;
  generatedAt?: number;
  summary?: Record<string, unknown>;
  records?: RawEconomyAuditRecord[];
}

interface RawEconomyRule {
  type?: unknown;
  id?: unknown;
  revision?: unknown;
  fields?: unknown;
}

interface RawEconomyRulePreview {
  status?: unknown;
  token?: unknown;
  current?: unknown;
  proposed?: unknown;
  warnings?: unknown;
  message?: unknown;
  expiresAt?: unknown;
}

interface RawEconomyRuleApply {
  status?: unknown;
  current?: unknown;
  message?: unknown;
}

function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
  );
}

function toEconomyRule(value: unknown): MinecraftEconomyRuleDto | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as RawEconomyRule;
  if ((raw.type !== 'policy' && raw.type !== 'exchange') || typeof raw.id !== 'string'
      || typeof raw.revision !== 'number' || !Number.isSafeInteger(raw.revision) || raw.revision < 0) {
    return null;
  }
  return { type: raw.type, id: raw.id, revision: raw.revision, fields: stringRecord(raw.fields) };
}

function toRulePreview(value: unknown): MinecraftEconomyRulePreviewDto | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as RawEconomyRulePreview;
  if (!['ready', 'conflict', 'invalid', 'unavailable'].includes(String(raw.status))) return null;
  return {
    status: raw.status as MinecraftEconomyRulePreviewDto['status'],
    token: typeof raw.token === 'string' ? raw.token : '',
    current: toEconomyRule(raw.current),
    proposed: toEconomyRule(raw.proposed),
    warnings: Array.isArray(raw.warnings)
      ? raw.warnings.filter((warning): warning is string => typeof warning === 'string')
      : [],
    message: typeof raw.message === 'string' ? raw.message : '',
    expiresAt: typeof raw.expiresAt === 'number' && raw.expiresAt > 0
      ? new Date(raw.expiresAt).toISOString()
      : null,
  };
}

function toRuleApply(value: unknown): MinecraftEconomyRuleApplyDto | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as RawEconomyRuleApply;
  if (!['applied', 'applied_reload_failed', 'conflict', 'invalid', 'expired', 'unavailable']
      .includes(String(raw.status))) return null;
  return {
    status: raw.status as MinecraftEconomyRuleApplyDto['status'],
    current: toEconomyRule(raw.current),
    message: typeof raw.message === 'string' ? raw.message : '',
  };
}

/**
 * Почему валюта недоступна — в виде, пригодном для показа.
 *
 * Коды плагина сохраняем как есть: панель по ним подсказывает, что именно
 * доставить на игровой сервер.
 */
function economyFailure(
  code: string | null,
  error: string | null,
): { available: false; code: 'no-companion' | 'requires-aurumcore' | 'requires-vault' | 'no-provider' | 'error'; reason: string } {
  if (code === 'no-companion') {
    return {
      available: false,
      code: 'no-companion',
      reason: 'mc.err.ecoNeedCompanion',
    };
  }
  if (code === 'requires-aurumcore') {
    return {
      available: false,
      code: 'requires-aurumcore',
      reason: 'mc.err.ecoNeedAurumCore',
    };
  }
  if (code === 'requires-vault') {
    return {
      available: false,
      code: 'requires-vault',
      reason: 'mc.err.ecoNeedVault',
    };
  }
  if (code === 'no-provider') {
    return {
      available: false,
      code: 'no-provider',
      reason: 'mc.err.ecoNoProvider',
    };
  }
  return {
    available: false,
    code: 'error',
    reason: error ?? 'mc.err.companionSilent',
  };
}

/** Ранг из ответа плагина. Неизвестное — участник: понижение безопаснее повышения. */
function toRank(raw: string | undefined): MinecraftGuildRank {
  return raw === 'leader' || raw === 'officer' ? raw : 'member';
}

function toGuild(raw: RawGuild): MinecraftGuildDto {
  return {
    id: numberOr(raw.id, 0),
    name: raw.name ?? '',
    tag: raw.tag ?? '',
    leaderUuid: raw.leaderUuid ?? '',
    leaderName: raw.leaderName ?? '',
    memberCount: numberOr(raw.memberCount, 0),
    bankBalance: numberOr(raw.bankBalance, 0),
    createdAt: new Date(numberOr(raw.createdAt, 0)).toISOString(),
    members: (raw.members ?? []).map((member) => ({
      uuid: member.uuid ?? '',
      name: member.name ?? '',
      rank: toRank(member.rank),
      joinedAt: new Date(numberOr(member.joinedAt, 0)).toISOString(),
    })),
  };
}
