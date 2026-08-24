import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import {
  DEFAULT_SERVER_LIST_PREFS,
  SERVER_SORTS,
  type ServerDto,
  type ServerListPrefsDto,
  type ServerSort,
} from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import {
  ApplicationApiService,
  defaultAllocation,
} from '../pterodactyl/application-api.service';
import { EffectivePermissions } from '../rbac/permissions.service';

@Injectable()
export class ServersService {
  private readonly logger = new Logger(ServersService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly appApi: ApplicationApiService,
  ) {}

  private toDto(s: {
    id: string;
    pteroIdentifier: string;
    name: string;
    description: string | null;
    node: string | null;
    allocationIp: string | null;
    allocationAlias: string | null;
    allocationPort: number | null;
    status: string | null;
    moduleId: string | null;
  }): ServerDto {
    // Показываем alias, когда он есть: если владелец завёл в Pterodactyl
    // доменное имя, значит игрокам он даёт именно его, а не голый IP.
    const host = s.allocationAlias?.trim() || s.allocationIp;
    return {
      id: s.id,
      pteroIdentifier: s.pteroIdentifier,
      name: s.name,
      description: s.description,
      node: s.node,
      address: host && s.allocationPort ? `${host}:${s.allocationPort}` : null,
      ip: s.allocationIp,
      port: s.allocationPort,
      status: s.status,
      moduleId: s.moduleId,
    };
  }

  /** Список серверов, отфильтрованный по server-scope пользователя. */
  async listForUser(eff: EffectivePermissions): Promise<ServerDto[]> {
    const servers = await this.prisma.server.findMany({
      where: eff.allowedServerIds === null ? {} : { id: { in: [...eff.allowedServerIds] } },
      orderBy: { name: 'asc' },
    });
    return servers.map((s) => this.toDto(s));
  }

  async getById(id: string): Promise<ServerDto> {
    const server = await this.prisma.server.findUnique({ where: { id } });
    if (!server) throw new NotFoundException('Сервер не найден');
    return this.toDto(server);
  }

  async setModule(id: string, moduleId: string | null): Promise<ServerDto> {
    const server = await this.prisma.server.findUnique({ where: { id } });
    if (!server) throw new NotFoundException('Сервер не найден');
    const updated = await this.prisma.server.update({ where: { id }, data: { moduleId } });
    return this.toDto(updated);
  }

  /**
   * Зеркалирование серверов из Pterodactyl Application API.
   * Пропавшие в Pterodactyl сервера НЕ удаляются (сохраняем тикеты/историю),
   * а помечаются статусом 'missing'.
   */
  async syncFromPterodactyl(): Promise<{ synced: number }> {
    const remote = await this.appApi.listAllServers();
    const seenIds: number[] = [];
    for (const srv of remote) {
      seenIds.push(srv.id);
      const allocation = defaultAllocation(srv);
      // Аллокация приезжает не всегда (ключу может не хватать прав на неё),
      // а затирать известный адрес пустотой хуже, чем показать вчерашний:
      // адрес сервера меняется несравнимо реже, чем случаются такие ответы.
      const address = allocation
        ? {
            allocationIp: allocation.ip,
            allocationAlias: allocation.alias || null,
            allocationPort: allocation.port,
          }
        : {};
      await this.prisma.server.upsert({
        where: { pteroId: srv.id },
        create: {
          pteroId: srv.id,
          pteroIdentifier: srv.identifier,
          pteroUuid: srv.uuid,
          name: srv.name,
          description: srv.description || null,
          node: String(srv.node),
          status: srv.suspended ? 'suspended' : 'active',
          memoryLimitMb: srv.limits?.memory ?? null,
          diskLimitMb: srv.limits?.disk ?? null,
          cpuLimitPercent: srv.limits?.cpu ?? null,
          ...address,
        },
        update: {
          pteroIdentifier: srv.identifier,
          pteroUuid: srv.uuid,
          name: srv.name,
          description: srv.description || null,
          node: String(srv.node),
          status: srv.suspended ? 'suspended' : 'active',
          memoryLimitMb: srv.limits?.memory ?? null,
          diskLimitMb: srv.limits?.disk ?? null,
          cpuLimitPercent: srv.limits?.cpu ?? null,
          ...address,
        },
      });
    }
    await this.prisma.server.updateMany({
      where: { pteroId: { notIn: seenIds } },
      data: { status: 'missing' },
    });
    this.logger.log(`Синхронизировано серверов: ${remote.length}`);
    return { synced: remote.length };
  }

  // ------------------------------------------- Личные настройки списка

  /**
   * Настройки списка серверов конкретного человека.
   *
   * Лежат в app_settings под ключом с его id. Отдельной таблицы нет
   * намеренно: это одна строка JSON на пользователя, которую читает и пишет
   * только он сам, — заводить под неё таблицу с миграцией значило бы
   * усложнить на ровном месте.
   *
   * Битое или устаревшее значение молча заменяется дефолтом: сломанный
   * порядок карточек не должен ломать сам список.
   */
  async getListPrefs(userId: string): Promise<ServerListPrefsDto> {
    const row = await this.prisma.appSetting.findUnique({
      where: { key: listPrefsKey(userId) },
    });
    if (!row) return { ...DEFAULT_SERVER_LIST_PREFS };
    try {
      const parsed = JSON.parse(row.value) as Partial<ServerListPrefsDto>;
      return {
        sort: (SERVER_SORTS as readonly string[]).includes(parsed.sort ?? '')
          ? (parsed.sort as ServerSort)
          : DEFAULT_SERVER_LIST_PREFS.sort,
        order: Array.isArray(parsed.order) ? parsed.order.filter((id) => typeof id === 'string') : [],
      };
    } catch {
      return { ...DEFAULT_SERVER_LIST_PREFS };
    }
  }

  async setListPrefs(userId: string, prefs: ServerListPrefsDto): Promise<ServerListPrefsDto> {
    const value = JSON.stringify(prefs);
    await this.prisma.appSetting.upsert({
      where: { key: listPrefsKey(userId) },
      create: { key: listPrefsKey(userId), value },
      update: { value },
    });
    return prefs;
  }
}

function listPrefsKey(userId: string): string {
  return `ui.serverList.${userId}`;
}
