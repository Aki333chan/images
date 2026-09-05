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

interface RawGuildOutcome {
  ok?: boolean;
  message?: string;
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
  // Всё идёт через Vault, поэтому недоступность бывает трёх видов, и панель
  // должна их различать: нет companion-плагина (ставить плагин панели),
  // нет Vault (ставить Vault) и Vault без провайдера (ставить плагин
  // экономики). Один общий текст «валюта недоступна» отправил бы человека
  // искать причину вслепую.

  /** Баланс игрока по UUID. Работает и для тех, кого сейчас нет в сети. */
  async getBalance(serverId: string, uuid: string): Promise<MinecraftBalanceDto> {
    if (!(await this.isConfigured(serverId))) return economyFailure('no-companion', null);
    const result = await this.callRaw<RawBalance>(serverId, `/players/${uuid}/balance`);
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
   * попытка с балансом до и после, а «нет Vault» — вообще не операция.
   */
  async changeBalance(
    serverId: string,
    uuid: string,
    direction: 'deposit' | 'withdraw',
    amount: number,
  ): Promise<
    { ok: true; change: MinecraftBalanceChangeDto } | { ok: false; failure: MinecraftBalanceDto }
  > {
    if (!(await this.isConfigured(serverId))) {
      return { ok: false, failure: economyFailure('no-companion', null) };
    }
    const result = await this.callRaw<RawBalanceChange>(
      serverId,
      `/players/${uuid}/balance/${direction}`,
      { method: 'POST', body: { amount } },
    );
    if (!result.ok) return { ok: false, failure: economyFailure(result.code, result.error) };
    return {
      ok: true,
      change: {
        ok: result.body.ok === true,
        error: result.body.error ?? undefined,
        balanceBefore: numberOr(result.body.balanceBefore, 0),
        balanceAfter: numberOr(result.body.balanceAfter, 0),
        formatted: result.body.formatted ?? undefined,
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
  ): Promise<{ ok: boolean; message: string }> {
    if (!(await this.isConfigured(serverId))) {
      return { ok: false, message: 'mc.err.companionNotConfigured' };
    }
    const result = await this.callRaw<RawGuildOutcome>(serverId, path, { method, body });
    if (result.ok) return { ok: true, message: result.body.message ?? 'common.done' };
    if (result.code === 'guilds-unavailable') {
      return { ok: false, message: 'mc.err.noGuildsPlugin' };
    }
    return { ok: false, message: result.error ?? 'mc.err.serverSilent' };
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
  ): Promise<{ ok: boolean; message: string }> {
    if (!(await this.isConfigured(serverId))) {
      return { ok: false, message: 'mc.err.companionNotConfigured' };
    }
    const result = await this.callRaw<RawGuildOutcome>(serverId, path, {
      method: 'POST',
      body,
    });
    if (result.ok) return { ok: true, message: result.body.message ?? 'common.done' };
    if (result.code === 'guilds-unavailable') {
      return { ok: false, message: 'mc.err.noGuildsPlugin' };
    }
    return { ok: false, message: result.error ?? 'mc.err.serverSilent' };
  }

  /**
   * Экономика сервера целиком.
   *
   * Таймаут здесь заметно больше обычного: плагин обходит всех, кто когда-либо
   * заходил на сервер, и у некоторых провайдеров это поход в базу на каждого.
   * Запрос редкий — панель держит результат в кэше.
   */
  async getEconomy(serverId: string, top: number): Promise<MinecraftEconomyDto> {
    if (!(await this.isConfigured(serverId))) return economyFailure('no-companion', null);
    const result = await this.callRaw<RawEconomy>(serverId, `/economy?top=${top}`, {
      timeoutMs: 40_000,
    });
    if (!result.ok) return economyFailure(result.code, result.error);
    return {
      available: true,
      total: numberOr(result.body.total, 0),
      totalFormatted: result.body.totalFormatted ?? undefined,
      currency: result.body.currency ?? undefined,
      playersCounted: numberOr(result.body.playersCounted, 0),
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
  error?: string | null;
  balanceBefore?: number;
  balanceAfter?: number;
  formatted?: string | null;
}

interface RawTopEntry {
  name?: string;
  uuid?: string;
  balance?: number;
  formatted?: string;
}

interface RawEconomy {
  total?: number;
  totalFormatted?: string | null;
  currency?: string | null;
  playersCounted?: number;
  top?: RawTopEntry[];
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
): { available: false; code: 'no-companion' | 'requires-vault' | 'no-provider' | 'error'; reason: string } {
  if (code === 'no-companion') {
    return {
      available: false,
      code: 'no-companion',
      reason: 'mc.err.ecoNeedCompanion',
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
