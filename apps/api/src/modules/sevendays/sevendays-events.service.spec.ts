import { randomUUID } from 'node:crypto';
import { SevenDaysEventsService, type IncomingEvent } from './sevendays-events.service';
import { PrismaService } from '../../prisma/prisma.service';

describe('7DTD event ingestion', () => {
  const now = Date.UTC(2026, 8, 18, 14);
  let createMany: jest.Mock;
  let deleteMany: jest.Mock;
  let findMany: jest.Mock;
  let service: SevenDaysEventsService;
  const event = (overrides: Partial<IncomingEvent> = {}): IncomingEvent => ({
    eventId: randomUUID(),
    kind: 'chat',
    playerId: 'Steam_test',
    playerName: 'Tester',
    occurredAt: new Date(now).toISOString(),
    text: 'hello',
    ...overrides,
  });
  beforeEach(() => {
    jest.spyOn(Date, 'now').mockReturnValue(now);
    const stored = new Set<string>();
    createMany = jest.fn(async ({ data, skipDuplicates }) => {
      expect(skipDuplicates).toBe(true);
      let count = 0;
      for (const row of data) {
        const id = row.id ?? randomUUID();
        if (!stored.has(id)) {
          stored.add(id);
          count++;
        }
      }
      return { count };
    });
    deleteMany = jest.fn().mockResolvedValue({ count: 0 });
    findMany = jest.fn().mockResolvedValue([]);
    service = new SevenDaysEventsService({
      sevenDaysEvent: { createMany, deleteMany, findMany },
    } as unknown as PrismaService);
  });
  afterEach(() => jest.restoreAllMocks());

  it('deduplicates a repeated batch and overlapping concurrent deliveries', async () => {
    const e = event();
    const results = await Promise.all([service.ingest('s1', [e, e]), service.ingest('s1', [e])]);
    expect(results.reduce((sum, r) => sum + r.accepted, 0)).toBe(1);
    expect(results.reduce((sum, r) => sum + r.duplicates, 0)).toBe(2);
  });
  it('scopes IDs by authenticated server, canonicalizes UUID case, survives service restart', async () => {
    const e = event();
    expect((await service.ingest('s1', [e])).accepted).toBe(1);
    expect((await service.ingest('s2', [e])).accepted).toBe(1);
    const restarted = new SevenDaysEventsService({
      sevenDaysEvent: { createMany, deleteMany },
    } as unknown as PrismaService);
    expect(
      (await restarted.ingest('s1', [{ ...e, eventId: e.eventId!.toUpperCase() }])).duplicates,
    ).toBe(1);
    expect(createMany.mock.calls[0][0].data[0].id).toMatch(/^sdtd_[0-9a-f]{64}$/);
  });
  it('keeps separate occurrences with identical content, accepts legacy without guessing IDs', async () => {
    const e = event();
    expect((await service.ingest('s1', [e, { ...e, eventId: randomUUID() }])).accepted).toBe(2);
    expect(
      (
        await service.ingest('s1', [
          { ...e, eventId: undefined },
          { ...e, eventId: undefined },
        ])
      ).accepted,
    ).toBe(2);
  });
  it('discards bad, expired and implausibly future events without blocking valid rows', async () => {
    const valid = event();
    const result = await service.ingest('s1', [
      valid,
      event({ eventId: 'bad' }),
      event({ occurredAt: new Date(now - 15 * 86400_000).toISOString() }),
      event({ occurredAt: new Date(now + 301_000).toISOString() }),
      event({ kind: 'unknown' }),
      event({ occurredAt: 'bad' }),
      null as unknown as IncomingEvent,
    ]);
    expect(result).toEqual({ accepted: 1, duplicates: 0, discarded: 6 });
  });
  it('bounds batches and does not prune after every batch', async () => {
    expect(
      (
        await service.ingest(
          's1',
          Array.from({ length: 205 }, () => event()),
        )
      ).discarded,
    ).toBe(5);
    await service.ingest('s1', [event()]);
    expect(deleteMany).toHaveBeenCalledTimes(1);
    jest.spyOn(Date, 'now').mockReturnValue(now + 3600_001);
    await service.ingest('s1', [event()]);
    expect(deleteMany).toHaveBeenCalledTimes(2);
  });
  it('propagates failed inserts so the mod retries instead of losing its batch', async () => {
    createMany.mockRejectedValueOnce(new Error('DB unavailable'));
    await expect(service.ingest('s1', [event()])).rejects.toThrow('DB unavailable');
    expect(deleteMany).not.toHaveBeenCalled();
  });
  it('filters expired rows on reads even before physical cleanup', async () => {
    await service.list('s1');
    expect(findMany.mock.calls[0][0].where.occurredAt.gte.getTime()).toBe(now - 14 * 86400_000);
  });
});
