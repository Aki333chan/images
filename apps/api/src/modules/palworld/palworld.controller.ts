import { Body, Controller, ForbiddenException, Get, Param, Post, Put, Query } from '@nestjs/common';
import {
  PALWORLD_PERMISSIONS,
  type PalworldCommandResultDto,
  type PalworldConfigStatusDto,
  type PalworldPlayersResponse,
  type PalworldServerStateDto,
} from '@aurum/shared';
import { AuthUser, CurrentUser } from '../../auth/decorators';
import { AuditRedactBody } from '../../audit/audit.decorators';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { PalworldApiService } from './palworld-api.service';
import { PalworldConfigService } from './palworld-config.service';
import { PalworldService } from './palworld.service';
import { ActionRunDto, ApiConfigDto, BanDto, KickDto } from './dto';

/**
 * Роуты модуля Palworld. Как и в Minecraft: каждый закрыт правом модуля и
 * @ServerScoped, доступ к конкретному серверу проверяется ядром по текущему
 * состоянию БД, а мутирующие запросы попадают в audit_log.
 *
 * Чего здесь НЕТ и почему: whitelist и инвентарь — REST API Palworld таких
 * возможностей не даёт вовсе; произвольная команда — эндпоинта «выполни
 * команду» в API тоже нет, набор действий закрытый.
 */
@Controller('modules/palworld/servers/:serverId')
export class PalworldController {
  constructor(
    private readonly palworld: PalworldService,
    private readonly config: PalworldConfigService,
    private readonly api: PalworldApiService,
  ) {}

  // ---------- Игроки ----------

  @Get('players')
  @RequirePermission(PALWORLD_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  players(@Param('serverId') serverId: string): Promise<PalworldPlayersResponse> {
    return this.palworld.getPlayers(serverId);
  }

  /** Имя, версия и показатели сервера: FPS, время кадра, аптайм. */
  @Get('state')
  @RequirePermission(PALWORLD_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  state(@Param('serverId') serverId: string): Promise<PalworldServerStateDto> {
    return this.palworld.getServerState(serverId);
  }

  @Post('players/kick')
  @RequirePermission(PALWORLD_PERMISSIONS.kick)
  @ServerScoped('serverId')
  kick(@Param('serverId') serverId: string, @Body() dto: KickDto) {
    return this.palworld.kick(serverId, dto.userId, dto.reason ?? '');
  }

  @Post('players/ban')
  @RequirePermission(PALWORLD_PERMISSIONS.ban)
  @ServerScoped('serverId')
  ban(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: BanDto,
  ) {
    return this.palworld.ban(
      serverId,
      dto.userId,
      dto.playerName ?? dto.userId,
      dto.reason ?? '',
      user.id,
    );
  }

  // ---------- Баны ----------

  @Get('bans')
  @RequirePermission(PALWORLD_PERMISSIONS.ban)
  @ServerScoped('serverId')
  listBans(@Param('serverId') serverId: string, @Query('search') search?: string) {
    return this.palworld.listBans(serverId, search || undefined);
  }

  @Post('bans/:banId/pardon')
  @RequirePermission(PALWORLD_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  pardon(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('banId') banId: string,
  ) {
    return this.palworld.pardon(serverId, banId, user.id);
  }

  // ---------- Быстрые действия ----------

  @Get('actions')
  @RequirePermission(PALWORLD_PERMISSIONS.quickActions)
  @ServerScoped('serverId')
  actions() {
    return { actions: this.palworld.listActions() };
  }

  /**
   * Обычные действия: объявление в чат, сохранение мира.
   *
   * Остановка сервера сюда НЕ попадает — у неё свой роут со своим правом.
   * Иначе модератор с правом на объявления мог бы выключить сервер, послав
   * сюда actionId остановки. Проверять право «по содержимому действия»
   * внутри одного роута нельзя: права берутся только из БД, а тянуть сюда
   * PermissionsService значит замкнуть кольцо импортов
   * (permissions.service → module-registry → palworld.def → этот контроллер).
   */
  @Post('actions/:actionId')
  @RequirePermission(PALWORLD_PERMISSIONS.quickActions)
  @ServerScoped('serverId')
  runAction(
    @Param('serverId') serverId: string,
    @Param('actionId') actionId: string,
    @Body() dto: ActionRunDto,
  ): Promise<PalworldCommandResultDto> {
    const action = this.palworld.findAction(actionId);
    if (action.permission !== PALWORLD_PERMISSIONS.quickActions) {
      throw new ForbiddenException(
        `Действие «${action.label}» выполняется отдельным роутом со своим правом`,
      );
    }
    return this.palworld.runAction(serverId, actionId, dto.args ?? {});
  }

  /** Остановка с предупреждением игроков — отдельное право. */
  @Post('shutdown')
  @RequirePermission(PALWORLD_PERMISSIONS.shutdown)
  @ServerScoped('serverId')
  shutdown(
    @Param('serverId') serverId: string,
    @Body() dto: ActionRunDto,
  ): Promise<PalworldCommandResultDto> {
    return this.palworld.runAction(serverId, 'shutdown', dto.args ?? {});
  }

  // ---------- Настройки подключения ----------

  /** Только флаги — ни адреса, ни пароля наружу. */
  @Get('config')
  @RequirePermission(PALWORLD_PERMISSIONS.configure)
  @ServerScoped('serverId')
  async configStatus(@Param('serverId') serverId: string): Promise<PalworldConfigStatusDto> {
    const creds = await this.config.read(serverId);
    return { configured: !!creds.palworld, lastSeenAt: creds.lastSeenAt ?? null };
  }

  @Put('config')
  @RequirePermission(PALWORLD_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес и пароль администратора сервера
  async setConfig(@Param('serverId') serverId: string, @Body() dto: ApiConfigDto) {
    await this.config.setApi(serverId, dto.baseUrl ?? null, dto.adminPassword ?? null);
    if (!dto.baseUrl) return { ok: true, configured: false };

    // Сразу проверяем связь, чтобы ошибка не всплыла позже у модератора.
    const state = await this.palworld.getServerState(serverId);
    return {
      ok: true,
      configured: await this.api.isConfigured(serverId),
      probe: state.available ? (state.serverName ?? 'сервер ответил') : state.reason,
    };
  }
}
