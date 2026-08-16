process.env.NODE_ENV = 'test';

import { ActivityService, hourBucket } from './activity.service';
import type { PrismaService } from '../prisma/prisma.service';

interface FakeRow {
  serverId: string;
  bucket: Date;
  avgOnline: number;
  maxOnline: number;
  samples: number;
}

type WhereUnique = { where: { serverId_bucket: { serverId: string; bucket: Date } } };

/** Ряды в памяти вместо базы: проверяем арифметику и выборку. */
function fakePrisma() {
  const rows = new Map<string, FakeRow>();
  const key = (serverId: string, bucket: Date) => `${serverId}|${bucket.toISOString()}`;

  return {
    rows,
    serverActivitySample: {
      findUnique: async ({ where }: WhereUnique) =>
        rows.get(key(where.serverId_bucket.serverId, where.serverId_bucket.bucket)) ?? null,

      create: async ({ data }: { data: FakeRow }) => {
        rows.set(key(data.serverId, data.bucket), { ...data });
        return data;
      },

      update: async ({ where, data }: WhereUnique & { data: Partial<FakeRow> }) => {
        const k = key(where.serverId_bucket.serverId, where.serverId_bucket.bucket);
        const existing = rows.get(k)!;
        rows.set(k, { ...existing, ...data });
        return rows.get(k)!;
      },

      findMany: async ({ where }: { where: { serverId: string; bucket: { gte: Date } } }) =>
        [...rows.values()]
          .filter((r) => r.serverId === where.serverId && r.bucket >= where.bucket.gte)
          .sort((a, b) => a.bucket.getTime() - b.bucket.getTime()),

      deleteMany: async ({ where }: { where: { bucket: { lt: Date } } }) => {
        let count = 0;
        for (const [k, r] of rows) {
          if (r.bucket < where.bucket.lt) {
            rows.delete(k);
            count++;
          }
        }
        return { count };
      },
    },
  };
}

describe('hourBucket', () => {
  it('срезает минуты и секунды до начала часа', () => {
    expect(hourBucket(new Date('2026-08-15T17:43:29.500Z')).toISOString()).toBe(
      '2026-08-15T17:00:00.000Z',
    );
  });
});

describe('ActivityService.record', () => {
  it('первый замер создаёт ряд', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);

    await service.record('srv', 5, new Date('2026-08-15T10:10:00Z'));

    const row = [...prisma.rows.values()][0]!;
    expect(row).toMatchObject({ avgOnline: 5, maxOnline: 5, samples: 1 });
  });

  it('усредняет замеры внутри часа, не плодя рядов', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);

    // 4, 8, 0 в пределах одного часа -> среднее 4, пик 8.
    await service.record('srv', 4, new Date('2026-08-15T10:05:00Z'));
    await service.record('srv', 8, new Date('2026-08-15T10:35:00Z'));
    await service.record('srv', 0, new Date('2026-08-15T10:55:00Z'));

    expect(prisma.rows.size).toBe(1);
    const row = [...prisma.rows.values()][0]!;
    expect(row.avgOnline).toBeCloseTo(4);
    expect(row.maxOnline).toBe(8);
    expect(row.samples).toBe(3);
  });

  it('разные часы попадают в разные ряды', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);

    await service.record('srv', 1, new Date('2026-08-15T10:05:00Z'));
    await service.record('srv', 2, new Date('2026-08-15T11:05:00Z'));

    expect(prisma.rows.size).toBe(2);
  });
});

describe('ActivityService.history', () => {
  it('отдаёт замеры плоским списком в UTC', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);
    const now = new Date();

    await service.record('srv', 0, new Date(now.getTime() - 60 * 60 * 1000));

    const history = await service.history('srv', 7);
    expect(history.days).toBe(7);
    expect(history.samples).toHaveLength(1);
    expect(history.samples[0]!.online).toBe(0);
    // Именно ISO-строка: клиент разбирает её как UTC и раскладывает по своим суткам.
    expect(history.samples[0]!.bucket).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:00:00\.000Z$/);
  });

  it('пик считается по всем замерам периода', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);
    const now = new Date();

    await service.record('srv', 3, new Date(now.getTime() - 2 * 60 * 60 * 1000));
    await service.record('srv', 11, new Date(now.getTime() - 60 * 60 * 1000));

    expect((await service.history('srv', 2)).peak).toBe(11);
  });

  it('берёт запас в сутки, чтобы восточные пояса не теряли первую строку', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);
    const day = 24 * 60 * 60 * 1000;

    // Замер «двое суток назад» должен попасть в выборку за 2 суток:
    // окно берётся с запасом, иначе у UTC+12 первая строка была бы неполной.
    await service.record('srv', 7, new Date(Date.now() - 2 * day));

    expect((await service.history('srv', 2)).samples).toHaveLength(1);
  });

  it('ограничивает запрошенный период разумными рамками', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);

    expect((await service.history('srv', 0)).days).toBe(7); // 0 -> значение по умолчанию
    expect((await service.history('srv', 999)).days).toBe(31); // сверху ограничено
    expect((await service.history('srv', 5)).days).toBe(5);
  });
});

describe('ActivityService.prune', () => {
  it('удаляет замеры старше срока хранения и не трогает свежие', async () => {
    const prisma = fakePrisma();
    const service = new ActivityService(prisma as unknown as PrismaService);
    const now = Date.now();
    const day = 24 * 60 * 60 * 1000;

    await service.record('srv', 1, new Date(now - 90 * day));
    await service.record('srv', 2, new Date(now - day));

    expect(await service.prune(60)).toBe(1);
    expect(prisma.rows.size).toBe(1);
  });
});
