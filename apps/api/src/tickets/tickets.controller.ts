import {
  Body,
  Controller,
  ForbiddenException,
  Get,
  Param,
  Post,
  Query,
  Req,
} from '@nestjs/common';
import { IsString, MinLength } from 'class-validator';
import type { Request } from 'express';
import { RequirePermission } from '../rbac/rbac.decorators';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { EffectivePermissions, PermissionsService } from '../rbac/permissions.service';
import { TicketsService } from './tickets.service';

class RespondDto {
  @IsString()
  @MinLength(1)
  text!: string;
}

@Controller('tickets')
export class TicketsController {
  constructor(
    private readonly tickets: TicketsService,
    private readonly permissions: PermissionsService,
  ) {}

  private eff(req: Request): Promise<EffectivePermissions> {
    const cached = (req as unknown as { effectivePermissions?: EffectivePermissions })
      .effectivePermissions;
    if (cached) return Promise.resolve(cached);
    const user = (req as unknown as { user: AuthUser }).user;
    return this.permissions.getEffectivePermissions(user.id);
  }

  /** Тикет — server-scoped ресурс: проверяем доступ к его серверу вручную по id тикета. */
  private async assertTicketAccess(req: Request, ticketId: string): Promise<EffectivePermissions> {
    const eff = await this.eff(req);
    const ticket = await this.tickets.getById(ticketId);
    if (eff.allowedServerIds !== null && !eff.allowedServerIds.has(ticket.serverId)) {
      throw new ForbiddenException('tickets.err.noServerAccess');
    }
    return eff;
  }

  @Get()
  @RequirePermission('tickets.view')
  async list(@Req() req: Request, @Query('status') status?: string) {
    return this.tickets.list(await this.eff(req), status === 'CLOSED' ? 'CLOSED' : 'OPEN');
  }

  @Get('badge')
  @RequirePermission('tickets.view')
  async badge(@Req() req: Request) {
    return { open: await this.tickets.openCount(await this.eff(req)) };
  }

  @Get(':id')
  @RequirePermission('tickets.view')
  async get(@Req() req: Request, @Param('id') id: string) {
    await this.assertTicketAccess(req, id);
    const { raw: _raw, ...dto } = await this.tickets.getById(id);
    return dto;
  }

  @Post(':id/respond')
  @RequirePermission('tickets.respond')
  async respond(
    @Req() req: Request,
    @CurrentUser() user: AuthUser,
    @Param('id') id: string,
    @Body() dto: RespondDto,
  ) {
    await this.assertTicketAccess(req, id);
    return this.tickets.respond(id, user.id, dto.text);
  }

  @Post(':id/close')
  @RequirePermission('tickets.close')
  async close(@Req() req: Request, @Param('id') id: string) {
    await this.assertTicketAccess(req, id);
    return this.tickets.close(id);
  }
}
