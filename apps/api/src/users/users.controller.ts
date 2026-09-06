import { Body, Controller, Get, Param, ParseUUIDPipe, Patch, Post, Put } from '@nestjs/common';
import {
  IsArray,
  IsBoolean,
  IsEmail,
  IsIn,
  IsOptional,
  IsString,
} from 'class-validator';
import { ROLES, Role, type CreateUserResultDto, type PendingUserDto, UserAdminDto } from '@aurum/shared';
import { RequirePermission } from '../rbac/rbac.decorators';
import { PermissionsService } from '../rbac/permissions.service';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { UsersService } from './users.service';
import { AccountProvisioningService } from './account-provisioning.service';

/**
 * Заведение аккаунта: только адрес и роль.
 *
 * Имени здесь нет намеренно — сотрудник придумывает себе ник сам при первом
 * входе. Имя, назначенное кем-то другим, всё равно разошлось бы с тем, как
 * человек подписывается в переписке.
 */
class CreateUserDto {
  @IsEmail()
  email!: string;

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

  /** Разовое разрешение сотруднику сменить себе ник. */
  @IsOptional()
  @IsBoolean()
  nicknameChangeAllowed?: boolean;
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
  approve(
    @CurrentUser() actor: AuthUser,
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<CreateUserResultDto> {
    // Актор нужен ради языка письма: у нового сотрудника своего ещё нет.
    return this.provisioning.approve(id, actor.id);
  }

  @Post('pending/:id/reject')
  @RequirePermission('users.manage')
  async reject(@Param('id', ParseUUIDPipe) id: string): Promise<{ ok: true }> {
    await this.provisioning.reject(id);
    return { ok: true };
  }

  /**
   * Сброс пароля: сотруднику уходит новый одноразовый пароль.
   *
   * Тот же механизм, что и при заведении аккаунта, — прежние сессии
   * обрываются, а после входа панель просит задать постоянный пароль.
   * Ник при этом заново не спрашивается: он уже выбран и коллеги его знают.
   */
  @Post(':id/reset-password')
  @RequirePermission('users.manage')
  async resetPassword(
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<{ emailSent: boolean; emailError?: string }> {
    const user = await this.users.getForProvisioning(id);
    const result = await this.provisioning.issueOneTimePassword(user.id, user.email, {
      reset: true,
    });
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
