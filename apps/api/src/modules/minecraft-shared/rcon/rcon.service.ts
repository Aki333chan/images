import { Injectable, Logger, OnModuleDestroy, ServiceUnavailableException } from '@nestjs/common';
import { RconConnection, RconAuthError, RconConnectionOptions } from './rcon-connection';

interface PooledConnection {
  connection: RconConnection;
  /** Хвост очереди: команды на одно соединение идут строго последовательно. */
  queue: Promise<unknown>;
  idleTimer: NodeJS.Timeout | null;
}

/** Закрывать простаивающее соединение, чтобы не держать сокет через туннель. */
const IDLE_TIMEOUT_MS = 5 * 60 * 1000;

@Injectable()
export class RconService implements OnModuleDestroy {
  private readonly logger = new Logger(RconService.name);
  private readonly pool = new Map<string, PooledConnection>();

  /**
   * Выполняет команду на сервере. Соединения переиспользуются и создаются
   * лениво; команды на один сервер сериализуются очередью.
   *
   * При обрыве соединения делается одна прозрачная попытка переподключиться
   * и повторить команду — кроме случая неверного пароля, там повтор бессмыслен.
   */
  async execute(serverId: string, options: RconConnectionOptions, command: string): Promise<string> {
    const entry = this.getOrCreate(serverId, options);

    const run = entry.queue.then(
      () => this.runWithReconnect(serverId, options, command),
      () => this.runWithReconnect(serverId, options, command),
    );
    // Хвост очереди не должен «падать» — ошибку получает вызывающий.
    entry.queue = run.catch(() => undefined);
    return run;
  }

  private async runWithReconnect(
    serverId: string,
    options: RconConnectionOptions,
    command: string,
  ): Promise<string> {
    try {
      return await this.runOnce(serverId, options, command);
    } catch (error) {
      if (error instanceof RconAuthError) throw this.toHttpError(error);
      this.logger.warn(
        `RCON ${options.host}:${options.port}: ${(error as Error).message}; переподключаюсь`,
      );
      this.drop(serverId);
      try {
        return await this.runOnce(serverId, options, command);
      } catch (retryError) {
        throw this.toHttpError(retryError as Error);
      }
    }
  }

  private async runOnce(
    serverId: string,
    options: RconConnectionOptions,
    command: string,
  ): Promise<string> {
    const entry = this.getOrCreate(serverId, options);
    if (!entry.connection.connected) {
      await entry.connection.connect();
      this.logger.log(`RCON подключён: ${entry.connection.address}`);
    }
    const result = await entry.connection.send(command);
    this.touch(serverId);
    return result;
  }

  private getOrCreate(serverId: string, options: RconConnectionOptions): PooledConnection {
    const existing = this.pool.get(serverId);
    if (existing) return existing;
    const entry: PooledConnection = {
      connection: new RconConnection(options),
      queue: Promise.resolve(),
      idleTimer: null,
    };
    this.pool.set(serverId, entry);
    this.touch(serverId);
    return entry;
  }

  private touch(serverId: string): void {
    const entry = this.pool.get(serverId);
    if (!entry) return;
    if (entry.idleTimer) clearTimeout(entry.idleTimer);
    entry.idleTimer = setTimeout(() => this.drop(serverId), IDLE_TIMEOUT_MS);
    entry.idleTimer.unref();
  }

  /** Закрывает и убирает соединение из пула (обрыв, смена настроек, простой). */
  drop(serverId: string): void {
    const entry = this.pool.get(serverId);
    if (!entry) return;
    if (entry.idleTimer) clearTimeout(entry.idleTimer);
    entry.connection.close();
    this.pool.delete(serverId);
  }

  /** Ошибки RCON наружу отдаём как 503: игровой сервер недоступен, но панель жива. */
  private toHttpError(error: Error): Error {
    if (error instanceof RconAuthError) {
      return new ServiceUnavailableException(
        'RCON отверг пароль — проверьте настройки подключения модуля',
      );
    }
    return new ServiceUnavailableException(`Игровой сервер недоступен по RCON: ${error.message}`);
  }

  onModuleDestroy(): void {
    for (const serverId of [...this.pool.keys()]) this.drop(serverId);
  }
}
