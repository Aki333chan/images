import { Body, Controller, Delete, Get, Param, Post, Put, Query } from '@nestjs/common';
import {
  MINECRAFT_PERMISSIONS,
  type MinecraftCommandResultDto,
  type MinecraftConfigStatusDto,
  type MinecraftPerformanceDto,
  type MinecraftInventoryStatusDto,
} from '@aurum/shared';
import { AuthUser, CurrentUser } from '../../auth/decorators';
import { AuditRedactBody } from '../../audit/audit.decorators';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { COMPANION_DOCS_URL, CompanionService } from './companion.service';
import { MinecraftConfigService } from './minecraft-config.service';
import { MinecraftService } from './minecraft.service';
import {
  BanDto,
  CompanionConfigDto,
  KickDto,
  QuickCommandRunDto,
  RawCommandDto,
  RconConfigDto,
  WhitelistAddDto,
} from './dto';

/**
 * Роуты модуля Minecraft. Каждый защищён правом модуля и @ServerScoped —
 * доступ к конкретному серверу проверяется ядром по текущему состоянию БД.
 *
 * Мутирующие запросы автоматически попадают в audit_log через глобальный
 * AuditInterceptor ядра (пароли и токены в метаданных редактируются).
 */
@Controller('modules/minecraft/servers/:serverId')
export class MinecraftController {
  constructor(
    private readonly minecraft: MinecraftService,
    private readonly config: MinecraftConfigService,
    private readonly companion: CompanionService,
  ) {}

  // ---------- Игроки ----------

  @Get('players')
  @RequirePermission(MINECRAFT_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  players(@Param('serverId') serverId: string) {
    return this.minecraft.getPlayers(serverId);
  }

  @Post('players/:name/kick')
  @RequirePermission(MINECRAFT_PERMISSIONS.kick)
  @ServerScoped('serverId')
  async kick(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: KickDto,
  ): Promise<MinecraftCommandResultDto> {
    return { output: await this.minecraft.kick(serverId, name, dto.reason) };
  }

  @Post('players/:name/ban')
  @RequirePermission(MINECRAFT_PERMISSIONS.ban)
  @ServerScoped('serverId')
  ban(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: BanDto,
  ) {
    return this.minecraft.ban(
      serverId,
      name,
      dto.reason,
      dto.expiresAt ? new Date(dto.expiresAt) : null,
      user.id,
    );
  }

  // ---------- Баны ----------

  @Get('bans')
  @RequirePermission(MINECRAFT_PERMISSIONS.ban)
  @ServerScoped('serverId')
  listBans(@Param('serverId') serverId: string, @Query('search') search?: string) {
    return this.minecraft.listBans(serverId, search || undefined);
  }

  @Post('bans/:banId/pardon')
  @RequirePermission(MINECRAFT_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  pardon(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('banId') banId: string,
  ) {
    return this.minecraft.pardon(serverId, banId, user.id);
  }

  // ---------- Whitelist ----------

  @Get('whitelist')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelist(@Param('serverId') serverId: string) {
    return this.minecraft.getWhitelist(serverId);
  }

  @Post('whitelist')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelistAdd(@Param('serverId') serverId: string, @Body() dto: WhitelistAddDto) {
    return this.minecraft.addToWhitelist(serverId, dto.name);
  }

  @Delete('whitelist/:name')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelistRemove(@Param('serverId') serverId: string, @Param('name') name: string) {
    return this.minecraft.removeFromWhitelist(serverId, name);
  }

  // ---------- Команды ----------

  /** Произвольная RCON-команда: только ГМ и Админ (см. defaultRoles манифеста). */
  @Post('command')
  @RequirePermission(MINECRAFT_PERMISSIONS.commandRaw)
  @ServerScoped('serverId')
  async rawCommand(
    @Param('serverId') serverId: string,
    @Body() dto: RawCommandDto,
  ): Promise<MinecraftCommandResultDto> {
    return { output: await this.minecraft.runCommand(serverId, dto.command) };
  }

  @Get('quick-commands')
  @RequirePermission(MINECRAFT_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  quickCommands() {
    return { commands: this.minecraft.listQuickCommands() };
  }

  @Post('quick-commands/:commandId')
  @RequirePermission(MINECRAFT_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  async runQuickCommand(
    @Param('serverId') serverId: string,
    @Param('commandId') commandId: string,
    @Body() dto: QuickCommandRunDto,
  ): Promise<MinecraftCommandResultDto> {
    return {
      output: await this.minecraft.runQuickCommand(serverId, commandId, dto.args ?? {}),
    };
  }

  // ---------- Инвентарь ----------

  /**
   * Доступен ли инвентарь на этом сервере. Отдельный роут нужен, чтобы вкладка
   * могла отличить «плагин не установлен» от «этот игрок офлайн», не спрашивая
   * инвентарь наугад. Секретов не содержит и доступен всем, кто видит инвентарь.
   */
  @Get('inventory-status')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryView)
  @ServerScoped('serverId')
  async inventoryStatus(@Param('serverId') serverId: string): Promise<MinecraftInventoryStatusDto> {
    return {
      companionConfigured: await this.companion.isConfigured(serverId),
      docsUrl: COMPANION_DOCS_URL,
    };
  }

  @Get('inventory/:name')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryView)
  @ServerScoped('serverId')
  inventory(@Param('serverId') serverId: string, @Param('name') name: string) {
    return this.minecraft.getInventory(serverId, name);
  }

  /** TPS и время тика. Право то же, что на просмотр игроков. */
  @Get('performance')
  @RequirePermission(MINECRAFT_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  performance(@Param('serverId') serverId: string): Promise<MinecraftPerformanceDto> {
    return this.minecraft.getPerformance(serverId);
  }

  // ---------- Настройки подключения ----------

  /** Только флаги — ни пароля, ни хоста, ни адреса плагина наружу. */
  @Get('config')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  async configStatus(@Param('serverId') serverId: string): Promise<MinecraftConfigStatusDto> {
    const creds = await this.config.read(serverId);
    return {
      rconConfigured: !!creds.rcon,
      companionConfigured: !!creds.companion,
      lastSeenAt: creds.lastSeenAt ?? null,
    };
  }

  @Put('config/rcon')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес через туннель, порт и пароль
  async setRcon(@Param('serverId') serverId: string, @Body() dto: RconConfigDto) {
    await this.config.setRcon(serverId, dto.host, dto.port, dto.password);
    // Сразу проверяем связь, чтобы ошибка не всплыла позже у модератора.
    const output = await this.minecraft.runCommand(serverId, 'list');
    return { ok: true, probe: output.slice(0, 200) };
  }

  @Put('config/companion')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // адрес companion-плагина и его токен
  async setCompanion(@Param('serverId') serverId: string, @Body() dto: CompanionConfigDto) {
    await this.config.setCompanion(serverId, dto.baseUrl ?? null, dto.token ?? null);
    return { ok: true, configured: await this.companion.isConfigured(serverId) };
  }
}
