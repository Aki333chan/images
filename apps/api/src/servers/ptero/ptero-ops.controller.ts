import { Body, Controller, Delete, Get, Param, ParseIntPipe, Post, Put } from '@nestjs/common';
import {
  IsBoolean,
  IsIn,
  IsInt,
  IsObject,
  IsOptional,
  IsString,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import type { PteroBackupDto, PteroScheduleDto, PteroTaskDto } from '@aurum/shared';
import { SCHEDULE_ACTIONS, type ScheduleAction } from '@aurum/shared';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { PteroOpsService } from './ptero-ops.service';

class CreateBackupDto {
  @IsOptional() @IsString() @MaxLength(191) name?: string;

  /** Что не класть в бэкап: шаблоны через перевод строки, как у Pterodactyl. */
  @IsOptional() @IsString() @MaxLength(2048) ignored?: string;
}

class RestoreBackupDto {
  /**
   * true — стереть текущие файлы перед распаковкой.
   *
   * Обязательное поле без значения по умолчанию намеренно: это выбор между
   * двумя разрушительными исходами, и делать его молча за человека нельзя.
   */
  @IsBoolean()
  truncate!: boolean;
}

class CronDto {
  @IsString() @MaxLength(64) minute!: string;
  @IsString() @MaxLength(64) hour!: string;
  @IsString() @MaxLength(64) dayOfMonth!: string;
  @IsString() @MaxLength(64) month!: string;
  @IsString() @MaxLength(64) dayOfWeek!: string;
}

class ScheduleDto {
  @IsString() @MinLength(1) @MaxLength(191) name!: string;
  @IsBoolean() isActive!: boolean;
  @IsBoolean() onlyWhenOnline!: boolean;

  @IsObject()
  @ValidateNested()
  @Type(() => CronDto)
  cron!: CronDto;
}

class ActiveDto {
  @IsBoolean() isActive!: boolean;
}

class TaskDto {
  @IsIn(SCHEDULE_ACTIONS as unknown as string[])
  action!: ScheduleAction;

  /** Команда — для command, сигнал — для power, список исключений — для backup. */
  @IsString() @MaxLength(2048) payload!: string;

  @IsInt() @Min(0) @Max(900) timeOffset!: number;

  @IsBoolean() continueOnFailure!: boolean;
}

/**
 * Бэкапы и расписания — возможности самого Pterodactyl, одинаковые при
 * любом игровом модуле.
 *
 * ПРАВА. Список бэкапов открыт и Модератору: убедиться, что ночной бэкап
 * сделался, — обычная дежурная проверка, и она безопасна. Всё остальное —
 * Админ: восстановление затирает сервер, а расписание с ошибкой ночью его
 * кладёт.
 *
 * Мутирующие роуты пишутся в audit_log глобальным интерцептором.
 */
@Controller('servers/:serverId')
export class PteroOpsController {
  constructor(private readonly ops: PteroOpsService) {}

  // ----------------------------------------------------------- Бэкапы

  @Get('backups')
  @RequirePermission('backups.view')
  @ServerScoped('serverId')
  listBackups(@Param('serverId') serverId: string): Promise<PteroBackupDto[]> {
    return this.ops.listBackups(serverId);
  }

  @Post('backups')
  @RequirePermission('backups.manage')
  @ServerScoped('serverId')
  createBackup(
    @Param('serverId') serverId: string,
    @Body() dto: CreateBackupDto,
  ): Promise<PteroBackupDto> {
    return this.ops.createBackup(serverId, dto.name, dto.ignored);
  }

  /**
   * Ссылка на скачивание.
   *
   * POST, а не GET, намеренно: выдача ссылки — это событие, которое должно
   * попасть в аудит, а аудируются мутирующие методы. Бэкап может весить
   * гигабайты, и в отличие от обычных файлов через панель он не идёт.
   */
  @Post('backups/:backupId/download')
  @RequirePermission('backups.view')
  @ServerScoped('serverId')
  downloadBackup(@Param('serverId') serverId: string, @Param('backupId') backupId: string) {
    return this.ops.getBackupDownloadUrl(serverId, backupId);
  }

  @Post('backups/:backupId/restore')
  @RequirePermission('backups.manage')
  @ServerScoped('serverId')
  restoreBackup(
    @Param('serverId') serverId: string,
    @Param('backupId') backupId: string,
    @Body() dto: RestoreBackupDto,
  ) {
    return this.ops.restoreBackup(serverId, backupId, dto.truncate);
  }

  @Post('backups/:backupId/lock')
  @RequirePermission('backups.manage')
  @ServerScoped('serverId')
  toggleLock(@Param('serverId') serverId: string, @Param('backupId') backupId: string) {
    return this.ops.toggleBackupLock(serverId, backupId);
  }

  @Delete('backups/:backupId')
  @RequirePermission('backups.manage')
  @ServerScoped('serverId')
  deleteBackup(@Param('serverId') serverId: string, @Param('backupId') backupId: string) {
    return this.ops.deleteBackup(serverId, backupId);
  }

  // ------------------------------------------------------- Расписания

  @Get('schedules')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  listSchedules(@Param('serverId') serverId: string): Promise<PteroScheduleDto[]> {
    return this.ops.listSchedules(serverId);
  }

  @Post('schedules')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  createSchedule(
    @Param('serverId') serverId: string,
    @Body() dto: ScheduleDto,
  ): Promise<PteroScheduleDto> {
    return this.ops.createSchedule(serverId, dto);
  }

  @Put('schedules/:scheduleId')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  updateSchedule(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
    @Body() dto: ScheduleDto,
  ): Promise<PteroScheduleDto> {
    return this.ops.updateSchedule(serverId, scheduleId, dto);
  }

  /** Переключатель отдельным роутом: он не должен трогать cron и шаги. */
  @Put('schedules/:scheduleId/active')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  setActive(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
    @Body() dto: ActiveDto,
  ): Promise<PteroScheduleDto> {
    return this.ops.setScheduleActive(serverId, scheduleId, dto.isActive);
  }

  /** Запуск немедленно, не дожидаясь расписания. */
  @Post('schedules/:scheduleId/execute')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  execute(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
  ) {
    return this.ops.executeSchedule(serverId, scheduleId);
  }

  @Delete('schedules/:scheduleId')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  deleteSchedule(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
  ) {
    return this.ops.deleteSchedule(serverId, scheduleId);
  }

  @Post('schedules/:scheduleId/tasks')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  addTask(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
    @Body() dto: TaskDto,
  ): Promise<PteroTaskDto> {
    return this.ops.addTask(serverId, scheduleId, dto);
  }

  @Delete('schedules/:scheduleId/tasks/:taskId')
  @RequirePermission('schedules.manage')
  @ServerScoped('serverId')
  deleteTask(
    @Param('serverId') serverId: string,
    @Param('scheduleId', ParseIntPipe) scheduleId: number,
    @Param('taskId', ParseIntPipe) taskId: number,
  ) {
    return this.ops.deleteTask(serverId, scheduleId, taskId);
  }
}
