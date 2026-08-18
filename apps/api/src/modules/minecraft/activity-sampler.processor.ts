import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Queue } from 'bullmq';
import { ActivityService } from '../../servers/activity.service';
import { PrismaService } from '../../prisma/prisma.service';
import { MinecraftService } from './minecraft.service';

export const ACTIVITY_QUEUE = 'mc-activity';

/** Замер онлайна раз в 5 минут: из них складывается график активности. */
@Injectable()
export class ActivitySamplerScheduler implements OnModuleInit {
  constructor(@InjectQueue(ACTIVITY_QUEUE) private readonly queue: Queue) {}

  async onModuleInit() {
    await this.queue.upsertJobScheduler('mc-activity-every-5m', { every: 5 * 60 * 1000 });
  }
}

@Processor(ACTIVITY_QUEUE)
export class ActivitySamplerProcessor extends WorkerHost {
  private readonly logger = new Logger(ActivitySamplerProcessor.name);
  /** Чтобы не чистить старое на каждом тике — раз в сутки достаточно. */
  private lastPrune = 0;

  constructor(
    private readonly prisma: PrismaService,
    private readonly minecraft: MinecraftService,
    private readonly activity: ActivityService,
  ) {
    super();
  }

  async process(): Promise<void> {
    const servers = await this.prisma.server.findMany({
      where: { moduleId: 'minecraft', status: 'active' },
      select: { id: true, name: true },
    });

    for (const server of servers) {
      try {
        const players = await this.minecraft.getPlayers(server.id);
        await this.activity.record(server.id, players.online);
      } catch (e) {
        // Сервер выключен, RCON не настроен, сеть моргнула — это нормальный
        // ход событий, а не сбой сборщика. Пропуск часа рисуется как «нет
        // данных», что честнее, чем записать ноль игроков.
        this.logger.debug(`Замер онлайна для «${server.name}» пропущен: ${(e as Error).message}`);
      }
    }

    const dayMs = 24 * 60 * 60 * 1000;
    if (Date.now() - this.lastPrune > dayMs) {
      this.lastPrune = Date.now();
      await this.activity
        .prune()
        .catch((e: Error) => this.logger.warn(`Чистка старых замеров не удалась: ${e.message}`));
    }
  }
}
