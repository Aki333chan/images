import { Body, Controller, Delete, Get, Param, ParseIntPipe, Post, Put } from '@nestjs/common';
import { IsOptional, IsString, Matches, MaxLength, MinLength } from 'class-validator';
import type { PteroAllocationDto, PteroDatabaseDto, PteroStartupDto } from '@aurum/shared';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { PteroSettingsService } from './ptero-settings.service';

class NotesDto {
  /** Пустая строка допустима: так заметку стирают. */
  @IsString()
  @MaxLength(191)
  notes!: string;
}

class VariableDto {
  @IsString() @MinLength(1) @MaxLength(191) key!: string;

  /** Пустое значение допустимо: часть переменных так и сбрасывают. */
  @IsString() @MaxLength(2048) value!: string;
}

class DockerImageDto {
  @IsString() @MinLength(1) @MaxLength(255) image!: string;
}

class CreateDatabaseDto {
  /**
   * Имя базы. Ограничение alpha_dash — правило самого Pterodactyl; проверяем
   * его здесь, чтобы отказ был по-русски и до похода в панель.
   */
  @IsString()
  @MinLength(3)
  @MaxLength(48)
  @Matches(/^[A-Za-z0-9_-]+$/, {
    message: 'Имя базы: латинские буквы, цифры, дефис и подчёркивание',
  })
  name!: string;

  /**
   * С каких адресов разрешено подключаться. По умолчанию «%» — отовсюду,
   * как и в самом Pterodactyl.
   */
  @IsOptional()
  @IsString()
  @MaxLength(191)
  remote?: string;
}

/**
 * Сеть, запуск и базы данных сервера — возможности самого Pterodactyl,
 * одинаковые при любом игровом модуле.
 *
 * Все три темы закрыты правом Админа: ошибка в переменной запуска не даёт
 * серверу подняться, лишний открытый порт — это дыра, а креденшлы базы —
 * это доступ к данным.
 *
 * Мутирующие роуты пишутся в audit_log глобальным интерцептором.
 */
@Controller('servers/:serverId')
export class PteroSettingsController {
  constructor(private readonly settings: PteroSettingsService) {}

  // ------------------------------------------------------------- Сеть

  @Get('allocations')
  @RequirePermission('allocations.manage')
  @ServerScoped('serverId')
  listAllocations(@Param('serverId') serverId: string): Promise<PteroAllocationDto[]> {
    return this.settings.listAllocations(serverId);
  }

  /** Порт выбирает сама панель из свободного пула ноды — указать его нельзя. */
  @Post('allocations')
  @RequirePermission('allocations.manage')
  @ServerScoped('serverId')
  addAllocation(@Param('serverId') serverId: string): Promise<PteroAllocationDto> {
    return this.settings.addAllocation(serverId);
  }

  @Put('allocations/:allocationId/notes')
  @RequirePermission('allocations.manage')
  @ServerScoped('serverId')
  setNotes(
    @Param('serverId') serverId: string,
    @Param('allocationId', ParseIntPipe) allocationId: number,
    @Body() dto: NotesDto,
  ) {
    return this.settings.setAllocationNotes(serverId, allocationId, dto.notes);
  }

  @Post('allocations/:allocationId/primary')
  @RequirePermission('allocations.manage')
  @ServerScoped('serverId')
  setPrimary(
    @Param('serverId') serverId: string,
    @Param('allocationId', ParseIntPipe) allocationId: number,
  ) {
    return this.settings.setPrimaryAllocation(serverId, allocationId);
  }

  @Delete('allocations/:allocationId')
  @RequirePermission('allocations.manage')
  @ServerScoped('serverId')
  deleteAllocation(
    @Param('serverId') serverId: string,
    @Param('allocationId', ParseIntPipe) allocationId: number,
  ) {
    return this.settings.deleteAllocation(serverId, allocationId);
  }

  // ----------------------------------------------------------- Запуск

  @Get('startup')
  @RequirePermission('startup.manage')
  @ServerScoped('serverId')
  getStartup(@Param('serverId') serverId: string): Promise<PteroStartupDto> {
    return this.settings.getStartup(serverId);
  }

  @Put('startup/variable')
  @RequirePermission('startup.manage')
  @ServerScoped('serverId')
  setVariable(@Param('serverId') serverId: string, @Body() dto: VariableDto) {
    return this.settings.setVariable(serverId, dto.key, dto.value);
  }

  @Put('startup/docker-image')
  @RequirePermission('startup.manage')
  @ServerScoped('serverId')
  setDockerImage(@Param('serverId') serverId: string, @Body() dto: DockerImageDto) {
    return this.settings.setDockerImage(serverId, dto.image);
  }

  // ------------------------------------------------------ Базы данных

  @Get('databases')
  @RequirePermission('databases.manage')
  @ServerScoped('serverId')
  listDatabases(@Param('serverId') serverId: string): Promise<PteroDatabaseDto[]> {
    return this.settings.listDatabases(serverId);
  }

  /**
   * Креденшлы одной базы.
   *
   * Отдельным роутом, а не полем списка: пароль не должен приезжать в
   * браузер при каждом открытии вкладки, а «кто-то посмотрел пароль» должно
   * быть отличимо в аудите от «кто-то открыл список».
   */
  @Get('databases/:databaseId/credentials')
  @RequirePermission('databases.manage')
  @ServerScoped('serverId')
  credentials(
    @Param('serverId') serverId: string,
    @Param('databaseId') databaseId: string,
  ): Promise<PteroDatabaseDto> {
    return this.settings.getDatabaseCredentials(serverId, databaseId);
  }

  @Post('databases')
  @RequirePermission('databases.manage')
  @ServerScoped('serverId')
  createDatabase(@Param('serverId') serverId: string, @Body() dto: CreateDatabaseDto) {
    return this.settings.createDatabase(serverId, dto.name, dto.remote || '%');
  }

  @Post('databases/:databaseId/rotate-password')
  @RequirePermission('databases.manage')
  @ServerScoped('serverId')
  rotatePassword(
    @Param('serverId') serverId: string,
    @Param('databaseId') databaseId: string,
  ) {
    return this.settings.rotateDatabasePassword(serverId, databaseId);
  }

  @Delete('databases/:databaseId')
  @RequirePermission('databases.manage')
  @ServerScoped('serverId')
  deleteDatabase(@Param('serverId') serverId: string, @Param('databaseId') databaseId: string) {
    return this.settings.deleteDatabase(serverId, databaseId);
  }
}
