import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import type {
  MinecraftBanDto,
  MinecraftInventoryResponse,
  MinecraftPlayersResponse,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { CompanionService } from './companion.service';
import { MinecraftConfigService } from './minecraft-config.service';
import {
  isValidNickname,
  parsePlayerList,
  parseWhitelist,
  sanitizeCommandArgument,
} from './minecraft-parsers';
import { MINECRAFT_QUICK_COMMANDS, NICKNAME_ARG_NAMES } from './quick-commands.config';
import { RconService } from './rcon/rcon.service';

@Injectable()
export class MinecraftService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: MinecraftConfigService,
    private readonly rcon: RconService,
    private readonly companion: CompanionService,
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
          select: { id: true, displayName: true },
        })
      : [];
    const nameById = new Map(users.map((u) => [u.id, u.displayName]));
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

  listQuickCommands(): MinecraftQuickCommandDto[] {
    return MINECRAFT_QUICK_COMMANDS.map(({ id, label, description, permission, args }) => ({
      id,
      label,
      description,
      permission,
      args,
    }));
  }

  /** Подставляет аргументы в шаблон. Ники валидируются, остальное санитизируется. */
  buildQuickCommand(id: string, args: Record<string, string>): { command: string; permission: string } {
    const definition = MINECRAFT_QUICK_COMMANDS.find((c) => c.id === id);
    if (!definition) throw new NotFoundException('Быстрая команда не найдена');

    let command = definition.template;
    for (const arg of definition.args) {
      const rawValue = args[arg.name];
      if (!rawValue) {
        if (arg.required) throw new BadRequestException(`Не заполнено поле «${arg.label}»`);
        continue;
      }
      const value = NICKNAME_ARG_NAMES.has(arg.name)
        ? this.assertNickname(rawValue)
        : sanitizeCommandArgument(rawValue);
      command = command.replaceAll(`{${arg.name}}`, value);
    }
    // Незаполненные необязательные плейсхолдеры не должны утечь в команду.
    command = command.replace(/\{[a-zA-Z0-9_]+\}/g, '').replace(/\s+/g, ' ').trim();
    return { command, permission: definition.permission };
  }

  async runQuickCommand(serverId: string, id: string, args: Record<string, string>): Promise<string> {
    const { command } = this.buildQuickCommand(id, args);
    return this.runCommand(serverId, command);
  }

  // ---------- Инвентарь ----------

  async getInventory(serverId: string, player: string): Promise<MinecraftInventoryResponse> {
    this.assertNickname(player);
    return this.companion.getInventory(serverId, player);
  }
}
