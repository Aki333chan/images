process.env.NODE_ENV = 'test';

import { TicketsService } from './tickets.service';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';

describe('TicketsService.createOrAppendTicket', () => {
  let prisma: {
    ticket: { findFirst: jest.Mock; findUnique: jest.Mock; create: jest.Mock; update: jest.Mock };
  };
  let ws: { emitTicketsUpdated: jest.Mock };
  let service: TicketsService;

  beforeEach(() => {
    prisma = {
      ticket: {
        findFirst: jest.fn(),
        findUnique: jest.fn(),
        create: jest.fn(),
        update: jest.fn(),
      },
    };
    ws = { emitTicketsUpdated: jest.fn() };
    service = new TicketsService(
      prisma as unknown as PrismaService,
      ws as unknown as EventsGateway,
    );
  });

  const now = new Date();
  const openTicket = {
    id: 't1',
    serverId: 'srv-1',
    playerUuid: 'p-uuid',
    playerNameCached: 'Steve',
    status: 'OPEN',
    messages: [{ text: 'первое', from: 'player', created_at: now.toISOString() }],
    createdAt: now,
    updatedAt: now,
  };

  it('создаёт новый тикет, если открытого для пары (server, player) нет', async () => {
    prisma.ticket.findFirst.mockResolvedValue(null);
    prisma.ticket.create.mockImplementation(({ data }: { data: Record<string, unknown> }) =>
      Promise.resolve({ ...openTicket, ...data, id: 't-new' }),
    );

    const result = await service.createOrAppendTicket('srv-1', 'p-uuid', 'Steve', 'помогите');
    expect(prisma.ticket.create).toHaveBeenCalled();
    expect(result.messages).toHaveLength(1);
    expect(result.messages[0]?.text).toBe('помогите');
    expect(ws.emitTicketsUpdated).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'created' }),
    );
  });

  it('повторное обращение добавляет сообщение в существующий open-тикет', async () => {
    prisma.ticket.findFirst.mockResolvedValue({ ...openTicket });
    prisma.ticket.update.mockImplementation(({ data }: { data: Record<string, unknown> }) =>
      Promise.resolve({ ...openTicket, ...data }),
    );

    const result = await service.createOrAppendTicket('srv-1', 'p-uuid', 'SteveRenamed', 'ещё вопрос');
    expect(prisma.ticket.create).not.toHaveBeenCalled();
    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]?.text).toBe('ещё вопрос');
    // имя игрока обновляется в кэше
    expect(prisma.ticket.update.mock.calls[0][0].data.playerNameCached).toBe('SteveRenamed');
    expect(ws.emitTicketsUpdated).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'message' }),
    );
  });
});
