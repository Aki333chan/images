import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { PteroSecretsService, SECRET_KEYS } from './ptero-secrets.service';
import { pteroRawGet, pteroRawRequest, pteroRequest } from './ptero-http';

/** Запись из листинга каталога. */
export interface PteroFile {
  name: string;
  mode: string;
  mode_bits?: string;
  size: number;
  is_file: boolean;
  is_symlink: boolean;
  mimetype: string;
  created_at: string;
  modified_at: string;
}

/**
 * Аллокация в Client API.
 *
 * ИМЕНА ПОЛЕЙ ЗДЕСЬ ДРУГИЕ, чем у одноимённой сущности в Application API:
 * там алиас зовётся `alias`, здесь — `ip_alias`, и там нет `is_default`.
 * Поэтому и тип отдельный, а не общий: один тип на две несовпадающие формы
 * означал бы, что половина полей всегда undefined и никто не помнит какая.
 */
export interface PteroClientAllocation {
  id: number;
  ip: string;
  ip_alias: string | null;
  port: number;
  notes: string | null;
  is_default: boolean;
}

/** Переменная egg, как её отдаёт EggVariableTransformer. */
export interface PteroEggVariable {
  name: string;
  description: string;
  env_variable: string;
  default_value: string;
  server_value: string;
  /** false — egg запрещает менять её из клиента. */
  is_editable: boolean;
  rules: string;
}

/** База данных сервера. Пароль приходит только при include=password. */
export interface PteroDatabase {
  id: string;
  name: string;
  username: string;
  host: { address: string; port: number };
  connections_from: string;
  max_connections: number | null;
  relationships?: { password?: { attributes?: { password?: string } } };
}

export interface PteroBackup {
  uuid: string;
  name: string;
  bytes: number;
  checksum: string | null;
  is_successful: boolean;
  is_locked: boolean;
  ignored_files: string[];
  created_at: string;
  completed_at: string | null;
}

export interface PteroTask {
  id: number;
  sequence_id: number;
  action: 'command' | 'power' | 'backup';
  payload: string;
  time_offset: number;
  is_queued: boolean;
  continue_on_failure: boolean;
}

export interface PteroSchedule {
  id: number;
  name: string;
  cron: {
    minute: string;
    hour: string;
    day_of_month: string;
    month: string;
    day_of_week: string;
  };
  is_active: boolean;
  is_processing: boolean;
  only_when_online: boolean;
  last_run_at: string | null;
  next_run_at: string | null;
  relationships?: { tasks?: { data?: { attributes: PteroTask }[] } };
}

/**
 * Тело создания и обновления расписания.
 *
 * Поля cron идут врозь и БЕЗ префикса cron_ — панель принимает именно
 * `minute`, `hour`, `day_of_month`, `month`, `day_of_week`, а в своей базе
 * складывает их уже в cron_*.
 */
export interface PteroScheduleInput {
  name: string;
  is_active: boolean;
  only_when_online: boolean;
  minute: string;
  hour: string;
  day_of_month: string;
  month: string;
  day_of_week: string;
}

export interface PteroTaskInput {
  action: 'command' | 'power' | 'backup';
  payload: string;
  time_offset: number;
  continue_on_failure: boolean;
  sequence_id?: number;
}

export interface PteroResources {
  current_state: string;
  resources: {
    memory_bytes: number;
    cpu_absolute: number;
    disk_bytes: number;
    network_rx_bytes: number;
    network_tx_bytes: number;
    uptime: number;
  };
}

/**
 * Client API служебного пользователя Pterodactyl — консоль (WebSocket-токен),
 * питание, команды, статистика. Служебный пользователь должен быть добавлен
 * subuser'ом (или владельцем) на нужные сервера в Pterodactyl.
 */
@Injectable()
export class ClientApiService {
  constructor(private readonly secrets: PteroSecretsService) {}

  private async key(): Promise<string> {
    const key = await this.secrets.get(SECRET_KEYS.CLIENT_KEY);
    if (!key) {
      throw new ServiceUnavailableException(
        'Client API key Pterodactyl не настроен (PTERO_CLIENT_API_KEY)',
      );
    }
    return key;
  }

  /** GET /api/client/servers/{identifier}/resources */
  async getResources(identifier: string): Promise<PteroResources> {
    const res = await pteroRequest<{ attributes: PteroResources }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/resources`,
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{identifier}/power, signal: start|stop|restart|kill */
  async sendPowerSignal(identifier: string, signal: 'start' | 'stop' | 'restart' | 'kill') {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/power`, {
      signal,
    });
  }

  /** POST /api/client/servers/{identifier}/command */
  async sendCommand(identifier: string, command: string) {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/command`, {
      command,
    });
  }

  // ---------------------------------------------------- Файлы сервера
  //
  // Маршруты сверены с routes/api-client.php панели Pterodactyl. Обратите
  // внимание на методы: rename — это PUT, а не POST, как остальные операции;
  // перепутанный метод даёт 405, а не понятную ошибку.

  /** GET /api/client/servers/{identifier}/files/list?directory=... */
  async listFiles(identifier: string, directory: string): Promise<PteroFile[]> {
    const res = await pteroRequest<{ data: { attributes: PteroFile }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/files/list?directory=${encodeURIComponent(directory)}`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{identifier}/files/write?file=...
   *
   * Тело — сырое содержимое файла, а не JSON. Для .jar это единственный
   * способ положить бинарник этим маршрутом.
   */
  async writeFile(identifier: string, path: string, content: Buffer): Promise<void> {
    await pteroRawRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/write?file=${encodeURIComponent(path)}`,
      content,
    );
  }

  /** PUT /api/client/servers/{identifier}/files/rename — им же и переносим. */
  async renameFile(identifier: string, root: string, from: string, to: string): Promise<void> {
    await pteroRequest(await this.key(), 'PUT', `/api/client/servers/${identifier}/files/rename`, {
      root,
      files: [{ from, to }],
    });
  }

  /** POST /api/client/servers/{identifier}/files/delete */
  async deleteFiles(identifier: string, root: string, files: string[]): Promise<void> {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/files/delete`, {
      root,
      files,
    });
  }

  /** POST /api/client/servers/{identifier}/files/create-folder */
  async createFolder(identifier: string, root: string, name: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/create-folder`,
      { root, name },
    );
  }

  /**
   * GET /api/client/servers/{identifier}
   *
   * Нужен ради docker_image: список разрешённых образов приходит в ответе
   * startup, а вот какой стоит СЕЙЧАС — только здесь.
   */
  async getServer(identifier: string): Promise<{ docker_image: string; name: string }> {
    const res = await pteroRequest<{ attributes: { docker_image: string; name: string } }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}`,
    );
    return res.attributes;
  }

  /** GET /api/client/servers/{identifier}/websocket — токен+URL для консоли Wings. */
  async getConsoleWebsocket(identifier: string): Promise<{ token: string; socket: string }> {
    const res = await pteroRequest<{ data: { token: string; socket: string } }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/websocket`,
    );
    return res.data;
  }

  // ------------------------------------------------- Файлы: остальное
  //
  // Маршруты и тела запросов сверены с routes/api-client.php и классами
  // Http/Requests/Api/Client/Servers/Files/*.php панели Pterodactyl. Имена
  // полей там объявлены явно, и это надёжнее любой документации.

  /**
   * GET /api/client/servers/{id}/files/contents?file=...
   *
   * Отвечает НЕ JSON, а самим содержимым с типом text/plain — поэтому
   * отдельный помощник, а не общий pteroRequest.
   */
  async readFile(
    identifier: string,
    path: string,
    maxBytes: number,
  ): Promise<{ content: Buffer; truncated: boolean }> {
    const res = await pteroRawGet(
      await this.key(),
      `/api/client/servers/${identifier}/files/contents?file=${encodeURIComponent(path)}`,
      maxBytes,
    );
    return { content: res.body, truncated: res.truncated };
  }

  /**
   * Содержимое файла для скачивания.
   *
   * Идём тем же маршрутом contents, а не download. Download отдал бы
   * подписанную ссылку прямо на Wings, и её пришлось бы передавать в
   * браузер: это и лишний адрес наружу, и обход прав панели — ссылка живёт
   * пятнадцать минут и работает у любого, кто её получил. Через панель
   * файл проходит под нашими правами и попадает в аудит.
   */
  async downloadFile(
    identifier: string,
    path: string,
    maxBytes: number,
  ): Promise<{ content: Buffer; truncated: boolean; contentType: string }> {
    const res = await pteroRawGet(
      await this.key(),
      `/api/client/servers/${identifier}/files/contents?file=${encodeURIComponent(path)}`,
      maxBytes,
    );
    return { content: res.body, truncated: res.truncated, contentType: res.contentType };
  }

  /** POST /api/client/servers/{id}/files/copy — создаёт копию рядом. */
  async copyFile(identifier: string, location: string): Promise<void> {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/files/copy`, {
      location,
    });
  }

  /**
   * POST /api/client/servers/{id}/files/compress
   *
   * Возвращает описание созданного архива: имя ему даёт Wings, и заранее
   * оно неизвестно.
   */
  async compressFiles(identifier: string, root: string, files: string[]): Promise<PteroFile> {
    const res = await pteroRequest<{ attributes: PteroFile }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/compress`,
      { root, files },
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{id}/files/decompress */
  async decompressFile(identifier: string, root: string, file: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/decompress`,
      { root, file },
    );
  }

  // ------------------------------------------------------------- Сеть

  /** GET /api/client/servers/{id}/network/allocations */
  async listAllocations(identifier: string): Promise<PteroClientAllocation[]> {
    const res = await pteroRequest<{ data: { attributes: PteroClientAllocation }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/network/allocations`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{id}/network/allocations — без тела.
   *
   * Порт выбирает сама панель из свободного пула ноды: указать конкретный
   * Client API не позволяет. Если свободных нет или упёрлись в лимит
   * сервера, ответ будет ошибкой — её и показываем.
   */
  async addAllocation(identifier: string): Promise<PteroClientAllocation> {
    const res = await pteroRequest<{ attributes: PteroClientAllocation }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/network/allocations`,
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{id}/network/allocations/{allocation} — заметка. */
  async setAllocationNotes(identifier: string, allocationId: number, notes: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/network/allocations/${allocationId}`,
      { notes },
    );
  }

  /** POST /api/client/servers/{id}/network/allocations/{allocation}/primary */
  async setPrimaryAllocation(identifier: string, allocationId: number): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/network/allocations/${allocationId}/primary`,
    );
  }

  /** DELETE /api/client/servers/{id}/network/allocations/{allocation} */
  async deleteAllocation(identifier: string, allocationId: number): Promise<void> {
    await pteroRequest(
      await this.key(),
      'DELETE',
      `/api/client/servers/${identifier}/network/allocations/${allocationId}`,
    );
  }

  // ----------------------------------------------------------- Запуск

  /**
   * GET /api/client/servers/{id}/startup
   *
   * Отдаёт только переменные с user_viewable, а в meta — собранную команду
   * запуска, сырую команду и список разрешённых egg докер-образов.
   */
  async getStartup(identifier: string): Promise<{
    variables: PteroEggVariable[];
    meta: { startup_command: string; raw_startup_command: string; docker_images: Record<string, string> };
  }> {
    const res = await pteroRequest<{
      data: { attributes: PteroEggVariable }[];
      meta: { startup_command: string; raw_startup_command: string; docker_images: Record<string, string> };
    }>(await this.key(), 'GET', `/api/client/servers/${identifier}/startup`);
    return { variables: res.data.map((d) => d.attributes), meta: res.meta };
  }

  /**
   * PUT /api/client/servers/{id}/startup/variable
   *
   * Метод именно PUT. Переменную, помеченную egg как нередактируемую,
   * панель отклонит сама — обходить это мы не пытаемся.
   */
  async setStartupVariable(identifier: string, key: string, value: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'PUT',
      `/api/client/servers/${identifier}/startup/variable`,
      { key, value },
    );
  }

  /** PUT /api/client/servers/{id}/settings/docker-image */
  async setDockerImage(identifier: string, dockerImage: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'PUT',
      `/api/client/servers/${identifier}/settings/docker-image`,
      { docker_image: dockerImage },
    );
  }

  // ------------------------------------------------------ Базы данных

  /**
   * GET /api/client/servers/{id}/databases
   *
   * `include=password` запрашивается отдельно и только когда пароль
   * действительно нужен: в обычном списке ему делать нечего.
   */
  async listDatabases(identifier: string, withPassword = false): Promise<PteroDatabase[]> {
    const res = await pteroRequest<{ data: { attributes: PteroDatabase }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/databases${withPassword ? '?include=password' : ''}`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{id}/databases
   *
   * `remote` — маска адресов, с которых разрешено подключаться. Ответ
   * содержит пароль: другого случая его увидеть не будет, пока не
   * перевыпустить.
   */
  async createDatabase(identifier: string, database: string, remote: string): Promise<PteroDatabase> {
    const res = await pteroRequest<{ attributes: PteroDatabase }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/databases`,
      { database, remote },
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{id}/databases/{database}/rotate-password */
  async rotateDatabasePassword(identifier: string, databaseId: string): Promise<PteroDatabase> {
    const res = await pteroRequest<{ attributes: PteroDatabase }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/databases/${databaseId}/rotate-password`,
    );
    return res.attributes;
  }

  /** DELETE /api/client/servers/{id}/databases/{database} */
  async deleteDatabase(identifier: string, databaseId: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'DELETE',
      `/api/client/servers/${identifier}/databases/${databaseId}`,
    );
  }

  // ----------------------------------------------------------- Бэкапы

  /** GET /api/client/servers/{id}/backups */
  async listBackups(identifier: string): Promise<PteroBackup[]> {
    const res = await pteroRequest<{ data: { attributes: PteroBackup }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/backups`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{id}/backups
   *
   * Возвращается сразу, не дожидаясь окончания: бэкап делается в фоне, и
   * у только что созданного completed_at ещё пустой.
   */
  async createBackup(identifier: string, name?: string, ignored?: string): Promise<PteroBackup> {
    const res = await pteroRequest<{ attributes: PteroBackup }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/backups`,
      { name: name || null, ignored: ignored || null },
    );
    return res.attributes;
  }

  /**
   * GET /api/client/servers/{id}/backups/{backup}/download
   *
   * Отдаёт подписанную ссылку на Wings, живущую пятнадцать минут. Бэкап
   * может весить гигабайты, поэтому его, в отличие от обычных файлов, через
   * панель не гоняем — ссылку открывает браузер напрямую.
   */
  async getBackupDownloadUrl(identifier: string, backupUuid: string): Promise<string> {
    const res = await pteroRequest<{ attributes: { url: string } }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/backups/${backupUuid}/download`,
    );
    return res.attributes.url;
  }

  /**
   * POST /api/client/servers/{id}/backups/{backup}/restore
   *
   * `truncate: true` — стереть текущие файлы перед распаковкой. Действие
   * разрушительное в обоих режимах: с truncate теряется всё, что появилось
   * после бэкапа, без него — файлы смешиваются.
   */
  async restoreBackup(identifier: string, backupUuid: string, truncate: boolean): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/backups/${backupUuid}/restore`,
      { truncate },
    );
  }

  /** POST /api/client/servers/{id}/backups/{backup}/lock — переключатель. */
  async toggleBackupLock(identifier: string, backupUuid: string): Promise<PteroBackup> {
    const res = await pteroRequest<{ attributes: PteroBackup }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/backups/${backupUuid}/lock`,
    );
    return res.attributes;
  }

  /** DELETE /api/client/servers/{id}/backups/{backup} */
  async deleteBackup(identifier: string, backupUuid: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'DELETE',
      `/api/client/servers/${identifier}/backups/${backupUuid}`,
    );
  }

  // ------------------------------------------------------- Расписания

  /** GET /api/client/servers/{id}/schedules?include=tasks */
  async listSchedules(identifier: string): Promise<PteroSchedule[]> {
    const res = await pteroRequest<{ data: { attributes: PteroSchedule }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/schedules?include=tasks`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{id}/schedules
   *
   * Поля cron идут врозь (minute, hour, day_of_month, month, day_of_week),
   * а не одной строкой: так их принимает панель.
   */
  async createSchedule(identifier: string, input: PteroScheduleInput): Promise<PteroSchedule> {
    const res = await pteroRequest<{ attributes: PteroSchedule }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/schedules`,
      input,
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{id}/schedules/{schedule} — обновление, не PUT. */
  async updateSchedule(
    identifier: string,
    scheduleId: number,
    input: PteroScheduleInput,
  ): Promise<PteroSchedule> {
    const res = await pteroRequest<{ attributes: PteroSchedule }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/schedules/${scheduleId}`,
      input,
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{id}/schedules/{schedule}/execute — запуск сейчас. */
  async executeSchedule(identifier: string, scheduleId: number): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/schedules/${scheduleId}/execute`,
    );
  }

  /** DELETE /api/client/servers/{id}/schedules/{schedule} */
  async deleteSchedule(identifier: string, scheduleId: number): Promise<void> {
    await pteroRequest(
      await this.key(),
      'DELETE',
      `/api/client/servers/${identifier}/schedules/${scheduleId}`,
    );
  }

  /** POST /api/client/servers/{id}/schedules/{schedule}/tasks */
  async createTask(
    identifier: string,
    scheduleId: number,
    input: PteroTaskInput,
  ): Promise<PteroTask> {
    const res = await pteroRequest<{ attributes: PteroTask }>(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/schedules/${scheduleId}/tasks`,
      input,
    );
    return res.attributes;
  }

  /** DELETE /api/client/servers/{id}/schedules/{schedule}/tasks/{task} */
  async deleteTask(identifier: string, scheduleId: number, taskId: number): Promise<void> {
    await pteroRequest(
      await this.key(),
      'DELETE',
      `/api/client/servers/${identifier}/schedules/${scheduleId}/tasks/${taskId}`,
    );
  }
}
