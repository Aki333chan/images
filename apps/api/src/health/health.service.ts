import { Injectable, Logger } from '@nestjs/common';
import Redis from 'ioredis';
import { env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

export interface LivenessResult {
  status: 'ok';
  uptimeSeconds: number;
}

export interface ReadinessResult {
  ready: boolean;
  checks: {
    database: 'ok' | 'fail';
    redis: 'ok' | 'fail';
  };
}

/** Проверка длиннее этого времени считается провалом — health не должен висеть. */
const CHECK_TIMEOUT_MS = 2000;

@Injectable()
export class HealthService {
  private readonly logger = new Logger(HealthService.name);

  constructor(private readonly prisma: PrismaService) {}

  liveness(): LivenessResult {
    return { status: 'ok', uptimeSeconds: Math.round(process.uptime()) };
  }

  async readiness(): Promise<ReadinessResult> {
    const [database, redis] = await Promise.all([this.checkDatabase(), this.checkRedis()]);
    return {
      ready: database === 'ok' && redis === 'ok',
      checks: { database, redis },
    };
  }

  private async checkDatabase(): Promise<'ok' | 'fail'> {
    try {
      await withTimeout(this.prisma.$queryRaw`SELECT 1`, CHECK_TIMEOUT_MS);
      return 'ok';
    } catch (e) {
      // Подробности — только в журнал, наружу они не уходят.
      this.logger.warn(`Проверка БД не прошла: ${(e as Error).message}`);
      return 'fail';
    }
  }

  private async checkRedis(): Promise<'ok' | 'fail'> {
    // Отдельное короткоживущее подключение: так проверка не зависит от
    // состояния пула BullMQ и не оставляет за собой висящих реконнектов.
    const client = new Redis(env.REDIS_URL, {
      lazyConnect: true,
      connectTimeout: CHECK_TIMEOUT_MS,
      maxRetriesPerRequest: 1,
      // null — не переподключаться: проверка одноразовая.
      retryStrategy: () => null,
    });
    // ioredis эмитит 'error'; без слушателя запоздавшее событие стало бы
    // необработанным исключением и уронило бы процесс.
    client.on('error', () => undefined);
    try {
      await withTimeout(client.connect().then(() => client.ping()), CHECK_TIMEOUT_MS);
      return 'ok';
    } catch (e) {
      this.logger.warn(`Проверка Redis не прошла: ${(e as Error).message}`);
      return 'fail';
    } finally {
      client.disconnect();
      client.removeAllListeners();
    }
  }
}

function withTimeout<T>(promise: PromiseLike<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`таймаут ${ms} мс`)), ms);
    Promise.resolve(promise).then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
