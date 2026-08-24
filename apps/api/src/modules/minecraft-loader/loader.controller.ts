import { Body, Controller, Delete, Get, Param, Post, Put, Query } from '@nestjs/common';
import {
  MINECRAFT_FORGE_PERMISSIONS,
  MINECRAFT_NEOFORGE_PERMISSIONS,
  type MinecraftCommandResultDto,
  type MinecraftConfigStatusDto,
} from '@aurum/shared';
import { AuthUser, CurrentUser } from '../../auth/decorators';
import { AuditRedactBody } from '../../audit/audit.decorators';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';
import {
  BanDto,
  KickDto,
  QuickCommandRunDto,
  RawCommandDto,
  RconConfigDto,
  WhitelistAddDto,
} from '../minecraft-shared/dto';

/**
 * Роуты сервера Minecraft на загрузчике модов — Forge или NeoForge.
 *
 * ФОРМА РОУТОВ ТА ЖЕ, ЧТО У Paper-модуля, и это не совпадение: за ними стоят
 * одни и те же команды сервера, выполняемые одним и тем же VanillaRconService.
 * Отличается только префикс пути и набор прав — у каждого модуля свой.
 *
 * ЧЕГО ЗДЕСЬ НЕТ. Инвентаря, прав через LuckPerms, валюты через Vault, списка
 * плагинов, TPS. Всё это работа companion-плагина Bukkit либо команды
 * Paper/Spigot, и на Forge с NeoForge их не существует. Показывать такие
 * вкладки «на будущее» значило бы обещать то, чего сервер не умеет: в
 * манифесте эти возможности честно выключены.
 *
 * ПОЧЕМУ ДВА КЛАССА-НАСЛЕДНИКА, А НЕ ОДИН С ПАРАМЕТРОМ. Nest сопоставляет
 * маршруты по декоратору класса, а право проверяется декоратором метода —
 * и то, и другое читается при старте, а не в рантайме. Общий предок держит
 * всю логику, наследники задают только префикс и ключи прав, так что
 * дублирования кода нет, а маршруты и права остаются раздельными по-настоящему.
 */
export abstract class LoaderControllerBase {
  constructor(
    protected readonly vanilla: VanillaRconService,
    protected readonly config: MinecraftConfigService,
  ) {}

  /**
   * Ключ права быстрых команд подставляется в каталог: в самом каталоге
   * записан ключ Paper, а фронтенд сверяет право пользователя именно с тем,
   * что приехало в ответе.
   */
  protected abstract quickCommandPermission(): string;

  // -------------------------------------------------------------- Игроки

  players(serverId: string) {
    return this.vanilla.getPlayers(serverId);
  }

  async kickPlayer(serverId: string, name: string, dto: KickDto): Promise<MinecraftCommandResultDto> {
    return { output: await this.vanilla.kick(serverId, name, dto.reason) };
  }

  banPlayer(user: AuthUser, serverId: string, name: string, dto: BanDto) {
    return this.vanilla.ban(
      serverId,
      name,
      dto.reason,
      dto.expiresAt ? new Date(dto.expiresAt) : null,
      user.id,
    );
  }

  // ---------------------------------------------------------------- Баны

  bans(serverId: string, search?: string) {
    return this.vanilla.listBans(serverId, search || undefined);
  }

  pardonBan(user: AuthUser, serverId: string, banId: string) {
    return this.vanilla.pardon(serverId, banId, user.id);
  }

  // ----------------------------------------------------------- Whitelist

  whitelist(serverId: string) {
    return this.vanilla.getWhitelist(serverId);
  }

  whitelistAdd(serverId: string, dto: WhitelistAddDto) {
    return this.vanilla.addToWhitelist(serverId, dto.name);
  }

  whitelistRemove(serverId: string, name: string) {
    return this.vanilla.removeFromWhitelist(serverId, name);
  }

  // ------------------------------------------------------------- Команды

  async rawCommand(serverId: string, dto: RawCommandDto): Promise<MinecraftCommandResultDto> {
    return { output: await this.vanilla.runCommand(serverId, dto.command) };
  }

  /**
   * Каталог быстрых действий.
   *
   * Список плагинов НЕ спрашивается и передаётся как null осознанно: плагинов
   * Bukkit на загрузчике модов не бывает, спрашивать не у кого. Остаются
   * ванильные действия — они работают на любом сервере игры.
   */
  quickCommands(serverId: string) {
    void serverId;
    return { commands: this.vanilla.listQuickCommands(null, this.quickCommandPermission()) };
  }

  async runQuickCommand(
    serverId: string,
    commandId: string,
    dto: QuickCommandRunDto,
  ): Promise<MinecraftCommandResultDto> {
    return { output: await this.vanilla.runQuickCommand(serverId, commandId, dto.args ?? {}) };
  }

  // ------------------------------------------------------------ Настройки

  /** Наружу уходят только флаги: ни пароля, ни адреса RCON здесь нет. */
  async configStatus(serverId: string): Promise<MinecraftConfigStatusDto> {
    const creds = await this.config.read(serverId);
    return {
      rconConfigured: !!creds.rcon,
      // companion-плагин Bukkit на загрузчике модов не существует — здесь
      // это не «пока не настроен», а «не бывает».
      companionConfigured: false,
      lastSeenAt: creds.lastSeenAt ?? null,
    };
  }

  async setRcon(serverId: string, dto: RconConfigDto) {
    await this.config.setRcon(serverId, dto.host, dto.port, dto.password);
    // Сразу проверяем связь, чтобы ошибка не всплыла позже у модератора.
    const output = await this.vanilla.runCommand(serverId, 'list');
    return { ok: true, probe: output.slice(0, 200) };
  }
}

@Controller('modules/minecraft-forge/servers/:serverId')
export class MinecraftForgeController extends LoaderControllerBase {
  /**
   * Конструктор объявлен явно, хотя ничего не добавляет к родительскому.
   *
   * Это не формальность: Nest определяет, что внедрять, по метаданным
   * `design:paramtypes`, а TypeScript пишет их только для класса, у которого
   * конструктор есть в исходнике. У наследника без своего конструктора
   * метаданные пустые — зависимости не внедряются, и падает это не при
   * старте, а на первом же запросе, с невнятным «Cannot read properties of
   * undefined».
   */
  constructor(vanilla: VanillaRconService, config: MinecraftConfigService) {
    super(vanilla, config);
  }

  protected quickCommandPermission(): string {
    return MINECRAFT_FORGE_PERMISSIONS.quickCommands;
  }

  @Get('players')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  override players(@Param('serverId') serverId: string) {
    return super.players(serverId);
  }

  @Post('players/:name/kick')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.kick)
  @ServerScoped('serverId')
  override kickPlayer(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: KickDto,
  ) {
    return super.kickPlayer(serverId, name, dto);
  }

  @Post('players/:name/ban')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.ban)
  @ServerScoped('serverId')
  override banPlayer(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: BanDto,
  ) {
    return super.banPlayer(user, serverId, name, dto);
  }

  @Get('bans')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.ban)
  @ServerScoped('serverId')
  override bans(@Param('serverId') serverId: string, @Query('search') search?: string) {
    return super.bans(serverId, search);
  }

  @Post('bans/:banId/pardon')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  override pardonBan(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('banId') banId: string,
  ) {
    return super.pardonBan(user, serverId, banId);
  }

  @Get('whitelist')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelist(@Param('serverId') serverId: string) {
    return super.whitelist(serverId);
  }

  @Post('whitelist')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelistAdd(@Param('serverId') serverId: string, @Body() dto: WhitelistAddDto) {
    return super.whitelistAdd(serverId, dto);
  }

  @Delete('whitelist/:name')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelistRemove(@Param('serverId') serverId: string, @Param('name') name: string) {
    return super.whitelistRemove(serverId, name);
  }

  /** Произвольная RCON-команда: только ГМ и Админ (см. defaultRoles манифеста). */
  @Post('command')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.commandRaw)
  @ServerScoped('serverId')
  override rawCommand(@Param('serverId') serverId: string, @Body() dto: RawCommandDto) {
    return super.rawCommand(serverId, dto);
  }

  @Get('quick-commands')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  override quickCommands(@Param('serverId') serverId: string) {
    return super.quickCommands(serverId);
  }

  @Post('quick-commands/:commandId')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  override runQuickCommand(
    @Param('serverId') serverId: string,
    @Param('commandId') commandId: string,
    @Body() dto: QuickCommandRunDto,
  ) {
    return super.runQuickCommand(serverId, commandId, dto);
  }

  @Get('config')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.configure)
  @ServerScoped('serverId')
  override configStatus(@Param('serverId') serverId: string) {
    return super.configStatus(serverId);
  }

  @Put('config/rcon')
  @RequirePermission(MINECRAFT_FORGE_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес через туннель, порт и пароль
  override setRcon(@Param('serverId') serverId: string, @Body() dto: RconConfigDto) {
    return super.setRcon(serverId, dto);
  }
}

@Controller('modules/minecraft-neoforge/servers/:serverId')
export class MinecraftNeoForgeController extends LoaderControllerBase {
  /**
   * Конструктор объявлен явно, хотя ничего не добавляет к родительскому.
   *
   * Это не формальность: Nest определяет, что внедрять, по метаданным
   * `design:paramtypes`, а TypeScript пишет их только для класса, у которого
   * конструктор есть в исходнике. У наследника без своего конструктора
   * метаданные пустые — зависимости не внедряются, и падает это не при
   * старте, а на первом же запросе, с невнятным «Cannot read properties of
   * undefined».
   */
  constructor(vanilla: VanillaRconService, config: MinecraftConfigService) {
    super(vanilla, config);
  }

  protected quickCommandPermission(): string {
    return MINECRAFT_NEOFORGE_PERMISSIONS.quickCommands;
  }

  @Get('players')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  override players(@Param('serverId') serverId: string) {
    return super.players(serverId);
  }

  @Post('players/:name/kick')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.kick)
  @ServerScoped('serverId')
  override kickPlayer(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: KickDto,
  ) {
    return super.kickPlayer(serverId, name, dto);
  }

  @Post('players/:name/ban')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.ban)
  @ServerScoped('serverId')
  override banPlayer(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: BanDto,
  ) {
    return super.banPlayer(user, serverId, name, dto);
  }

  @Get('bans')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.ban)
  @ServerScoped('serverId')
  override bans(@Param('serverId') serverId: string, @Query('search') search?: string) {
    return super.bans(serverId, search);
  }

  @Post('bans/:banId/pardon')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  override pardonBan(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('banId') banId: string,
  ) {
    return super.pardonBan(user, serverId, banId);
  }

  @Get('whitelist')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelist(@Param('serverId') serverId: string) {
    return super.whitelist(serverId);
  }

  @Post('whitelist')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelistAdd(@Param('serverId') serverId: string, @Body() dto: WhitelistAddDto) {
    return super.whitelistAdd(serverId, dto);
  }

  @Delete('whitelist/:name')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  override whitelistRemove(@Param('serverId') serverId: string, @Param('name') name: string) {
    return super.whitelistRemove(serverId, name);
  }

  /** Произвольная RCON-команда: только ГМ и Админ (см. defaultRoles манифеста). */
  @Post('command')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.commandRaw)
  @ServerScoped('serverId')
  override rawCommand(@Param('serverId') serverId: string, @Body() dto: RawCommandDto) {
    return super.rawCommand(serverId, dto);
  }

  @Get('quick-commands')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  override quickCommands(@Param('serverId') serverId: string) {
    return super.quickCommands(serverId);
  }

  @Post('quick-commands/:commandId')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  override runQuickCommand(
    @Param('serverId') serverId: string,
    @Param('commandId') commandId: string,
    @Body() dto: QuickCommandRunDto,
  ) {
    return super.runQuickCommand(serverId, commandId, dto);
  }

  @Get('config')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.configure)
  @ServerScoped('serverId')
  override configStatus(@Param('serverId') serverId: string) {
    return super.configStatus(serverId);
  }

  @Put('config/rcon')
  @RequirePermission(MINECRAFT_NEOFORGE_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес через туннель, порт и пароль
  override setRcon(@Param('serverId') serverId: string, @Body() dto: RconConfigDto) {
    return super.setRcon(serverId, dto);
  }
}
