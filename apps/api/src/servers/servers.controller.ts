import { Body, Controller, Get, Param, Post, Put, Query, Req } from '@nestjs/common';
import type { ServerActivityDto, ServerResourcesDto } from '@aurum/shared';
import { IsIn, IsOptional, IsString } from 'class-validator';
import type { Request } from 'express';
import { RequirePermission, ServerScoped } from '../rbac/rbac.decorators';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { EffectivePermissions, PermissionsService } from '../rbac/permissions.service';
import { ClientApiService } from '../pterodactyl/client-api.service';
import { PrismaService } from '../prisma/prisma.service';
import { ServersService } from './servers.service';
import { ActivityService } from './activity.service';

class PowerDto {
  @IsIn(['start', 'stop', 'restart', 'kill'])
  signal!: 'start' | 'stop' | 'restart' | 'kill';
}

class SetModuleDto {
  @IsOptional()
  @IsString()
  moduleId!: string | null;
}

@Controller('servers')
export class ServersController {
  constructor(
    private readonly servers: ServersService,
    private readonly permissions: PermissionsService,
    private readonly clientApi: ClientApiService,
    private readonly prisma: PrismaService,
    private readonly activityService: ActivityService,
  ) {}

  private eff(req: Request): Promise<EffectivePermissions> {
    const cached = (req as unknown as { effectivePermissions?: EffectivePermissions })
      .effectivePermissions;
    if (cached) return Promise.resolve(cached);
    const user = (req as unknown as { user: AuthUser }).user;
    return this.permissions.getEffectivePermissions(user.id);
  }

  @Get()
  @RequirePermission('servers.view')
  async list(@Req() req: Request) {
    return this.servers.listForUser(await this.eff(req));
  }

  @Post('sync')
  @RequirePermission('servers.manage')
  sync() {
    return this.servers.syncFromPterodactyl();
  }

  @Get(':serverId')
  @RequirePermission('servers.view')
  @ServerScoped('serverId')
  get(@Param('serverId') serverId: string) {
    return this.servers.getById(serverId);
  }

  @Put(':serverId/module')
  @RequirePermission('servers.manage')
  @ServerScoped('serverId')
  setModule(@Param('serverId') serverId: string, @Body() dto: SetModuleDto) {
    return this.servers.setModule(serverId, dto.moduleId ?? null);
  }

  @Get(':serverId/resources')
  @RequirePermission('servers.view')
  @ServerScoped('serverId')
  async resources(@Param('serverId') serverId: string): Promise<ServerResourcesDto> {
    const server = await this.prisma.server.findUniqueOrThrow({ where: { id: serverId } });
    const raw = await this.clientApi.getResources(server.pteroIdentifier);
    const MIB = 1024 * 1024;
    return {
      state: raw.current_state,
      cpuPercent: raw.resources.cpu_absolute,
      memoryBytes: raw.resources.memory_bytes,
      // Лимиты приходят из Pterodactyl в МиБ и обновляются при синке.
      memoryLimitBytes: (server.memoryLimitMb ?? 0) * MIB,
      diskBytes: raw.resources.disk_bytes,
      diskLimitBytes: (server.diskLimitMb ?? 0) * MIB,
      networkRxBytes: raw.resources.network_rx_bytes,
      networkTxBytes: raw.resources.network_tx_bytes,
      uptimeMs: raw.resources.uptime,
    };
  }

  /**
   * История онлайна по часам за последние `days` суток.
   * Данные копит сборщик игрового модуля; без модуля сетка будет пустой.
   */
  @Get(':serverId/activity')
  @RequirePermission('servers.view')
  @ServerScoped('serverId')
  activity(
    @Param('serverId') serverId: string,
    @Query('days') days?: string,
  ): Promise<ServerActivityDto> {
    return this.activityService.history(serverId, Number(days) || 7);
  }

  /**
   * Токен и адрес WebSocket-консоли Wings. Консоль — возможность ядра:
   * игровые модули объявляют capability `console`, но своей реализации не имеют.
   * Токен короткоживущий, поэтому фронт запрашивает его перед подключением
   * и повторно — по событию Wings «token expiring».
   */
  @Get(':serverId/console-token')
  @RequirePermission('servers.view')
  @ServerScoped('serverId')
  async consoleToken(@Param('serverId') serverId: string) {
    const server = await this.prisma.server.findUniqueOrThrow({ where: { id: serverId } });
    return this.clientApi.getConsoleWebsocket(server.pteroIdentifier);
  }

  @Post(':serverId/power')
  @RequirePermission('servers.power')
  @ServerScoped('serverId')
  async power(
    @CurrentUser() _user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: PowerDto,
  ) {
    const server = await this.prisma.server.findUniqueOrThrow({ where: { id: serverId } });
    await this.clientApi.sendPowerSignal(server.pteroIdentifier, dto.signal);
    return { ok: true };
  }
}
