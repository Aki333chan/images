import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import { ServerDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { ApplicationApiService } from '../pterodactyl/application-api.service';
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
    status: string | null;
    moduleId: string | null;
  }): ServerDto {
    return {
      id: s.id,
      pteroIdentifier: s.pteroIdentifier,
      name: s.name,
      description: s.description,
      node: s.node,
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
        },
        update: {
          pteroIdentifier: srv.identifier,
          pteroUuid: srv.uuid,
          name: srv.name,
          description: srv.description || null,
          node: String(srv.node),
          status: srv.suspended ? 'suspended' : 'active',
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
}
