import { BadRequestException, Injectable } from '@nestjs/common';
import type {
  PteroAllocationDto,
  PteroDatabaseDto,
  PteroStartupDto,
  PteroVariableDto,
} from '@aurum/shared';
import {
  ClientApiService,
  type PteroClientAllocation,
  type PteroDatabase,
  type PteroEggVariable,
} from '../../pterodactyl/client-api.service';
import { ServersService } from '../servers.service';

/**
 * Сеть, запуск и базы данных сервера.
 *
 * Три темы в одном сервисе потому, что все они — тонкий перевод ответов
 * Pterodactyl в наши DTO, и на каждую пришлось бы по два десятка строк.
 * Логики, которую стоило бы разделять, здесь нет: решения принимает сам
 * Pterodactyl, а панель показывает и передаёт.
 */
@Injectable()
export class PteroSettingsService {
  constructor(
    private readonly client: ClientApiService,
    private readonly servers: ServersService,
  ) {}

  private async identifier(serverId: string): Promise<string> {
    const server = await this.servers.getById(serverId);
    return server.pteroIdentifier;
  }

  // ------------------------------------------------------------- Сеть

  async listAllocations(serverId: string): Promise<PteroAllocationDto[]> {
    const list = await this.client.listAllocations(await this.identifier(serverId));
    // Основная сверху: с неё начинают смотреть, и она же в адресе сервера.
    return list.map(toAllocationDto).sort((a, b) => Number(b.isDefault) - Number(a.isDefault));
  }

  /**
   * Добавляет аллокацию из свободного пула ноды.
   *
   * Конкретный порт выбрать нельзя — Client API этого не позволяет, порт
   * назначает сама панель. Если свободных на ноде нет или сервер упёрся в
   * свой лимит аллокаций, Pterodactyl ответит ошибкой, и мы её покажем как
   * есть: придумывать своё объяснение чужому отказу — значит однажды
   * соврать.
   */
  async addAllocation(serverId: string): Promise<PteroAllocationDto> {
    return toAllocationDto(await this.client.addAllocation(await this.identifier(serverId)));
  }

  async setAllocationNotes(
    serverId: string,
    allocationId: number,
    notes: string,
  ): Promise<{ ok: true }> {
    await this.client.setAllocationNotes(await this.identifier(serverId), allocationId, notes);
    return { ok: true };
  }

  async setPrimaryAllocation(serverId: string, allocationId: number): Promise<{ ok: true }> {
    await this.client.setPrimaryAllocation(await this.identifier(serverId), allocationId);
    return { ok: true };
  }

  /**
   * Удаляет второстепенную аллокацию.
   *
   * Основную удалить нельзя — это правило самого Pterodactyl. Проверяем и
   * у себя, чтобы отказ был понятным по-русски и до похода в панель, а не
   * пришёл сырым текстом чужой ошибки.
   */
  async deleteAllocation(serverId: string, allocationId: number): Promise<{ ok: true }> {
    const identifier = await this.identifier(serverId);
    const current = await this.client.listAllocations(identifier);
    const target = current.find((a) => a.id === allocationId);
    if (!target) throw new BadRequestException('Такой аллокации у сервера нет');
    if (target.is_default) {
      throw new BadRequestException(
        'Это основная аллокация — сначала назначьте основной другую, потом удаляйте эту',
      );
    }
    await this.client.deleteAllocation(identifier, allocationId);
    return { ok: true };
  }

  // ----------------------------------------------------------- Запуск

  async getStartup(serverId: string): Promise<PteroStartupDto> {
    const identifier = await this.identifier(serverId);
    // Два запроса, потому что данные лежат врозь: startup отдаёт переменные
    // и список РАЗРЕШЁННЫХ образов, а какой стоит СЕЙЧАС — только карточка
    // сервера. Без второго запроса пришлось бы показывать «текущий образ
    // неизвестен» на экране, где его и меняют.
    const [startup, server] = await Promise.all([
      this.client.getStartup(identifier),
      this.client.getServer(identifier),
    ]);

    return {
      startupCommand: startup.meta.startup_command,
      rawStartupCommand: startup.meta.raw_startup_command,
      dockerImages: startup.meta.docker_images ?? {},
      currentDockerImage: server.docker_image || null,
      variables: startup.variables.map(toVariableDto),
    };
  }

  /**
   * Меняет одну переменную egg.
   *
   * Переменную, которую egg пометил нередактируемой, панель отклонит сама —
   * мы это не обходим. Своей проверки «а можно ли» здесь нет намеренно:
   * дублировать чужое правило значит однажды с ним разойтись.
   */
  async setVariable(serverId: string, key: string, value: string): Promise<PteroStartupDto> {
    await this.client.setStartupVariable(await this.identifier(serverId), key, value);
    return this.getStartup(serverId);
  }

  /**
   * Меняет докер-образ.
   *
   * Список допустимых задан egg — берём его же и проверяем принадлежность
   * до отправки: опечатка в имени образа не даст серверу подняться, и
   * поймать её лучше здесь.
   */
  async setDockerImage(serverId: string, image: string): Promise<PteroStartupDto> {
    const identifier = await this.identifier(serverId);
    const startup = await this.client.getStartup(identifier);
    const allowed = Object.values(startup.meta.docker_images ?? {});
    if (allowed.length > 0 && !allowed.includes(image)) {
      throw new BadRequestException('Этот образ не разрешён egg этого сервера');
    }
    await this.client.setDockerImage(identifier, image);
    return this.getStartup(serverId);
  }

  // ------------------------------------------------------ Базы данных

  /** Список без паролей: в обычном списке им делать нечего. */
  async listDatabases(serverId: string): Promise<PteroDatabaseDto[]> {
    const list = await this.client.listDatabases(await this.identifier(serverId), false);
    return list.map((db) => toDatabaseDto(db, false));
  }

  /**
   * Креденшлы одной базы — по явному запросу.
   *
   * Отдельным роутом, а не полем списка: пароль не должен приезжать в
   * браузер при каждом открытии вкладки, а факт «кто-то посмотрел пароль»
   * должен быть отличим в аудите от «кто-то открыл список».
   */
  async getDatabaseCredentials(serverId: string, databaseId: string): Promise<PteroDatabaseDto> {
    const list = await this.client.listDatabases(await this.identifier(serverId), true);
    const found = list.find((db) => db.id === databaseId);
    if (!found) throw new BadRequestException('База не найдена');
    return toDatabaseDto(found, true);
  }

  async createDatabase(
    serverId: string,
    name: string,
    remote: string,
  ): Promise<PteroDatabaseDto> {
    const created = await this.client.createDatabase(await this.identifier(serverId), name, remote);
    // В ответе на создание пароль есть — и это единственный раз, когда его
    // видно без отдельного запроса.
    return toDatabaseDto(created, true);
  }

  async rotateDatabasePassword(serverId: string, databaseId: string): Promise<PteroDatabaseDto> {
    const updated = await this.client.rotateDatabasePassword(
      await this.identifier(serverId),
      databaseId,
    );
    return toDatabaseDto(updated, true);
  }

  async deleteDatabase(serverId: string, databaseId: string): Promise<{ ok: true }> {
    await this.client.deleteDatabase(await this.identifier(serverId), databaseId);
    return { ok: true };
  }
}

function toAllocationDto(a: PteroClientAllocation): PteroAllocationDto {
  return {
    id: a.id,
    ip: a.ip,
    ipAlias: a.ip_alias,
    port: a.port,
    notes: a.notes,
    isDefault: a.is_default,
  };
}

function toVariableDto(v: PteroEggVariable): PteroVariableDto {
  return {
    name: v.name,
    description: v.description,
    envVariable: v.env_variable,
    defaultValue: v.default_value,
    serverValue: v.server_value,
    isEditable: v.is_editable,
    rules: v.rules,
  };
}

function toDatabaseDto(db: PteroDatabase, withPassword: boolean): PteroDatabaseDto {
  return {
    id: db.id,
    name: db.name,
    username: db.username,
    host: db.host,
    connectionsFrom: db.connections_from,
    maxConnections: db.max_connections,
    ...(withPassword ? { password: db.relationships?.password?.attributes?.password } : {}),
  };
}
