process.env.NODE_ENV = 'test';
import { aiCostMicros, aiTokenPrices, reserveAiRequest } from './ai-budget';
import type { AiRuntimeConfig } from './ai-settings.service';
import type { PrismaService } from '../prisma/prisma.service';

const config: AiRuntimeConfig = {
  provider: 'deepseek',
  model: 'deepseek-flash',
  apiKey: 'test',
  systemPrompt: '',
  requestsPerHour: 30,
  tokensPerDay: 200000,
  maxInputTokens: 32768,
  maxOutputTokens: 4096,
  dailyBudgetUsd: 1,
  monthlyBudgetUsd: 10,
};

function database() {
  interface UsageRow {
    id?: bigint;
    userId: string;
    provider?: string;
    model?: string;
    status?: string;
    promptTokens?: number;
    completionTokens?: number;
    reservedTokens?: number;
    costUsdMicros?: bigint;
    reservedCostUsdMicros?: bigint;
  }
  const rows: UsageRow[] = [];
  const lock = jest.fn().mockResolvedValue(0);
  const log = {
    count: ({ where }: { where: { userId?: string } }) =>
      Promise.resolve(rows.filter((row) => !where.userId || row.userId === where.userId).length),
    aggregate: ({ where }: { where: { userId?: string } }) =>
      Promise.resolve({
        _sum: rows
          .filter((row) => !where.userId || row.userId === where.userId)
          .reduce(
            (sum, row) => ({
              promptTokens: sum.promptTokens + (row.promptTokens ?? 0),
              completionTokens: sum.completionTokens + (row.completionTokens ?? 0),
              reservedTokens: sum.reservedTokens + (row.reservedTokens ?? 0),
              costUsdMicros: sum.costUsdMicros + (row.costUsdMicros ?? 0n),
              reservedCostUsdMicros: sum.reservedCostUsdMicros + (row.reservedCostUsdMicros ?? 0n),
            }),
            {
              promptTokens: 0,
              completionTokens: 0,
              reservedTokens: 0,
              costUsdMicros: 0n,
              reservedCostUsdMicros: 0n,
            },
          ),
      }),
    create: ({ data }: { data: UsageRow }) => {
      const row = { id: BigInt(rows.length + 1), ...data };
      rows.push(row);
      return Promise.resolve(row);
    },
  };
  let queue: Promise<unknown> = Promise.resolve();
  const db = {
    aiUsageLog: log,
    $transaction: (
      callback: (tx: { aiUsageLog: typeof log; $executeRaw: typeof lock }) => Promise<unknown>,
    ) => {
      const pending = queue.then(() => callback({ aiUsageLog: log, $executeRaw: lock }));
      queue = pending.catch(() => {});
      return pending;
    },
  } as unknown as PrismaService;
  return { db, rows, lock };
}

it('calculates conservative peak prices in integer USD micro-units', () => {
  expect(aiCostMicros('deepseek', 'deepseek-flash', 1000000, 1000000)).toBe(1500000n);
  expect(aiCostMicros('gemini', 'gemini-3.5-flash-lite', 1000000, 1000000)).toBe(2800000n);
  expect(() => aiCostMicros('gemini', 'invented-model', 1, 1)).toThrow();
  expect(aiTokenPrices('gemini', 'gemini-3.8-flash', new Date('2027-01-01'))).toEqual([1.5, 7.5]);
});

it('reserves the entire bounded tool loop before a provider request', async () => {
  const { db, rows, lock } = database();
  await reserveAiRequest(db, 'u1', config);
  expect(lock).toHaveBeenCalled();
  expect(rows[0]).toMatchObject({
    userId: 'u1',
    provider: 'deepseek',
    reservedTokens: 184320,
    reservedCostUsdMicros: 73728n,
    status: 'reserved',
  });
});

it('reserves the higher scheduled tariff if a request can cross the price-change boundary', async () => {
  jest.useFakeTimers().setSystemTime(new Date('2026-12-31T23:59:00Z'));
  try {
    const { db, rows } = database();
    await reserveAiRequest(db, 'u1', { ...config, provider: 'gemini', model: 'gemini-3.8-flash' });
    expect(rows[0]).toMatchObject({ reservedCostUsdMicros: 399360n });
  } finally {
    jest.useRealTimers();
  }
});

it('parallel requests cannot ignore in-flight per-user tokens', async () => {
  const { db, rows } = database();
  const results = await Promise.allSettled([
    reserveAiRequest(db, 'u1', config),
    reserveAiRequest(db, 'u1', config),
  ]);
  expect(results.map((result) => result.status).sort()).toEqual(['fulfilled', 'rejected']);
  expect(rows).toHaveLength(1);
});

it('the money budget is shared by different users and providers', async () => {
  const { db, rows } = database();
  await reserveAiRequest(db, 'u1', { ...config, dailyBudgetUsd: 0.1 });
  await expect(reserveAiRequest(db, 'u2', { ...config, dailyBudgetUsd: 0.1 })).rejects.toThrow(
    'ai.err.budgetLimit',
  );
  await expect(reserveAiRequest(db, 'u2', { ...config, monthlyBudgetUsd: 0.1 })).rejects.toThrow(
    'ai.err.budgetLimit',
  );
  expect(rows).toHaveLength(1);
});

it('counts in-flight requests against the hourly allowance', async () => {
  const { db } = database();
  await reserveAiRequest(db, 'u1', { ...config, requestsPerHour: 1 });
  await expect(
    reserveAiRequest(db, 'u1', { ...config, requestsPerHour: 1, tokensPerDay: 1000000 }),
  ).rejects.toThrow('ai.err.rateLimit');
});
