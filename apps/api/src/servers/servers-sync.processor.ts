import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Queue } from 'bullmq';
import { ServersService } from './servers.service';

export const SYNC_QUEUE = 'ptero-sync';

/** Периодический синк зеркала серверов (BullMQ repeatable job, каждые 5 минут). */
@Injectable()
export class ServersSyncScheduler implements OnModuleInit {
  constructor(@InjectQueue(SYNC_QUEUE) private readonly queue: Queue) {}

  async onModuleInit() {
    await this.queue.upsertJobScheduler('ptero-sync-every-5m', { every: 5 * 60 * 1000 });
  }
}

@Processor(SYNC_QUEUE)
export class ServersSyncProcessor extends WorkerHost {
  private readonly logger = new Logger(ServersSyncProcessor.name);

  constructor(private readonly servers: ServersService) {
    super();
  }

  async process(): Promise<void> {
    try {
      await this.servers.syncFromPterodactyl();
    } catch (e) {
      // Panel может быть временно недоступен — не роняем воркер.
      this.logger.warn(`Синк Pterodactyl не удался: ${(e as Error).message}`);
    }
  }
}
