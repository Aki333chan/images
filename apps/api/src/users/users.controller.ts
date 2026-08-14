import { Body, Controller, Get, Param, Patch, Post, Put } from '@nestjs/common';
import { IsArray, IsBoolean, IsEmail, IsIn, IsOptional, IsString, MinLength } from 'class-validator';
import { ROLES, Role, UserAdminDto } from '@aurum/shared';
import { RequirePermission } from '../rbac/rbac.decorators';
import { CurrentUser, AuthUser } from '../auth/decorators';
import { UsersService } from './users.service';

class CreateUserDto {
  @IsEmail()
  email!: string;

  @IsString()
  @MinLength(8)
  password!: string;

  @IsString()
  @MinLength(1)
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

/** Экран управления доступом — только для роли ГМ (users.manage есть только у OWNER). */
@Controller('users')
@RequirePermission('users.manage')
export class UsersController {
  constructor(private readonly users: UsersService) {}

  @Get()
  list(): Promise<UserAdminDto[]> {
    return this.users.list();
  }

  @Post()
  create(@Body() dto: CreateUserDto): Promise<UserAdminDto> {
    return this.users.create(dto);
  }

  @Patch(':id')
  update(
    @CurrentUser() actor: AuthUser,
    @Param('id') id: string,
    @Body() dto: UpdateUserDto,
  ): Promise<UserAdminDto> {
    return this.users.update(actor.id, id, dto);
  }

  @Put(':id/servers')
  setServers(@Param('id') id: string, @Body() dto: SetServersDto): Promise<UserAdminDto> {
    return this.users.setServerAccess(id, dto.serverIds);
  }
}
