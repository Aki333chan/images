import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, Ticket } from '@prisma/client';
import { TicketDto, TicketMessage } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';
import { EffectivePermissions } from '../rbac/permissions.service';
import { TicketDeliveryRegistry } from './ticket-delivery.registry';

@Injectable()
export class TicketsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ws: EventsGateway,
    private readonly delivery: TicketDeliveryRegistry,
  ) {}

  private toDto(t: Ticket & { server?: { name: string } }): TicketDto {
    return {
      id: t.id,
      serverId: t.serverId,
      serverName: t.server?.name,
      playerUuid: t.playerUuid,
      playerNameCached: t.playerNameCached,
      status: t.status,
      messages: (t.messages as unknown as TicketMessage[]) ?? [],
      createdAt: t.createdAt.toISOString(),
      updatedAt: t.updatedAt.toISOString(),
    };
  }

  /**
   * Публичный сервис для игровых модулей: обращение игрока создаёт тикет или,
   * если по паре (serverId, playerUuid) уже есть открытый, добавляет сообщение
   * в него. Инвариант «один open-тикет на пару» дополнительно закреплён
   * частичным уникальным индексом в БД — при гонке повторяем как append.
   */
  async createOrAppendTicket(
    serverId: string,
    playerUuid: string,
    playerName: string,
    text: string,
  ): Promise<TicketDto> {
    const message: TicketMessage = {
      text,
      from: 'player',
      created_at: new Date().toISOString(),
    };

    const append = async (): Promise<Ticket | null> => {
      const existing = await this.prisma.ticket.findFirst({
        where: { serverId, playerUuid, status: 'OPEN' },
      });
      if (!existing) return null;
      const messages = [...((existing.messages as unknown as TicketMessage[]) ?? []), message];
      return this.prisma.ticket.update({
        where: { id: existing.id },
        data: {
          messages: messages as unknown as Prisma.InputJsonValue,
          playerNameCached: playerName,
        },
      });
    };

    let ticket = await append();
    let action: 'created' | 'message' = 'message';
    if (!ticket) {
      try {
        ticket = await this.prisma.ticket.create({
          data: {
            serverId,
            playerUuid,
            playerNameCached: playerName,
            messages: [message] as unknown as Prisma.InputJsonValue,
          },
        });
        action = 'created';
      } catch (e) {
        // Гонка с частичным уникальным индексом: параллельно создался open-тикет.
        if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
          ticket = await append();
          if (!ticket) throw e;
        } else {
          throw e;
        }
      }
    }

    this.ws.emitTicketsUpdated({ serverId, ticketId: ticket.id, action });
    return this.toDto(ticket);
  }

  /** Открытый тикет пары (сервер, игрок) или null. */
  async findOpenTicket(serverId: string, playerUuid: string): Promise<Ticket | null> {
    return this.prisma.ticket.findFirst({ where: { serverId, playerUuid, status: 'OPEN' } });
  }

  async list(eff: EffectivePermissions, status: 'OPEN' | 'CLOSED' = 'OPEN'): Promise<TicketDto[]> {
    const tickets = await this.prisma.ticket.findMany({
      where: {
        status,
        ...(eff.allowedServerIds === null ? {} : { serverId: { in: [...eff.allowedServerIds] } }),
      },
      include: { server: { select: { name: true } } },
      orderBy: { updatedAt: 'desc' },
    });
    return tickets.map((t) => this.toDto(t));
  }

  async openCount(eff: EffectivePermissions): Promise<number> {
    return this.prisma.ticket.count({
      where: {
        status: 'OPEN',
        ...(eff.allowedServerIds === null ? {} : { serverId: { in: [...eff.allowedServerIds] } }),
      },
    });
  }

  async getById(id: string): Promise<TicketDto & { raw: Ticket }> {
    const ticket = await this.prisma.ticket.findUnique({
      where: { id },
      include: { server: { select: { name: true } } },
    });
    if (!ticket) throw new NotFoundException('Тикет не найден');
    return { ...this.toDto(ticket), raw: ticket };
  }

  /** Ответ сотрудника панели; from = id пользователя. */
  async respond(ticketId: string, responderId: string, text: string): Promise<TicketDto> {
    const { raw } = await this.getById(ticketId);
    const messages = [
      ...((raw.messages as unknown as TicketMessage[]) ?? []),
      { text, from: responderId, created_at: new Date().toISOString() },
    ];
    const updated = await this.prisma.ticket.update({
      where: { id: ticketId },
      data: { messages: messages as unknown as Prisma.InputJsonValue },
      include: { server: { select: { name: true, moduleId: true } } },
    });
    this.ws.emitTicketsUpdated({ serverId: updated.serverId, ticketId, action: 'message' });

    // Доставка ответа в игру — best-effort и не блокирует ответ API:
    // игрок может быть оффлайн, а игровой сервер выключен.
    // catch обязателен: необработанный reject в фоне уронил бы весь процесс.
    void this.delivery
      .deliver(updated.server.moduleId, {
        serverId: updated.serverId,
        playerUuid: updated.playerUuid,
        playerName: updated.playerNameCached,
        text,
      })
      .catch(() => undefined);

    return this.toDto(updated);
  }

  async close(ticketId: string): Promise<TicketDto> {
    const updated = await this.prisma.ticket.update({
      where: { id: ticketId },
      data: { status: 'CLOSED' },
      include: { server: { select: { name: true } } },
    });
    this.ws.emitTicketsUpdated({ serverId: updated.serverId, ticketId, action: 'closed' });
    return this.toDto(updated);
  }
}
