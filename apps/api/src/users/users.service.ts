import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { Role, UserAdminDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';

@Injectable()
export class UsersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ws: EventsGateway,
  ) {}

  private toDto(user: {
    id: string;
    email: string;
    nickname: string | null;
    nicknameChangeAllowed: boolean;
    role: Role;
    isActive: boolean;
    totpEnabled: boolean;
    createdAt: Date;
    serverAccess: { serverId: string }[];
  }): UserAdminDto {
    return {
      id: user.id,
      email: user.email,
      nickname: user.nickname,
      nicknameChangeAllowed: user.nicknameChangeAllowed,
      role: user.role,
      isActive: user.isActive,
      totpEnabled: user.totpEnabled,
      serverIds: user.serverAccess.map((a) => a.serverId),
      createdAt: user.createdAt.toISOString(),
    };
  }

  /** Минимум данных для выдачи одноразового пароля. */
  async getForProvisioning(userId: string): Promise<{ id: string; email: string }> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { id: true, email: true },
    });
    if (!user) throw new NotFoundException('users.err.notFound');
    return user;
  }

  async list(): Promise<UserAdminDto[]> {
    const users = await this.prisma.user.findMany({
      include: { serverAccess: { select: { serverId: true } } },
      orderBy: { createdAt: 'asc' },
    });
    return users.map((u) => this.toDto(u));
  }

  async update(
    actorId: string,
    userId: string,
    patch: { role?: Role; isActive?: boolean; nicknameChangeAllowed?: boolean },
  ): Promise<UserAdminDto> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new NotFoundException('users.err.notFound');

    // Защита от самоблокировки: нельзя понизить/деактивировать последнего OWNER.
    const demotingOwner =
      user.role === 'OWNER' && ((patch.role && patch.role !== 'OWNER') || patch.isActive === false);
    if (demotingOwner) {
      const owners = await this.prisma.user.count({ where: { role: 'OWNER', isActive: true } });
      if (owners <= 1) throw new BadRequestException('users.err.lastOwner');
    }

    const updated = await this.prisma.user.update({
      where: { id: userId },
      data: patch,
      include: { serverAccess: { select: { serverId: true } } },
    });

    if (patch.role !== undefined || patch.isActive !== undefined) {
      if (patch.isActive === false) {
        // Немедленно отзываем все сессии деактивированного пользователя.
        await this.prisma.session.updateMany({
          where: { userId, revokedAt: null },
          data: { revokedAt: new Date() },
        });
        this.ws.emitPermissionsUpdated(userId, { reason: 'deactivated' });
      } else {
        this.ws.emitPermissionsUpdated(userId, { reason: 'role' });
      }
    }
    return this.toDto(updated);
  }

  async setServerAccess(userId: string, serverIds: string[]): Promise<UserAdminDto> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new NotFoundException('users.err.notFound');

    const servers = await this.prisma.server.findMany({
      where: { id: { in: serverIds } },
      select: { id: true },
    });
    if (servers.length !== new Set(serverIds).size) {
      throw new BadRequestException('users.err.unknownServers');
    }

    await this.prisma.$transaction([
      this.prisma.userServerAccess.deleteMany({ where: { userId } }),
      this.prisma.userServerAccess.createMany({
        data: serverIds.map((serverId) => ({ userId, serverId })),
      }),
    ]);

    this.ws.emitPermissionsUpdated(userId, { reason: 'servers' });

    const updated = await this.prisma.user.findUniqueOrThrow({
      where: { id: userId },
      include: { serverAccess: { select: { serverId: true } } },
    });
    return this.toDto(updated);
  }
}
