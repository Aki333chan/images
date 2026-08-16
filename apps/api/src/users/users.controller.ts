import { Body, Controller, Get, Param, ParseUUIDPipe, Patch, Post, Put } from '@nestjs/common';
import {
  IsArray,
  IsBoolean,
  IsEmail,
  IsIn,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';
import { ROLES, Role, type CreateUserResultDto, type PendingUserDto, UserAdminDto } from '@aurum/shared';
import { RequirePermission } from '../rbac/rbac.decorators';
import { PermissionsService } from '../rbac/permissions.service';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { UsersService } from './users.service';
import { AccountProvisioningService } from './account-provisioning.service';

class CreateUserDto {
  @IsEmail()
  email!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(80)
  displayName!: string;

  @IsIn(ROLES as unknown as string[])
  role!: Role;
}

class UpdateUserDto {
  @IsOptional()
  @IsIn(ROLES as unknown as string[])
  role?: Role;

  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @IsOptional()
  @IsString()
  @MinLength(1)
  displayName?: string;
}

class SetServersDto {
  @IsArray()
  @IsString({ each: true })
  serverIds!: string[];
}

/**
 * Управление учётными записями.
 *
 * Право на уровне класса НЕ ставится: часть маршрутов доступна Админу с
 * users.create.moderator, остальные — только ГМ с users.manage. Каждый
 * маршрут объявляет своё требование явно.
 */
@Controller('users')
export class UsersController {
  constructor(
    private readonly users: UsersService,
    private readonly provisioning: AccountProvisioningService,
    private readonly permissions: PermissionsService,
  ) {}

  /** Есть ли у пользователя полное право распоряжаться учётками. */
  private async canManageUsers(userId: string): Promise<boolean> {
    const eff = await this.permissions.getEffectivePermissions(userId);
    return eff.permissions.has('users.manage');
  }

  @Get()
  @RequirePermission('users.manage')
  list(): Promise<UserAdminDto[]> {
    return this.users.list();
  }

  /**
   * Создание учётной записи.
   *
   * Доступно и ГМ (users.manage), и Админу (users.create.moderator) —
   * `anyOf`, а не два отдельных маршрута: путь один, различается только
   * то, что каждому из них позволено дальше, и это решает сам сервис.
   */
  @Post()
  @RequirePermission('users.manage', 'users.create.moderator')
  async create(
    @CurrentUser() actor: AuthUser,
    @Body() dto: CreateUserDto,
  ): Promise<CreateUserResultDto> {
    const full = await this.canManageUsers(actor.id);
    return this.provisioning.create({ id: actor.id }, full, dto);
  }

  // ---------------------------------------- Заявки от Админов (только ГМ)

  @Get('pending')
  @RequirePermission('users.manage')
  pending(): Promise<PendingUserDto[]> {
    return this.provisioning.listPending();
  }

  @Post('pending/:id/approve')
  @RequirePermission('users.manage')
  approve(@Param('id', ParseUUIDPipe) id: string): Promise<CreateUserResultDto> {
    return this.provisioning.approve(id);
  }

  @Post('pending/:id/reject')
  @RequirePermission('users.manage')
  async reject(@Param('id', ParseUUIDPipe) id: string): Promise<{ ok: true }> {
    await this.provisioning.reject(id);
    return { ok: true };
  }

  /** Выдать новый одноразовый пароль: прежний протух или потерян. */
  @Post(':id/resend-password')
  @RequirePermission('users.manage')
  async resendPassword(
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<{ emailSent: boolean; emailError?: string }> {
    const user = await this.users.getForProvisioning(id);
    const result = await this.provisioning.issueOneTimePassword(
      user.id,
      user.email,
      user.displayName,
    );
    return { emailSent: result.sent, ...(result.error ? { emailError: result.error } : {}) };
  }

  @Patch(':id')
  @RequirePermission('users.manage')
  update(
    @CurrentUser() actor: AuthUser,
    @Param('id') id: string,
    @Body() dto: UpdateUserDto,
  ): Promise<UserAdminDto> {
    return this.users.update(actor.id, id, dto);
  }

  @Put(':id/servers')
  @RequirePermission('users.manage')
  setServers(@Param('id') id: string, @Body() dto: SetServersDto): Promise<UserAdminDto> {
    return this.users.setServerAccess(id, dto.serverIds);
  }
}
