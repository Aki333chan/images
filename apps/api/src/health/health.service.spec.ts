process.env.NODE_ENV = 'test';

import { HealthService } from './health.service';
import type { PrismaService } from '../prisma/prisma.service';

describe('HealthService', () => {
  function makeService(queryImpl: jest.Mock) {
    return new HealthService({ $queryRaw: queryImpl } as unknown as PrismaService);
  }

  it('liveness не ходит в зависимости и всегда отвечает ok', () => {
    const query = jest.fn();
    const result = makeService(query).liveness();

    expect(result.status).toBe('ok');
    expect(result.uptimeSeconds).toBeGreaterThanOrEqual(0);
    // Ключевое: liveness не должен краснеть из-за недоступной БД.
    expect(query).not.toHaveBeenCalled();
  });

  it('readiness помечает БД как fail, если запрос падает', async () => {
    const service = makeService(jest.fn().mockRejectedValue(new Error('connection refused')));
    const result = await service.readiness();

    expect(result.checks.database).toBe('fail');
    expect(result.ready).toBe(false);
  });

  it('readiness не виснет, если БД не отвечает', async () => {
    // Запрос, который никогда не завершится: нас спасает только таймаут.
    const service = makeService(jest.fn().mockReturnValue(new Promise(() => {})));
    const started = Date.now();

    const result = await service.readiness();

    expect(result.checks.database).toBe('fail');
    expect(Date.now() - started).toBeLessThan(5000);
  });

  it('в ответе нет подробностей об ошибке — они только в журнале', async () => {
    const service = makeService(
      jest.fn().mockRejectedValue(new Error('password authentication failed for user "panel"')),
    );
    const result = await service.readiness();

    const serialized = JSON.stringify(result);
    expect(serialized).not.toContain('password');
    expect(serialized).not.toContain('panel');
  });

  it('readiness сообщает про Redis отдельно от БД', async () => {
    const service = makeService(jest.fn().mockResolvedValue([{ '?column?': 1 }]));
    const result = await service.readiness();

    // Redis в тестовом окружении не поднят — БД при этом должна быть ok.
    expect(result.checks.database).toBe('ok');
    expect(result.checks.redis).toBe('fail');
    expect(result.ready).toBe(false);
  });
});
