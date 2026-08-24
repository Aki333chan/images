import { Body, Controller, Get, Param, Post, Put, Query, Req } from '@nestjs/common';
import {
  SERVER_SORTS,
  type ServerActivityDto,
  type ServerListPrefsDto,
  type ServerMetricsDto,
  type ServerResourcesDto,
  type ServerSort,
} from '@aurum/shared';
import { ArrayMaxSize, IsArray, IsIn, IsOptional, IsString, IsUUID } from 'class-validator';
import type { Request } from 'express';
import { RequirePermission, ServerScoped } from '../rbac/rbac.decorators';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { EffectivePermissions, PermissionsService } from '../rbac/permissions.service';
import { ClientApiService } from '../pterodactyl/client-api.service';
import { PrismaService } from '../prisma/prisma.service';
import { ServersService } from './servers.service';
import { ActivityService } from './activity.service';
import { ServerMetricsService } from './metrics/server-metrics.service';

class PowerDto {
  @IsIn(['start', 'stop', 'restart', 'kill'])
  signal!: 'start' | 'stop' | 'restart' | 'kill';
}

class SetModuleDto {
  @IsOptional()
  @IsString()
  moduleId!: string | null;
}

class ServerListPrefsInput {
  @IsIn(SERVER_SORTS)
  sort!: ServerSort;

  /**
   * Порядок карточек. Потолок в 500 — не про красоту, а про то, что это
   * пользовательский ввод: без него сюда можно было бы записать список на
   * мегабайт и раздуть строку настроек.
   */
  @IsArray()
  @ArrayMaxSize(500)
  @IsUUID('4', { each: true })
  order!: string[];
}

@Controller('servers')
export class ServersController {
  constructor(
    private readonly servers: ServersService,
    private readonly permissions: PermissionsService,
    private readonly clientApi: ClientApiService,
    private readonly prisma: PrismaService,
    private readonly activityService: ActivityService,
    private readonly metrics: ServerMetricsService,
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

  /**
   * Нагрузка и онлайн по всем доступным серверам — для списка серверов.
   *
   * Читает снимки, собранные кроном, а НЕ ходит в Pterodactyl. На десятке
   * серверов поход за каждым означал бы десяток запросов на одно открытие
   * списка, и открывался бы он секундами. Цена — цифры отстают на полминуты,
   * что для списка ровно то, что нужно: точные значения показывает страница
   * сервера.
   */
  @Get('metrics')
  @RequirePermission('servers.view')
  async metricsList(@Req() req: Request): Promise<ServerMetricsDto[]> {
    const servers = await this.servers.listForUser(await this.eff(req));
    const rows = await this.prisma.server.findMany({
      where: { id: { in: servers.map((s) => s.id) } },
      select: { id: true, memoryLimitMb: true, cpuLimitPercent: true },
    });
    return this.metrics.listFor(rows);
  }

  /**
   * Личные настройки списка: критерий сортировки и свой порядок карточек.
   *
   * ЛИЧНЫЕ, а не общие для панели: перетаскивание у одного человека не должно
   * переставлять карточки всем остальным. Поэтому хранятся под ключом с id
   * пользователя, а не в поле сервера.
   */
  @Get('list-prefs')
  @RequirePermission('servers.view')
  async listPrefs(@CurrentUser() user: AuthUser): Promise<ServerListPrefsDto> {
    return this.servers.getListPrefs(user.id);
  }

  @Put('list-prefs')
  @RequirePermission('servers.view')
  async saveListPrefs(
    @CurrentUser() user: AuthUser,
    @Body() dto: ServerListPrefsInput,
  ): Promise<ServerListPrefsDto> {
    return this.servers.setListPrefs(user.id, { sort: dto.sort, order: dto.order });
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
      // Без лимита сырой процент не значит ничего: 150 — это перегрузка на
      // сервере с одним ядром и половина выделенного на сервере с тремя.
      // Нормализует его фронтенд через cpuUsage(), см. resources.ts.
      cpuLimitPercent: server.cpuLimitPercent ?? 0,
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
