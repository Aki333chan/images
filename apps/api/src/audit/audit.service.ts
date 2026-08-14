import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { AuditLogDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';

export interface AuditEntry {
  actorId: string | null;
  actorType?: 'user' | 'ai';
  action: string;
  targetType?: string | null;
  targetId?: string | null;
  metadata?: unknown;
}

@Injectable()
export class AuditService {
  constructor(private readonly prisma: PrismaService) {}

  async log(entry: AuditEntry): Promise<void> {
    await this.prisma.auditLog.create({
      data: {
        actorId: entry.actorId,
        actorType: entry.actorType ?? 'user',
        action: entry.action,
        targetType: entry.targetType ?? null,
        targetId: entry.targetId ?? null,
        metadata: (entry.metadata as Prisma.InputJsonValue) ?? Prisma.JsonNull,
      },
    });
  }

  async query(filters: {
    actorId?: string;
    action?: string;
    targetType?: string;
    from?: Date;
    to?: Date;
    page?: number;
    pageSize?: number;
  }): Promise<{ items: AuditLogDto[]; total: number }> {
    const where: Prisma.AuditLogWhereInput = {
      ...(filters.actorId ? { actorId: filters.actorId } : {}),
      ...(filters.action ? { action: { contains: filters.action, mode: 'insensitive' } } : {}),
      ...(filters.targetType ? { targetType: filters.targetType } : {}),
      ...(filters.from || filters.to
        ? { createdAt: { ...(filters.from ? { gte: filters.from } : {}), ...(filters.to ? { lte: filters.to } : {}) } }
        : {}),
    };
    const page = filters.page ?? 1;
    const pageSize = Math.min(filters.pageSize ?? 50, 200);
    const [rows, total] = await this.prisma.$transaction([
      this.prisma.auditLog.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * pageSize,
        take: pageSize,
      }),
      this.prisma.auditLog.count({ where }),
    ]);

    const actorIds = [...new Set(rows.map((r) => r.actorId).filter((v): v is string => !!v))];
    const actors = await this.prisma.user.findMany({
      where: { id: { in: actorIds } },
      select: { id: true, email: true },
    });
    const emailById = new Map(actors.map((a) => [a.id, a.email]));

    return {
      total,
      items: rows.map((r) => ({
        id: r.id.toString(),
        actorId: r.actorId,
        actorEmail: r.actorId ? (emailById.get(r.actorId) ?? null) : null,
        actorType: r.actorType,
        action: r.action,
        targetType: r.targetType,
        targetId: r.targetId,
        metadata: r.metadata,
        createdAt: r.createdAt.toISOString(),
      })),
    };
  }
}
