import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
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
import { MinecraftConfigService } from './minecraft-config.service';
import { KNOWN_PLUGINS } from '@aurum/shared';
import {
  escapeForJsonLiteral,
  isValidNickname,
  looksLikeUnknownCommand,
  parseMspt,
  parsePlayerList,
  parseTps,
  parseWhitelist,
  sanitizeCommandArgument,
} from './minecraft-parsers';
import {
  MINECRAFT_QUICK_COMMANDS,
  NICKNAME_ARG_NAMES,
  catalogConsoleCommands,
} from './quick-commands.config';
import { RconService } from './rcon/rcon.service';

@Injectable()
export class MinecraftService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: MinecraftConfigService,
    private readonly rcon: RconService,
    private readonly companion: CompanionService,
    private readonly audit: AuditService,
  ) {}

  /** Единая точка выполнения RCON-команд: берёт креды и отмечает доступность. */
  async runCommand(serverId: string, command: string): Promise<string> {
    const rconConfig = await this.config.requireRcon(serverId);
    const output = await this.rcon.execute(serverId, rconConfig, command);
    await this.config.markSeen(serverId).catch(() => undefined);
    return output;
  }

  private assertNickname(name: string): string {
    if (!isValidNickname(name)) {
      throw new BadRequestException('Некорректный ник Minecraft (3–16 символов A-Z, 0-9, _)');
    }
    return name;
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

  async kick(serverId: string, name: string, reason: string): Promise<string> {
    this.assertNickname(name);
    const safeReason = sanitizeCommandArgument(reason) || 'Кик модератором';
    return this.runCommand(serverId, `kick ${name} ${safeReason}`);
  }

  // ---------- Баны ----------

  private async toBanDto(ban: {
    id: string;
    serverId: string;
    playerName: string;
    playerUuid: string | null;
    reason: string;
    expiresAt: Date | null;
    createdAt: Date;
    createdById: string | null;
    pardonedAt: Date | null;
    pardonedById: string | null;
  }): Promise<MinecraftBanDto> {
    const ids = [ban.createdById, ban.pardonedById].filter((v): v is string => !!v);
    const users = ids.length
      ? await this.prisma.user.findMany({
          where: { id: { in: ids } },
          select: { id: true, nickname: true, email: true },
        })
      : [];
    // Ник — единственное имя сотрудника. Пока он не выбран (человек ещё не
    // входил), в журнале банов честнее показать email, чем пустоту.
    const nameById = new Map(users.map((u) => [u.id, u.nickname ?? u.email]));
    const expired = !!ban.expiresAt && ban.expiresAt <= new Date();
    return {
      id: ban.id,
      serverId: ban.serverId,
      playerName: ban.playerName,
      playerUuid: ban.playerUuid,
      reason: ban.reason,
      expiresAt: ban.expiresAt?.toISOString() ?? null,
      createdAt: ban.createdAt.toISOString(),
      createdByName: ban.createdById ? (nameById.get(ban.createdById) ?? null) : null,
      pardonedAt: ban.pardonedAt?.toISOString() ?? null,
      pardonedByName: ban.pardonedById ? (nameById.get(ban.pardonedById) ?? null) : null,
      active: !ban.pardonedAt && !expired,
    };
  }

  async listBans(serverId: string, search?: string): Promise<MinecraftBanDto[]> {
    const bans = await this.prisma.minecraftBan.findMany({
      where: {
        serverId,
        ...(search ? { playerName: { contains: search, mode: 'insensitive' } } : {}),
      },
      orderBy: { createdAt: 'desc' },
      take: 200,
    });
    return Promise.all(bans.map((b) => this.toBanDto(b)));
  }

  /**
   * Бан: сначала запись в свою таблицу (источник истины о причине, сроке и
   * модераторе), затем RCON-команда для мгновенного эффекта на сервере.
   * Временные баны снимает крон (у ванильного сервера нет срочных банов).
   */
  async ban(
    serverId: string,
    name: string,
    reason: string,
    expiresAt: Date | null,
    actorId: string,
  ): Promise<MinecraftBanDto> {
    this.assertNickname(name);
    if (expiresAt && expiresAt.getTime() <= Date.now()) {
      throw new BadRequestException('Срок бана должен быть в будущем');
    }
    const safeReason = sanitizeCommandArgument(reason) || 'Бан модератором';

    const ban = await this.prisma.minecraftBan.create({
      data: {
        serverId,
        playerName: name,
        reason: safeReason,
        expiresAt,
        createdById: actorId,
      },
    });
    // Если сервер сейчас недоступен, запись о бане уже есть: игрока забанит
    // синхронизация при следующем успешном обращении (см. TODO в README модуля).
    await this.runCommand(serverId, `ban ${name} ${safeReason}`);
    return this.toBanDto(ban);
  }

  async pardon(serverId: string, banId: string, actorId: string): Promise<MinecraftBanDto> {
    const ban = await this.prisma.minecraftBan.findUnique({ where: { id: banId } });
    if (!ban || ban.serverId !== serverId) throw new NotFoundException('Бан не найден');
    if (ban.pardonedAt) throw new BadRequestException('Бан уже снят');

    const updated = await this.prisma.minecraftBan.update({
      where: { id: banId },
      data: { pardonedAt: new Date(), pardonedById: actorId },
    });
    await this.runCommand(serverId, `pardon ${ban.playerName}`);
    return this.toBanDto(updated);
  }

  // ---------- Whitelist ----------

  async getWhitelist(serverId: string): Promise<MinecraftWhitelistResponse> {
    const raw = await this.runCommand(serverId, 'whitelist list');
    return { players: parseWhitelist(raw) };
  }

  async addToWhitelist(serverId: string, name: string): Promise<MinecraftWhitelistResponse> {
    this.assertNickname(name);
    await this.runCommand(serverId, `whitelist add ${name}`);
    return this.getWhitelist(serverId);
  }

  async removeFromWhitelist(serverId: string, name: string): Promise<MinecraftWhitelistResponse> {
    this.assertNickname(name);
    await this.runCommand(serverId, `whitelist remove ${name}`);
    return this.getWhitelist(serverId);
  }

  // ---------- Быстрые команды ----------

  /**
   * Каталог быстрых действий.
   *
   * @param installedPlugins имена плагинов, реально стоящих на сервере;
   *   null — список получить не удалось (нет companion-плагина или он молчит).
   *   В этом случае показываем только ванильные действия: кнопка, ведущая в
   *   «Unknown command», хуже, чем её отсутствие.
   */
  listQuickCommands(installedPlugins: string[] | null): MinecraftQuickCommandDto[] {
    const installed = new Set((installedPlugins ?? []).map((name) => name.toLowerCase()));
    return MINECRAFT_QUICK_COMMANDS.filter(
      (c) => c.plugin === null || installed.has(c.plugin.toLowerCase()),
    ).map(({ id, label, description, permission, args, plugin, destructive }) => ({
      id,
      label,
      description,
      // Пометку «здесь ожидается ник» выводим из того же набора имён, по
      // которому аргумент проходит валидацию ника. Так подсказка не может
      // разойтись с проверкой: поле, куда панель подставляет игроков, — это
      // ровно то поле, значение которого потом обязано быть валидным ником.
      args: args.map((arg) =>
        NICKNAME_ARG_NAMES.has(arg.name) ? { ...arg, suggest: 'online-players' as const } : arg,
      ),
      permission,
      plugin,
      destructive,
    }));
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
        reason:
          'Список плагинов отдаёт companion-плагин. Пока он не настроен или не отвечает, ' +
          'проверить установленное невозможно.',
        installed: [],
        known: KNOWN_PLUGINS.map((p) => ({
          id: p.id,
          displayName: p.displayName,
          gives: p.gives,
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
          gives: p.gives,
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

  /** Подставляет аргументы в шаблон. Ники валидируются, остальное санитизируется. */
  buildQuickCommand(
    id: string,
    args: Record<string, string>,
  ): { commands: string[]; permission: string } {
    const definition = MINECRAFT_QUICK_COMMANDS.find((c) => c.id === id);
    if (!definition) throw new NotFoundException('Быстрая команда не найдена');

    const templates = Array.isArray(definition.template)
      ? definition.template
      : [definition.template];

    const filled = templates.map((template) => {
      let command = template;
      for (const arg of definition.args) {
        const rawValue = args[arg.name];
        if (!rawValue) {
          if (arg.required) throw new BadRequestException(`Не заполнено поле «${arg.label}»`);
          continue;
        }
        let value = NICKNAME_ARG_NAMES.has(arg.name)
          ? this.assertNickname(rawValue)
          : sanitizeCommandArgument(rawValue);
        // Значение уходит внутрь JSON-литерала команды — экранируем кавычки и
        // обратные слэши, иначе текст с кавычкой разорвёт JSON и сервер
        // отвергнет команду целиком.
        if (arg.escape === 'json') value = escapeForJsonLiteral(value);
        command = command.replaceAll(`{${arg.name}}`, value);
      }
      return command.replace(/\s+/g, ' ').trim();
    });

    // Строку с незаполненным плейсхолдером выбрасываем целиком, а не
    // подставляем в неё пустоту: «title @a subtitle с пустым текстом» — это
    // видимая игроку пустая надпись, а не отсутствие подзаголовка.
    const commands = filled.filter((command) => !/\{[a-zA-Z0-9_]+\}/.test(command));
    if (commands.length === 0) {
      throw new BadRequestException('Нечего выполнять: не заполнено ни одно поле');
    }
    return { commands, permission: definition.permission };
  }

  /** Выполняет быстрое действие: одну команду или пару, по порядку. */
  async runQuickCommand(
    serverId: string,
    id: string,
    args: Record<string, string>,
  ): Promise<string> {
    const { commands } = this.buildQuickCommand(id, args);
    // По порядку и последовательно: у команд, идущих парой, порядок значим.
    const outputs: string[] = [];
    for (const command of commands) {
      outputs.push(await this.runCommand(serverId, command));
    }
    return outputs.filter((o) => o.trim().length > 0).join('\n');
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
        `Не удалось определить UUID игрока ${player}. UUID отдаёт companion-плагин и только ` +
          'для тех, кто сейчас в сети — проверьте, что плагин настроен и игрок онлайн.',
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
      throw new BadRequestException('Сумма должна быть больше нуля');
    }
    // Дробные копейки провайдеры округляют по-своему, и в журнале потом не
    // сходится. Две цифры после запятой — то, что показывают все известные
    // плагины экономики.
    const rounded = Math.round(amount * 100) / 100;

    const result = await this.companion.changeBalance(serverId, uuid, direction, rounded);
    if (!result.ok) {
      // Валюты на сервере нет — это не состоявшаяся операция, писать в журнал
      // «изменение баланса» было бы неправдой.
      throw new BadRequestException(result.failure.reason ?? 'Валюта на этом сервере недоступна');
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
