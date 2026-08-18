import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, Ticket } from '@prisma/client';
import { TicketDto, TicketMessage } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';
import { EffectivePermissions } from '../rbac/permissions.service';
import { TicketDeliveryRegistry } from './ticket-delivery.registry';

/**
 * Сколько символов начала id хватает, чтобы считать его указанием на тикет.
 * Восемь — длина первого сегмента UUID.
 */
const MIN_TICKET_PREFIX = 8;

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

  /**
   * Полный id тикета по тому, что назвали, — id целиком или его начало.
   *
   * Нужно из-за AI-ассистента: модель, пересказывая список тикетов человеку,
   * сокращает длинные id («58d581d9…»), а потом подставляет собственное
   * сокращение обратно в инструмент. Требовать от модели дисциплины бесполезно —
   * надёжнее принять префикс и разрешить его здесь, по тем же тикетам, которые
   * этому человеку и так видны.
   *
   * Минимальная длина префикса — 8 символов: это первый сегмент UUID, ровно то,
   * что модели обычно и показывают. Меньше — уже угадывание.
   *
   * Неоднозначный префикс — ошибка, а не «возьмём первый попавшийся»: закрыть
   * не тот тикет хуже, чем переспросить.
   */
  async resolveId(eff: EffectivePermissions, idOrPrefix: string): Promise<string> {
    // Многоточие в конце срезаем: модель сокращает id именно так («58d581d9…»),
    // и если она подставит своё сокращение целиком, это всё равно должно
    // сработать, а не превратиться в «тикет не найден».
    const raw = (idOrPrefix ?? '').trim().replace(/(?:…|\.{3})$/, '').trim();
    if (!raw) throw new NotFoundException('Не указан тикет');

    const scope: Prisma.TicketWhereInput =
      eff.allowedServerIds === null ? {} : { serverId: { in: [...eff.allowedServerIds] } };

    const exact = await this.prisma.ticket.findFirst({ where: { id: raw, ...scope } });
    if (exact) return exact.id;

    if (raw.length < MIN_TICKET_PREFIX) {
      throw new NotFoundException(
        `Тикет ${raw} не найден. Укажите id целиком (первых ${MIN_TICKET_PREFIX} символов тоже достаточно).`,
      );
    }

    const matches = await this.prisma.ticket.findMany({
      where: { id: { startsWith: raw }, ...scope },
      select: { id: true },
      take: 5,
    });
    if (matches.length === 1) return matches[0]!.id;
    if (matches.length === 0) {
      throw new NotFoundException(`Тикет ${raw} не найден`);
    }
    throw new NotFoundException(
      `Начало «${raw}» подходит сразу нескольким тикетам (${matches
        .map((m) => m.id)
        .join(', ')}) — укажите id целиком`,
    );
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
