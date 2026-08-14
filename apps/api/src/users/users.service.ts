import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import * as argon2 from 'argon2';
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
    displayName: string;
    role: Role;
    isActive: boolean;
    totpEnabled: boolean;
    createdAt: Date;
    serverAccess: { serverId: string }[];
  }): UserAdminDto {
    return {
      id: user.id,
      email: user.email,
      displayName: user.displayName,
      role: user.role,
      isActive: user.isActive,
      totpEnabled: user.totpEnabled,
      serverIds: user.serverAccess.map((a) => a.serverId),
      createdAt: user.createdAt.toISOString(),
    };
  }

  async list(): Promise<UserAdminDto[]> {
    const users = await this.prisma.user.findMany({
      include: { serverAccess: { select: { serverId: true } } },
      orderBy: { createdAt: 'asc' },
    });
    return users.map((u) => this.toDto(u));
  }

  async create(input: {
    email: string;
    password: string;
    displayName: string;
    role: Role;
  }): Promise<UserAdminDto> {
    const email = input.email.toLowerCase();
    if (await this.prisma.user.findUnique({ where: { email } })) {
      throw new ConflictException('Пользователь с таким email уже существует');
    }
    const user = await this.prisma.user.create({
      data: {
        email,
        passwordHash: await argon2.hash(input.password),
        displayName: input.displayName,
        role: input.role,
      },
      include: { serverAccess: { select: { serverId: true } } },
    });
    return this.toDto(user);
  }

  async update(
    actorId: string,
    userId: string,
    patch: { role?: Role; isActive?: boolean; displayName?: string },
  ): Promise<UserAdminDto> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new NotFoundException('Пользователь не найден');

    // Защита от самоблокировки: нельзя понизить/деактивировать последнего OWNER.
    const demotingOwner =
      user.role === 'OWNER' && ((patch.role && patch.role !== 'OWNER') || patch.isActive === false);
    if (demotingOwner) {
      const owners = await this.prisma.user.count({ where: { role: 'OWNER', isActive: true } });
      if (owners <= 1) throw new BadRequestException('Нельзя убрать последнего ГМ');
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
    if (!user) throw new NotFoundException('Пользователь не найден');

    const servers = await this.prisma.server.findMany({
      where: { id: { in: serverIds } },
      select: { id: true },
    });
    if (servers.length !== new Set(serverIds).size) {
      throw new BadRequestException('Список содержит несуществующие сервера');
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
