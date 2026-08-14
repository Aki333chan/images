import { Body, Controller, Get, Param, Post, Put, Req } from '@nestjs/common';
import { IsIn, IsOptional, IsString } from 'class-validator';
import type { Request } from 'express';
import { RequirePermission, ServerScoped } from '../rbac/rbac.decorators';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { EffectivePermissions, PermissionsService } from '../rbac/permissions.service';
import { ClientApiService } from '../pterodactyl/client-api.service';
import { PrismaService } from '../prisma/prisma.service';
import { ServersService } from './servers.service';

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
  async resources(@Param('serverId') serverId: string) {
    const server = await this.prisma.server.findUniqueOrThrow({ where: { id: serverId } });
    return this.clientApi.getResources(server.pteroIdentifier);
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
