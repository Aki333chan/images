process.env.NODE_ENV = 'test';

import { TicketsService } from './tickets.service';
import { PrismaService } from '../prisma/prisma.service';
import { EventsGateway } from '../ws/events.gateway';
import { TicketDeliveryRegistry } from './ticket-delivery.registry';

describe('TicketsService.createOrAppendTicket', () => {
  let prisma: {
    ticket: { findFirst: jest.Mock; findUnique: jest.Mock; create: jest.Mock; update: jest.Mock };
  };
  let ws: { emitTicketsUpdated: jest.Mock };
  let delivery: { deliver: jest.Mock };
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
    delivery = { deliver: jest.fn().mockResolvedValue(undefined) };
    service = new TicketsService(
      prisma as unknown as PrismaService,
      ws as unknown as EventsGateway,
      delivery as unknown as TicketDeliveryRegistry,
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

describe('TicketsService.respond — доставка ответа в игру', () => {
  const now = new Date();
  const baseTicket = {
    id: 't1',
    serverId: 'srv-1',
    playerUuid: 'p-uuid',
    playerNameCached: 'Steve',
    status: 'OPEN',
    messages: [{ text: 'первое', from: 'player', created_at: now.toISOString() }],
    createdAt: now,
    updatedAt: now,
  };

  function makeService(moduleId: string | null, deliverImpl?: jest.Mock) {
    const prisma = {
      ticket: {
        findUnique: jest.fn().mockResolvedValue({ ...baseTicket, server: { name: 'Выживание' } }),
        update: jest.fn().mockImplementation(({ data }: { data: Record<string, unknown> }) =>
          Promise.resolve({
            ...baseTicket,
            ...data,
            server: { name: 'Выживание', moduleId },
          }),
        ),
        findFirst: jest.fn(),
        create: jest.fn(),
      },
    };
    const delivery = { deliver: deliverImpl ?? jest.fn().mockResolvedValue(undefined) };
    const service = new TicketsService(
      prisma as unknown as PrismaService,
      { emitTicketsUpdated: jest.fn() } as unknown as EventsGateway,
      delivery as unknown as TicketDeliveryRegistry,
    );
    return { service, delivery };
  }

  it('передаёт ответ модулю сервера вместе с ником и UUID игрока', async () => {
    const { service, delivery } = makeService('minecraft');
    await service.respond('t1', 'user-1', 'Разберёмся');

    expect(delivery.deliver).toHaveBeenCalledWith('minecraft', {
      serverId: 'srv-1',
      playerUuid: 'p-uuid',
      playerName: 'Steve',
      text: 'Разберёмся',
    });
  });

  it('для сервера без модуля доставка не вызывается с id модуля', async () => {
    const { service, delivery } = makeService(null);
    await service.respond('t1', 'user-1', 'Ответ');
    expect(delivery.deliver).toHaveBeenCalledWith(null, expect.anything());
  });

  it('ошибка доставки не ломает сохранение ответа', async () => {
    const failing = jest.fn().mockRejectedValue(new Error('сервер выключен'));
    const { service } = makeService('minecraft', failing);

    const result = await service.respond('t1', 'user-1', 'Ответ');

    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]?.text).toBe('Ответ');
  });
});

describe('TicketsService.resolveId', () => {
  const FULL = '58d581d9-4c9e-4f30-9a7e-1f2b3c4d5e6f';

  function setup(options: { exact?: unknown; matches?: { id: string }[] } = {}) {
    const prisma = {
      ticket: {
        findFirst: jest.fn().mockResolvedValue(options.exact ?? null),
        findMany: jest.fn().mockResolvedValue(options.matches ?? []),
      },
    };
    const service = new TicketsService(
      prisma as unknown as PrismaService,
      { emitTicketsUpdated: jest.fn() } as unknown as EventsGateway,
      { deliver: jest.fn() } as unknown as TicketDeliveryRegistry,
    );
    return { service, prisma };
  }

  const owner = {
    userId: 'u1',
    role: 'OWNER' as const,
    permissions: new Set<never>(),
    allowedServerIds: null,
    isOwner: true,
  };

  it('полный id возвращается как есть', async () => {
    const { service, prisma } = setup({ exact: { id: FULL } });

    await expect(service.resolveId(owner, FULL)).resolves.toBe(FULL);
    // Второй запрос не нужен: точное совпадение нашлось сразу.
    expect(prisma.ticket.findMany).not.toHaveBeenCalled();
  });

  it('обрезанный id разрешается по началу — на этом спотыкался ассистент', async () => {
    // Именно так модель пересказывает список тикетов человеку: «58d581d9…».
    const { service } = setup({ matches: [{ id: FULL }] });

    await expect(service.resolveId(owner, '58d581d9')).resolves.toBe(FULL);
  });

  it('многоточие и пробелы в конце не мешают', async () => {
    const { service, prisma } = setup({ matches: [{ id: FULL }] });

    await expect(service.resolveId(owner, ' 58d581d9… ')).resolves.toBe(FULL);
    expect(prisma.ticket.findMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: expect.objectContaining({ id: { startsWith: '58d581d9' } }) }),
    );
  });

  it('слишком короткое начало отвергается, а не угадывается', async () => {
    const { service, prisma } = setup();

    await expect(service.resolveId(owner, '58d5')).rejects.toThrow(/целиком/);
    expect(prisma.ticket.findMany).not.toHaveBeenCalled();
  });

  it('неоднозначное начало — ошибка со списком, а не «первый попавшийся»', async () => {
    const { service } = setup({ matches: [{ id: `${FULL}` }, { id: '58d581d9-0000-0000-0000-000000000000' }] });

    await expect(service.resolveId(owner, '58d581d9')).rejects.toThrow(/нескольким тикетам/);
  });

  it('поиск ограничен серверами, к которым есть доступ', async () => {
    const { service, prisma } = setup({ matches: [{ id: FULL }] });
    const moderator = { ...owner, isOwner: false, allowedServerIds: new Set(['srv-1']) };

    await service.resolveId(moderator, '58d581d9');

    expect(prisma.ticket.findMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: expect.objectContaining({ serverId: { in: ['srv-1'] } }),
      }),
    );
  });
});
