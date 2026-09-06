import { BadRequestException, Injectable } from '@nestjs/common';
import type {
  MinecraftBalanceChangeDto,
  MinecraftBalanceDto,
  MinecraftBanDto,
  MinecraftConsoleCompletionDto,
  MinecraftConsoleDictionaryDto,
  MinecraftEconomyDto,
  MinecraftInventoryResponse,
  MinecraftPerformanceDto,
  MinecraftPlayersResponse,
  MinecraftPluginsDto,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { MINECRAFT_SERVER_COMMANDS } from '@aurum/shared';
import { AuditService } from '../../audit/audit.service';
import { PrismaService } from '../../prisma/prisma.service';
import { CompanionService } from './companion.service';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import { KNOWN_PLUGINS } from '@aurum/shared';
import {
  looksLikeUnknownCommand,
  parseMspt,
  parsePlayerList,
  parseTps,
} from '../minecraft-shared/minecraft-parsers';
import {
  catalogConsoleCommands,
} from '../minecraft-shared/quick-commands.config';
import { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';

@Injectable()
export class MinecraftService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: MinecraftConfigService,
    private readonly vanilla: VanillaRconService,
    private readonly companion: CompanionService,
    private readonly audit: AuditService,
  ) {}

  /**
   * Всё, что умеет любой сервер Minecraft, живёт в общем VanillaRconService и
   * отсюда только делегируется. Второй реализации тех же команд быть не
   * должно: `ban` на Paper и `ban` на Forge — это буквально одна и та же
   * команда сервера, и разъехавшись однажды, они разъедутся молча.
   *
   * В этом классе остаётся ТОЛЬКО то, чего на загрузчиках модов нет:
   * companion-плагин, плагины Bukkit, TPS, права LuckPerms и валюта Vault.
   */
  runCommand(serverId: string, command: string): Promise<string> {
    return this.vanilla.runCommand(serverId, command);
  }

  private assertNickname(name: string): string {
    return this.vanilla.assertNickname(name);
  }

  // ---------- Игроки ----------

  async getPlayers(serverId: string): Promise<MinecraftPlayersResponse> {
    // Плагин даёт UUID и пинг; без него разбираем ответ команды list.
    const fromPlugin = await this.companion.getPlayers(serverId);
    if (fromPlugin) {
      return {
        players: fromPlugin,
        online: fromPlugin.length,
        max: null,
        source: 'companion',
      };
    }
    const raw = await this.runCommand(serverId, 'list');
    const parsed = parsePlayerList(raw);
    return {
      // По RCON доступны только ники — остальное умеет отдать плагин.
      players: parsed.names.map((name) => ({
        name,
        uuid: null,
        ping: null,
        health: null,
        maxHealth: null,
        world: null,
        position: null,
      })),
      online: parsed.online,
      max: parsed.max,
      source: 'rcon',
    };
  }

  /**
   * TPS и время тика. Команды есть только в Paper/Spigot — на ванильном
   * сервере они неизвестны, и это не ошибка: возвращаем флаги поддержки,
   * чтобы интерфейс мог сказать «недоступно», а не показывать пустоту.
   */
  async getPerformance(serverId: string): Promise<MinecraftPerformanceDto> {
    const [tpsRaw, msptRaw] = await Promise.all([
      this.runCommand(serverId, 'tps'),
      this.runCommand(serverId, 'mspt').catch(() => ''),
    ]);

    const tpsSupported = !looksLikeUnknownCommand(tpsRaw);
    const msptSupported = !looksLikeUnknownCommand(msptRaw);
    const tps = tpsSupported ? parseTps(tpsRaw) : { tps1m: null, tps5m: null, tps15m: null };

    return {
      ...tps,
      mspt: msptSupported ? parseMspt(msptRaw) : null,
      tpsSupported,
      msptSupported,
    };
  }

  kick(serverId: string, name: string, reason: string): Promise<string> {
    return this.vanilla.kick(serverId, name, reason);
  }

  // ---------- Баны, whitelist, быстрые команды ----------
  //
  // Всё это команды самого сервера Minecraft, одинаковые на Paper, Forge и
  // NeoForge, — поэтому реализация одна, в общем слое, а здесь только проброс.
  // Таблица банов тоже общая: причина, срок и модератор это данные панели.

  listBans(serverId: string, search?: string): Promise<MinecraftBanDto[]> {
    return this.vanilla.listBans(serverId, search);
  }

  ban(
    serverId: string,
    name: string,
    reason: string,
    expiresAt: Date | null,
    actorId: string,
  ): Promise<MinecraftBanDto> {
    return this.vanilla.ban(serverId, name, reason, expiresAt, actorId);
  }

  pardon(serverId: string, banId: string, actorId: string): Promise<MinecraftBanDto> {
    return this.vanilla.pardon(serverId, banId, actorId);
  }

  getWhitelist(serverId: string): Promise<MinecraftWhitelistResponse> {
    return this.vanilla.getWhitelist(serverId);
  }

  addToWhitelist(serverId: string, name: string): Promise<MinecraftWhitelistResponse> {
    return this.vanilla.addToWhitelist(serverId, name);
  }

  removeFromWhitelist(serverId: string, name: string): Promise<MinecraftWhitelistResponse> {
    return this.vanilla.removeFromWhitelist(serverId, name);
  }

  /**
   * Каталог быстрых действий.
   *
   * @param installedPlugins имена плагинов, реально стоящих на сервере;
   *   null — список получить не удалось (нет companion-плагина или он молчит).
   *   Тогда показываем только ванильные действия: кнопка, ведущая в
   *   «Unknown command», хуже, чем её отсутствие.
   */
  listQuickCommands(installedPlugins: string[] | null): MinecraftQuickCommandDto[] {
    return this.vanilla.listQuickCommands(installedPlugins);
  }

  // ---------- Автодополнение в консоли ----------

  /**
   * Словарь для базового автодополнения: команды сервера + команды плагинов
   * из каталога + ники игроков онлайн.
   *
   * Панель забирает его один раз при открытии консоли и дополняет локально,
   * поэтому Tab срабатывает мгновенно и не зависит от того, жив ли игровой
   * сервер. Всё, что можно не знать, здесь не фатально: и список плагинов,
   * и список игроков могут не получиться — словарь всё равно вернётся.
   */
  async getConsoleDictionary(serverId: string): Promise<MinecraftConsoleDictionaryDto> {
    const installed = await this.installedPluginNames(serverId).catch(() => null);
    const known = new Set(installed?.map((name) => name.toLowerCase()) ?? []);

    // Команды плагинов показываем, только если плагин действительно стоит.
    // Когда список плагинов недоступен (нет companion-плагина), фильтровать
    // нечем — тогда лучше предложить лишнее, чем не предложить нужное:
    // в консоли неверная подсказка стоит одной строки «Unknown command».
    const fromCatalog = catalogConsoleCommands().filter(
      (c) => installed === null || (c.plugin !== null && known.has(c.plugin.toLowerCase())),
    );

    const serverCommandNames = new Set(MINECRAFT_SERVER_COMMANDS.map((c) => c.name));
    const commands = [
      ...MINECRAFT_SERVER_COMMANDS,
      ...fromCatalog.filter((c) => !serverCommandNames.has(c.name)),
    ].sort((a, b) => a.name.localeCompare(b.name));

    const players = await this.getPlayers(serverId)
      .then((r) => r.players.map((p) => p.name))
      .catch(() => [] as string[]);

    return {
      commands,
      players,
      companionAvailable: await this.companion.isConfigured(serverId),
    };
  }

  /**
   * Продвинутое автодополнение: спрашиваем у самого Bukkit через
   * companion-плагин. Работает так же, как Tab в игре, — знает и команды
   * сторонних плагинов, и их аргументы (миры, киты, зачарования).
   *
   * Если плагина нет или он не ответил, возвращаем available:false, а не
   * ошибку: панель в этом случае просто остаётся на словаре.
   */
  async completeConsoleCommand(
    serverId: string,
    line: string,
  ): Promise<MinecraftConsoleCompletionDto> {
    // Ограничение длины — от случайной вставки простыни текста в поле ввода.
    const suggestions = await this.companion.complete(serverId, line.slice(0, 256));
    if (!suggestions) return { available: false, suggestions: [], source: 'static' };
    return { available: true, suggestions, source: 'companion' };
  }

  /**
   * Плагины сервера: что стоит на самом деле и что из известного панели
   * доступно. Работает и без companion-плагина — тогда честно говорит,
   * что проверить нечем.
   */
  async getPlugins(serverId: string): Promise<MinecraftPluginsDto> {
    const installed = await this.companion.getInstalledPlugins(serverId);
    if (!installed) {
      return {
        available: false,
        reason: 'mc.err.pluginListNeedsCompanion',
        installed: [],
        known: KNOWN_PLUGINS.map((p) => ({
          id: p.id,
          displayName: p.displayName,
          givesKey: p.givesKey,
          installed: false,
          version: null,
        })),
      };
    }

    const byName = new Map(installed.map((p) => [p.name.toLowerCase(), p]));
    return {
      available: true,
      installed,
      known: KNOWN_PLUGINS.map((p) => {
        const match = byName.get(p.id.toLowerCase());
        return {
          id: p.id,
          displayName: p.displayName,
          givesKey: p.givesKey,
          // Выключенный плагин командой не отзовётся — считаем неустановленным.
          installed: !!match && match.enabled,
          version: match?.version ?? null,
        };
      }),
    };
  }

  /** Имена установленных плагинов или null. Нужен для фильтрации действий. */
  async installedPluginNames(serverId: string): Promise<string[] | null> {
    const installed = await this.companion.getInstalledPlugins(serverId);
    return installed ? installed.filter((p) => p.enabled).map((p) => p.name) : null;
  }

  /**
   * Сборка и запуск быстрых действий — тоже общий слой: шаблоны команд
   * ванильные, а разбор аргументов и экранирование одинаковы везде.
   */
  buildQuickCommand(
    id: string,
    args: Record<string, string>,
  ): { commands: string[]; permission: string } {
    return this.vanilla.buildQuickCommand(id, args);
  }

  runQuickCommand(serverId: string, id: string, args: Record<string, string>): Promise<string> {
    return this.vanilla.runQuickCommand(serverId, id, args);
  }

  // ---------- Инвентарь ----------

  async getInventory(serverId: string, player: string): Promise<MinecraftInventoryResponse> {
    this.assertNickname(player);
    return this.companion.getInventory(serverId, player);
  }

  /**
   * Ник -> UUID, с внятным отказом вместо пустоты.
   *
   * Нужен там, где вызывающая сторона оперирует ником, а адресат работает по
   * UUID: карточка игрока UUID уже знает, а вот AI-ассистенту им оперировать
   * не стоит — модель длинные идентификаторы сокращает и потом подставляет
   * собственное сокращение. Ник она не сокращает никогда.
   */
  async requirePlayerUuid(serverId: string, player: string): Promise<string> {
    this.assertNickname(player);
    const uuid = await this.companion.resolveUuid(serverId, player);
    if (!uuid) {
      throw new BadRequestException(
        { message: 'mc.err.noUuid', i18nValues: { player } },
      );
    }
    return uuid;
  }

  // ---------- Валюта (Vault) ----------

  async getBalance(serverId: string, uuid: string): Promise<MinecraftBalanceDto> {
    return this.companion.getBalance(serverId, uuid);
  }

  /**
   * Начисление или списание с записью в журнал аудита.
   *
   * В журнал попадает и неудачная попытка: «модератор пытался списать больше,
   * чем есть» — это ровно то, ради чего журнал заводят. Поле reason не
   * обязательное для API, но именно оно потом отвечает на вопрос «за что».
   */
  async changeBalance(
    serverId: string,
    uuid: string,
    direction: 'deposit' | 'withdraw',
    amount: number,
    reason: string | null,
    actorId: string,
  ): Promise<MinecraftBalanceChangeDto> {
    if (!Number.isFinite(amount) || amount <= 0) {
      throw new BadRequestException('mc.err.amountPositive');
    }
    // Дробные копейки провайдеры округляют по-своему, и в журнале потом не
    // сходится. Две цифры после запятой — то, что показывают все известные
    // плагины экономики.
    const rounded = Math.round(amount * 100) / 100;

    const result = await this.companion.changeBalance(serverId, uuid, direction, rounded);
    if (!result.ok) {
      // Валюты на сервере нет — это не состоявшаяся операция, писать в журнал
      // «изменение баланса» было бы неправдой.
      throw new BadRequestException(result.failure.reason ?? 'mc.err.ecoUnavailable');
    }

    // Ник ищем среди онлайна: в журнале «Steve» читается, а UUID — нет.
    // Не нашли — не беда, UUID остаётся в записи в любом случае.
    const players = await this.companion.getPlayers(serverId).catch(() => null);
    const playerName = players?.find((p) => p.uuid === uuid)?.name ?? null;

    await this.audit.log({
      actorId,
      action: direction === 'deposit' ? 'minecraft.economy.deposit' : 'minecraft.economy.withdraw',
      targetType: 'minecraft-player',
      targetId: uuid,
      metadata: {
        serverId,
        playerUuid: uuid,
        playerName,
        amount: rounded,
        reason: reason ?? null,
        ok: result.change.ok,
        error: result.change.error ?? null,
        balanceBefore: result.change.balanceBefore,
        balanceAfter: result.change.balanceAfter,
      },
    });

    return result.change;
  }

  /**
   * Экономика сервера: общий объём денег и доска богатства.
   *
   * Считается не на каждое открытие страницы. Плагин ради этой цифры обходит
   * всех, кто когда-либо заходил на сервер, и на выросшей базе это заметная
   * работа — поэтому результат живёт в кэше, а «обновить» пользователь жмёт
   * сам, когда цифра нужна свежая.
   */
  async getEconomy(serverId: string, options?: { refresh?: boolean }): Promise<MinecraftEconomyDto> {
    const now = Date.now();
    const cached = MinecraftService.economyCache.get(serverId);
    if (!options?.refresh && cached && now - cached.at < ECONOMY_CACHE_TTL_MS) {
      return { ...cached.value, cached: true };
    }

    const fresh = await this.companion.getEconomy(serverId, ECONOMY_TOP_LIMIT);
    if (!fresh.available) {
      // Отказ не кэшируем: поставили Vault — цифра должна появиться сразу,
      // а не через пять минут.
      MinecraftService.economyCache.delete(serverId);
      return fresh;
    }
    const value: MinecraftEconomyDto = { ...fresh, calculatedAt: new Date(now).toISOString() };
    MinecraftService.economyCache.set(serverId, { at: now, value });
    return { ...value, cached: false };
  }

  /**
   * Кэш экономики. Статический — чтобы переживать пересоздание сервиса в
   * тестах и не зависеть от области видимости провайдера.
   *
   * Хранение в памяти процесса выбрано осознанно: панель работает одним
   * экземпляром, а устаревшая на несколько минут сумма денег — не та величина,
   * ради которой стоит заводить общее хранилище. Если экземпляров станет
   * несколько, каждый просто посчитает свой снимок.
   */
  private static readonly economyCache = new Map<
    string,
    { at: number; value: MinecraftEconomyDto }
  >();
}

/** Пять минут: достаточно свежо для наблюдения и достаточно редко для сервера. */
const ECONOMY_CACHE_TTL_MS = 5 * 60 * 1000;

/** Длина доски богатства. Больше десятка строк на экран сервера не влезает. */
const ECONOMY_TOP_LIMIT = 10;
