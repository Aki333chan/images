import 'reflect-metadata';
import { SevenDaysEventsService } from './sevendays-events.service';
import { SevenDaysController } from './sevendays.controller';
import { PrismaService } from '../../prisma/prisma.service';

const rows = Array.from({ length: 11 }, (_, i) => ({
  id: `sdtd_${100 - i}`,
  serverId: 's',
  kind: 'join',
  playerId: 'Steam_1',
  playerName: 'Alice',
  actorId: null,
  actorName: null,
  text: null,
  x: null,
  y: null,
  z: null,
  occurredAt: new Date('2026-09-20T12:00:00Z'),
  createdAt: new Date(),
}));
function fixture() {
  const findMany = jest.fn().mockResolvedValue(rows);
  return {
    findMany,
    service: new SevenDaysEventsService({
      sevenDaysEvent: { findMany },
    } as unknown as PrismaService),
  };
}
describe('7DTD player history', () => {
  it('requires event permission and server scope, not inventory permission', () => {
    expect(
      Reflect.getMetadata('requiredPermission', SevenDaysController.prototype.playerHistory),
    ).toEqual(['sevendays.events.view']);
    expect(
      Reflect.getMetadata('serverScopeParam', SevenDaysController.prototype.playerHistory),
    ).toBe('serverId');
  });
  it('includes subject and actor, retains server/time scope and uses bounded stable pagination', async () => {
    const { service, findMany } = fixture();
    const first = await service.history('s', 'Steam_1', 'player-kill');
    expect(first.events).toHaveLength(10);
    expect(first.retentionDays).toBe(14);
    expect(first.nextCursor).toBeTruthy();
    const args = findMany.mock.calls[0][0];
    expect(args.take).toBe(11);
    expect(args.orderBy).toEqual([{ occurredAt: 'desc' }, { id: 'desc' }]);
    expect(args.where.serverId).toBe('s');
    expect(args.where.kind).toBe('player-kill');
    expect(args.where.AND[0]).toEqual({
      OR: [{ playerId: { in: ['Steam_1'] } }, { actorId: { in: ['Steam_1'] } }],
    });
    expect(args.where.occurredAt.gte.getTime()).toBeGreaterThan(Date.now() - 14 * 86400000 - 1000);
    findMany.mockResolvedValueOnce([]);
    const last = await service.history('s', 'Steam_1', 'player-kill', first.nextCursor!);
    expect(last.nextCursor).toBeNull();
    expect(findMany.mock.calls[1][0].where.AND[1]).toEqual({
      OR: [
        { occurredAt: { lt: rows[9]!.occurredAt } },
        { occurredAt: rows[9]!.occurredAt, id: { lt: rows[9]!.id } },
      ],
    });
  });
  it('rejects invalid IDs, filters and cursors before querying', async () => {
    const { service, findMany } = fixture();
    await expect(service.history('s', '../other')).rejects.toThrow();
    await expect(service.history('s', 'Steam_1', '', undefined, '../other')).rejects.toThrow();
    await expect(service.history('s', 'Steam_1', 'bad')).rejects.toThrow();
    for (const cursor of [
      '!',
      'x'.repeat(257),
      Buffer.from(JSON.stringify({ at: 'bad', id: 'a' })).toString('base64url'),
      Buffer.from(JSON.stringify({ at: rows[0]!.occurredAt.toISOString(), id: '../' })).toString(
        'base64url',
      ),
    ])
      await expect(service.history('s', 'Steam_1', '', cursor)).rejects.toThrow(
        'invalid_event_cursor',
      );
    expect(findMany).not.toHaveBeenCalled();
  });
  it('includes the explicit cross-platform ID without guessing identities by name', async () => {
    const { service, findMany } = fixture();
    await service.history('s', 'Steam_1', '', undefined, 'EOS_2');
    expect(findMany.mock.calls[0][0].where.AND[0]).toEqual({
      OR: [{ playerId: { in: ['Steam_1', 'EOS_2'] } }, { actorId: { in: ['Steam_1', 'EOS_2'] } }],
    });
  });
});
