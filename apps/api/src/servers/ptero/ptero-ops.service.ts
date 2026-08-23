import { BadRequestException, Injectable } from '@nestjs/common';
import type { PteroBackupDto, PteroScheduleDto, PteroTaskDto, ScheduleAction } from '@aurum/shared';
import { SCHEDULE_POWER_ACTIONS } from '@aurum/shared';
import {
  ClientApiService,
  type PteroBackup,
  type PteroSchedule,
  type PteroTask,
} from '../../pterodactyl/client-api.service';
import { ServersService } from '../servers.service';
import { validateCron, type CronParts } from './cron-fields';

export interface ScheduleInput {
  name: string;
  isActive: boolean;
  onlyWhenOnline: boolean;
  cron: CronParts;
}

export interface TaskInput {
  action: ScheduleAction;
  payload: string;
  timeOffset: number;
  continueOnFailure: boolean;
}

/**
 * Бэкапы и расписания сервера.
 *
 * Обе темы — возможности самого Pterodactyl, одинаковые при любом игровом
 * модуле. Логики здесь немного, и почти вся она про то, чтобы не дать
 * отправить в панель заведомо неверное: расписание с опечаткой сработает не
 * тогда, а восстановление бэкапа необратимо.
 */
@Injectable()
export class PteroOpsService {
  /** Дольше сервер ждать перед шагом всё равно не даст сам Pterodactyl. */
  static readonly MAX_TIME_OFFSET = 900;

  constructor(
    private readonly client: ClientApiService,
    private readonly servers: ServersService,
  ) {}

  private async identifier(serverId: string): Promise<string> {
    const server = await this.servers.getById(serverId);
    return server.pteroIdentifier;
  }

  // ----------------------------------------------------------- Бэкапы

  async listBackups(serverId: string): Promise<PteroBackupDto[]> {
    const list = await this.client.listBackups(await this.identifier(serverId));
    // Свежие сверху: ищут почти всегда последний.
    return list
      .map(toBackupDto)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  /**
   * Создаёт бэкап.
   *
   * Возвращается сразу: бэкап делается в фоне, и у только что созданного
   * completedAt пустой. Интерфейс на это и рассчитывает — показывает «в
   * работе», а не ждёт.
   */
  async createBackup(serverId: string, name?: string, ignored?: string): Promise<PteroBackupDto> {
    const created = await this.client.createBackup(
      await this.identifier(serverId),
      name?.trim() || undefined,
      ignored?.trim() || undefined,
    );
    return toBackupDto(created);
  }

  /**
   * Ссылка на скачивание бэкапа.
   *
   * ЗДЕСЬ ССЫЛКА УМЕСТНА, в отличие от обычных файлов. Бэкап весит гигабайты,
   * и гнать его через память панели нельзя; ссылка подписана и живёт
   * пятнадцать минут. Сам факт выдачи ссылки попадает в аудит — это и есть
   * событие «человек забрал бэкап».
   */
  async getBackupDownloadUrl(serverId: string, backupUuid: string): Promise<{ url: string }> {
    const url = await this.client.getBackupDownloadUrl(await this.identifier(serverId), backupUuid);
    return { url };
  }

  async restoreBackup(
    serverId: string,
    backupUuid: string,
    truncate: boolean,
  ): Promise<{ ok: true }> {
    const identifier = await this.identifier(serverId);

    // Восстановление неудавшегося бэкапа — это гарантированная порча
    // сервера: файлов в нём нет, а текущие при truncate уже стёрты.
    const backups = await this.client.listBackups(identifier);
    const target = backups.find((b) => b.uuid === backupUuid);
    if (!target) throw new BadRequestException('Бэкап не найден');
    if (!target.is_successful) {
      throw new BadRequestException('Этот бэкап не завершился успешно — восстанавливать нечего');
    }

    await this.client.restoreBackup(identifier, backupUuid, truncate);
    return { ok: true };
  }

  async toggleBackupLock(serverId: string, backupUuid: string): Promise<PteroBackupDto> {
    return toBackupDto(
      await this.client.toggleBackupLock(await this.identifier(serverId), backupUuid),
    );
  }

  async deleteBackup(serverId: string, backupUuid: string): Promise<{ ok: true }> {
    await this.client.deleteBackup(await this.identifier(serverId), backupUuid);
    return { ok: true };
  }

  // ------------------------------------------------------- Расписания

  async listSchedules(serverId: string): Promise<PteroScheduleDto[]> {
    const list = await this.client.listSchedules(await this.identifier(serverId));
    return list.map(toScheduleDto);
  }

  async createSchedule(serverId: string, input: ScheduleInput): Promise<PteroScheduleDto> {
    const created = await this.client.createSchedule(
      await this.identifier(serverId),
      toScheduleBody(input),
    );
    return toScheduleDto(created);
  }

  async updateSchedule(
    serverId: string,
    scheduleId: number,
    input: ScheduleInput,
  ): Promise<PteroScheduleDto> {
    const updated = await this.client.updateSchedule(
      await this.identifier(serverId),
      scheduleId,
      toScheduleBody(input),
    );
    return toScheduleDto(updated);
  }

  /**
   * Включает или выключает расписание, не трогая остального.
   *
   * Обновление у Pterodactyl полное — оно ждёт все поля разом, — поэтому
   * сначала читаем текущее состояние. Иначе переключатель «выключить»
   * заодно сбрасывал бы cron в то, что случайно оказалось в форме.
   */
  async setScheduleActive(
    serverId: string,
    scheduleId: number,
    isActive: boolean,
  ): Promise<PteroScheduleDto> {
    const identifier = await this.identifier(serverId);
    const current = (await this.client.listSchedules(identifier)).find((s) => s.id === scheduleId);
    if (!current) throw new BadRequestException('Расписание не найдено');

    const updated = await this.client.updateSchedule(identifier, scheduleId, {
      name: current.name,
      is_active: isActive,
      only_when_online: current.only_when_online,
      minute: current.cron.minute,
      hour: current.cron.hour,
      day_of_month: current.cron.day_of_month,
      month: current.cron.month,
      day_of_week: current.cron.day_of_week,
    });
    return toScheduleDto(updated);
  }

  async executeSchedule(serverId: string, scheduleId: number): Promise<{ ok: true }> {
    await this.client.executeSchedule(await this.identifier(serverId), scheduleId);
    return { ok: true };
  }

  async deleteSchedule(serverId: string, scheduleId: number): Promise<{ ok: true }> {
    await this.client.deleteSchedule(await this.identifier(serverId), scheduleId);
    return { ok: true };
  }

  async addTask(serverId: string, scheduleId: number, input: TaskInput): Promise<PteroTaskDto> {
    const created = await this.client.createTask(await this.identifier(serverId), scheduleId, {
      action: input.action,
      payload: validatePayload(input.action, input.payload),
      time_offset: validateOffset(input.timeOffset),
      continue_on_failure: input.continueOnFailure,
    });
    return toTaskDto(created);
  }

  async deleteTask(serverId: string, scheduleId: number, taskId: number): Promise<{ ok: true }> {
    await this.client.deleteTask(await this.identifier(serverId), scheduleId, taskId);
    return { ok: true };
  }
}

/** Тело расписания для Pterodactyl. Поля cron идут врозь и без префикса cron_. */
function toScheduleBody(input: ScheduleInput) {
  const cron = validateCron(input.cron);
  const name = input.name.trim();
  if (name === '') throw new BadRequestException('У расписания должно быть название');
  return {
    name: name.slice(0, 191),
    is_active: input.isActive,
    only_when_online: input.onlyWhenOnline,
    minute: cron.minute,
    hour: cron.hour,
    day_of_month: cron.dayOfMonth,
    month: cron.month,
    day_of_week: cron.dayOfWeek,
  };
}

/**
 * Проверяет payload шага.
 *
 * У каждого действия он значит своё: для power это сигнал из закрытого
 * списка, для command — строка команды, а backup обходится без него.
 * Ошибиться здесь легко, а заметить — только когда расписание однажды
 * ночью не сработает.
 */
function validatePayload(action: ScheduleAction, raw: string): string {
  const payload = (raw ?? '').trim();

  if (action === 'backup') {
    // У backup payload — это список исключений, и он необязателен.
    return payload.slice(0, 2048);
  }
  if (action === 'power') {
    if (!(SCHEDULE_POWER_ACTIONS as readonly string[]).includes(payload)) {
      throw new BadRequestException(
        `Для шага питания нужен один из сигналов: ${SCHEDULE_POWER_ACTIONS.join(', ')}`,
      );
    }
    return payload;
  }
  if (payload === '') throw new BadRequestException('Не указана команда');
  // Перевод строки внутри команды — это вторая команда для игрового сервера.
  if (/[\r\n]/.test(payload)) {
    throw new BadRequestException('Команда не может содержать перевод строки');
  }
  return payload.slice(0, 2048);
}

function validateOffset(raw: number): number {
  if (!Number.isInteger(raw) || raw < 0 || raw > PteroOpsService.MAX_TIME_OFFSET) {
    throw new BadRequestException(
      `Задержка перед шагом — целое число секунд от 0 до ${PteroOpsService.MAX_TIME_OFFSET}`,
    );
  }
  return raw;
}

function toBackupDto(b: PteroBackup): PteroBackupDto {
  return {
    uuid: b.uuid,
    name: b.name,
    bytes: b.bytes,
    checksum: b.checksum,
    isSuccessful: b.is_successful,
    isLocked: b.is_locked,
    createdAt: b.created_at,
    completedAt: b.completed_at,
  };
}

function toTaskDto(t: PteroTask): PteroTaskDto {
  return {
    id: t.id,
    sequenceId: t.sequence_id,
    action: t.action,
    payload: t.payload,
    timeOffset: t.time_offset,
    continueOnFailure: t.continue_on_failure,
  };
}

function toScheduleDto(s: PteroSchedule): PteroScheduleDto {
  return {
    id: s.id,
    name: s.name,
    cron: {
      minute: s.cron.minute,
      hour: s.cron.hour,
      dayOfMonth: s.cron.day_of_month,
      month: s.cron.month,
      dayOfWeek: s.cron.day_of_week,
    },
    isActive: s.is_active,
    isProcessing: s.is_processing,
    onlyWhenOnline: s.only_when_online,
    lastRunAt: s.last_run_at,
    nextRunAt: s.next_run_at,
    // Шаги приходят вложением include=tasks; сортируем по порядку
    // выполнения — в ответе он не гарантирован, а показывать шаги вперемешку
    // значит соврать о том, что за чем идёт.
    tasks: (s.relationships?.tasks?.data ?? [])
      .map((d) => toTaskDto(d.attributes))
      .sort((a, b) => a.sequenceId - b.sequenceId),
  };
}
