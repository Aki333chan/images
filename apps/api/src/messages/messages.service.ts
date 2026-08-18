import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import type { ConversationDto, StaffContactDto, StaffMessageDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';
import { normalizeNickname } from '../users/onboarding.service';

/** Потолок длины сообщения: это переписка, а не файлообменник. */
const MAX_TEXT_LENGTH = 4000;

/**
 * Личные сообщения между сотрудниками панели.
 *
 * ПРИВАТНОСТЬ. Каждая выборка обязательно ограничена участием текущего
 * пользователя. Роль не даёт доступа к чужой переписке никому, включая ГМ:
 * это не игровое действие и не объект модерации, поэтому и отдельных
 * permission-ключей здесь нет — доступ есть у всех, но только к своему.
 *
 * По той же причине сообщения не попадают в журнал аудита: он читается
 * администраторами, а содержимое переписки им видеть не положено.
 */
@Injectable()
export class MessagesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ws: EventsGateway,
  ) {}

  /** Сотрудники с ником — для автодополнения. Себя в списке не показываем. */
  async contacts(currentUserId: string, query?: string): Promise<StaffContactDto[]> {
    const rows = await this.prisma.user.findMany({
      where: {
        id: { not: currentUserId },
        nickname: { not: null },
        isActive: true,
        status: 'active',
      },
      select: { id: true, nickname: true },
      orderBy: { nickname: 'asc' },
    });

    const needle = query ? normalizeNickname(query) : '';
    return rows
      .filter((r) => !needle || normalizeNickname(r.nickname!).includes(needle))
      .slice(0, 20)
      .map((r) => ({ id: r.id, nickname: r.nickname! }));
  }

  /** Список диалогов: последний обмен с каждым собеседником. */
  async conversations(currentUserId: string): Promise<ConversationDto[]> {
    const messages = await this.prisma.staffMessage.findMany({
      where: { OR: [{ fromUserId: currentUserId }, { toUserId: currentUserId }] },
      orderBy: { createdAt: 'desc' },
      include: {
        from: { select: { id: true, nickname: true } },
        to: { select: { id: true, nickname: true } },
      },
    });

    const byPeer = new Map<string, ConversationDto>();
    for (const message of messages) {
      const outgoing = message.fromUserId === currentUserId;
      const peer = outgoing ? message.to : message.from;

      const existing = byPeer.get(peer.id);
      if (!existing) {
        // Первое встреченное сообщение и есть последнее: список отсортирован
        // по убыванию времени.
        byPeer.set(peer.id, {
          peer: { id: peer.id, nickname: peer.nickname },
          lastMessage: {
            text: message.text,
            createdAt: message.createdAt.toISOString(),
            outgoing,
          },
          unread: !outgoing && message.readAt === null ? 1 : 0,
        });
        continue;
      }
      if (!outgoing && message.readAt === null) existing.unread += 1;
    }

    return [...byPeer.values()];
  }

  /** Общее число непрочитанных — для бейджа в навигации. */
  async unreadCount(currentUserId: string): Promise<number> {
    return this.prisma.staffMessage.count({
      where: { toUserId: currentUserId, readAt: null },
    });
  }

  /**
   * Переписка с одним собеседником.
   *
   * Условие по обеим сторонам обязательно: без него, зная чужой id, можно
   * было бы прочитать чужой диалог.
   */
  async thread(currentUserId: string, peerId: string): Promise<StaffMessageDto[]> {
    const rows = await this.prisma.staffMessage.findMany({
      where: {
        OR: [
          { fromUserId: currentUserId, toUserId: peerId },
          { fromUserId: peerId, toUserId: currentUserId },
        ],
      },
      orderBy: { createdAt: 'asc' },
      take: 300,
    });
    return rows.map(toDto);
  }

  /** Отметить входящие в диалоге прочитанными. */
  async markRead(currentUserId: string, peerId: string): Promise<{ updated: number }> {
    const { count } = await this.prisma.staffMessage.updateMany({
      where: { toUserId: currentUserId, fromUserId: peerId, readAt: null },
      data: { readAt: new Date() },
    });
    if (count > 0) {
      // Своим же вкладкам: бейдж должен упасть везде, где человек открыт.
      this.ws.emitMessagesUpdated(currentUserId, { peerId, action: 'read' });
    }
    return { updated: count };
  }

  /** Отправка по нику собеседника. */
  async send(
    currentUserId: string,
    input: { nickname: string; text: string },
  ): Promise<StaffMessageDto> {
    const text = input.text.trim();
    if (!text) throw new BadRequestException('Пустое сообщение отправить нельзя');
    if (text.length > MAX_TEXT_LENGTH) {
      throw new BadRequestException(`Сообщение длиннее ${MAX_TEXT_LENGTH} символов`);
    }

    const peer = await this.findByNickname(input.nickname);
    if (!peer) throw new NotFoundException(`Сотрудник с ником «${input.nickname}» не найден`);
    if (peer.id === currentUserId) {
      throw new BadRequestException('Нельзя написать самому себе');
    }

    const message = await this.prisma.staffMessage.create({
      data: { fromUserId: currentUserId, toUserId: peer.id, text },
    });

    // Адресно получателю: сам факт переписки — тоже приватная информация,
    // и широковещательное событие её бы раскрыло.
    this.ws.emitMessagesUpdated(peer.id, { peerId: currentUserId, action: 'received' });
    return toDto(message);
  }

  /** Поиск по нику без учёта регистра и лишних пробелов. */
  private async findByNickname(nickname: string) {
    const normalized = normalizeNickname(nickname);
    const candidates = await this.prisma.user.findMany({
      where: { nickname: { not: null }, isActive: true, status: 'active' },
      select: { id: true, nickname: true },
    });
    return candidates.find((c) => normalizeNickname(c.nickname!) === normalized) ?? null;
  }
}

function toDto(message: {
  id: string;
  fromUserId: string;
  toUserId: string;
  text: string;
  readAt: Date | null;
  createdAt: Date;
}): StaffMessageDto {
  return {
    id: message.id,
    fromUserId: message.fromUserId,
    toUserId: message.toUserId,
    text: message.text,
    readAt: message.readAt ? message.readAt.toISOString() : null,
    createdAt: message.createdAt.toISOString(),
  };
}
