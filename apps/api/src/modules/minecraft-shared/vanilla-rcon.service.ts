import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import type {
  MinecraftBanDto,
  MinecraftPlayersResponse,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { MinecraftConfigService } from './minecraft-config.service';
import { RconService } from './rcon/rcon.service';
import {
  escapeForJsonLiteral,
  isValidNickname,
  parsePlayerList,
  parseWhitelist,
  sanitizeCommandArgument,
} from './minecraft-parsers';
import { MINECRAFT_QUICK_COMMANDS, NICKNAME_ARG_NAMES } from './quick-commands.config';

/**
 * Операции, которые умеет ЛЮБОЙ сервер Minecraft по RCON.
 *
 * ЧТО ЗДЕСЬ ЕСТЬ И ПОЧЕМУ ИМЕННО ЭТО. Всё, что ниже, — команды самого сервера
 * Minecraft: `list`, `kick`, `ban`, `pardon`, `whitelist`. Они не зависят ни
 * от ядра семейства Bukkit, ни от загрузчика модов: RCON это часть базового
 * сервера, а не Paper и не Forge. Поэтому один и тот же код обслуживает и
 * Paper-модуль, и Forge, и NeoForge.
 *
 * ЧЕГО ЗДЕСЬ НЕТ И НЕ ДОЛЖНО ПОЯВИТЬСЯ. Ничего, что предполагает Bukkit:
 * список плагинов, инвентарь, права через LuckPerms, валюта через Vault,
 * `tps` и `mspt`. Всё это либо команды Paper/Spigot, либо работа
 * companion-плагина, и на сервере с Forge его нет и быть не может. Такие
 * вещи живут в модуле Paper и остаются там.
 *
 * Сервис не знает, какой модуль его вызвал, и знать не должен: команды одни и
 * те же. Единственное, что владелец подставляет от себя, — ключ права в
 * каталоге быстрых команд, см. listQuickCommands.
 */
@Injectable()
export class VanillaRconService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: MinecraftConfigService,
    private readonly rcon: RconService,
  ) {}

  /** Единая точка выполнения RCON-команд: берёт креды и отмечает доступность. */
  async runCommand(serverId: string, command: string): Promise<string> {
    const rconConfig = await this.config.requireRcon(serverId);
    const output = await this.rcon.execute(serverId, rconConfig, command);
    await this.config.markSeen(serverId).catch(() => undefined);
    return output;
  }

  assertNickname(name: string): string {
    if (!isValidNickname(name)) {
      throw new BadRequestException('Некорректный ник Minecraft (3–16 символов A-Z, 0-9, _)');
    }
    return name;
  }

  // ------------------------------------------------------------- Игроки

  /**
   * Список игроков по команде `list`.
   *
   * По RCON доступны только ники: UUID и пинг умеет отдать лишь плагин на
   * стороне сервера, а его на загрузчиках модов нет. Поля честно остаются
   * пустыми вместо выдуманных значений.
   */
  async getPlayers(serverId: string): Promise<MinecraftPlayersResponse> {
    const raw = await this.runCommand(serverId, 'list');
    const parsed = parsePlayerList(raw);
    return {
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

  async kick(serverId: string, name: string, reason: string): Promise<string> {
    this.assertNickname(name);
    const safeReason = sanitizeCommandArgument(reason) || 'Кик модератором';
    return this.runCommand(serverId, `kick ${name} ${safeReason}`);
  }

  // --------------------------------------------------------------- Баны
  //
  // Таблица банов общая для всего семейства Minecraft и разделена по
  // serverId. Общая она не по недосмотру: причина, срок и модератор — это
  // данные панели, а не сервера, и они одинаковы независимо от того, на чём
  // сервер запущен. У самого Minecraft срочных банов нет вовсе, поэтому
  // истёкшие снимает крон (см. ban-expiry.processor).

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
      data: { serverId, playerName: name, reason: safeReason, expiresAt, createdById: actorId },
    });
    // Если сервер сейчас недоступен, запись о бане уже есть: игрока забанит
    // синхронизация при следующем успешном обращении.
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

  // ---------------------------------------------------------- Whitelist

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

  // ---------------------------------------------------- Быстрые команды

  /**
   * Каталог быстрых действий.
   *
   * @param installedPlugins имена плагинов Bukkit, реально стоящих на сервере;
   *   null — плагинов нет или список получить не удалось. Тогда остаются
   *   только ванильные действия: кнопка, ведущая в «Unknown command», хуже,
   *   чем её отсутствие.
   *
   * Для Forge и NeoForge сюда ВСЕГДА приходит null, и это не заглушка:
   * плагинов Bukkit на них не существует, спрашивать не у кого и незачем.
   * Ванильные действия при этом работают полностью — `time set day`,
   * `weather clear`, `say`, `gamemode` и остальные есть на любом сервере.
   */
  listQuickCommands(
    installedPlugins: string[] | null,
    permissionKey?: string,
  ): MinecraftQuickCommandDto[] {
    const installed = new Set((installedPlugins ?? []).map((name) => name.toLowerCase()));
    return MINECRAFT_QUICK_COMMANDS.filter(
      (c) => c.plugin === null || installed.has(c.plugin.toLowerCase()),
    ).map(({ id, label, description, permission, args, plugin, destructive }) => ({
      id,
      label,
      description,
      // Пометку «здесь ожидается ник» выводим из того же набора имён, по
      // которому аргумент проходит валидацию ника, — так подсказка не может
      // разойтись с проверкой.
      args: args.map((arg) =>
        NICKNAME_ARG_NAMES.has(arg.name) ? { ...arg, suggest: 'online-players' as const } : arg,
      ),
      // Право у каждого модуля своё: `minecraft.quick-commands` не должно
      // открывать кнопки на Forge-сервере. В каталоге записан ключ Paper —
      // владелец подменяет его своим, иначе фронтенд спрятал бы кнопки от
      // того, у кого право на этот сервер как раз есть.
      permission: permissionKey ?? permission,
      plugin,
      destructive,
    }));
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
}

