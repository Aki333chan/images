import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Queue } from 'bullmq';
import { PrismaService } from '../../prisma/prisma.service';
import { MinecraftService } from './minecraft.service';

export const BAN_EXPIRY_QUEUE = 'minecraft-ban-expiry';

/**
 * У ванильного Minecraft нет временных банов, поэтому срок хранится у нас,
 * а снятие делает крон: раз в минуту ищет истёкшие активные баны и шлёт pardon.
 */
@Injectable()
export class BanExpiryScheduler implements OnModuleInit {
  constructor(@InjectQueue(BAN_EXPIRY_QUEUE) private readonly queue: Queue) {}

  async onModuleInit() {
    await this.queue.upsertJobScheduler('minecraft-ban-expiry-every-1m', { every: 60_000 });
  }
}

@Processor(BAN_EXPIRY_QUEUE)
export class BanExpiryProcessor extends WorkerHost {
  private readonly logger = new Logger(BanExpiryProcessor.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly minecraft: MinecraftService,
  ) {
    super();
  }

  async process(): Promise<void> {
    const expired = await this.prisma.minecraftBan.findMany({
      where: { pardonedAt: null, expiresAt: { not: null, lte: new Date() } },
      take: 50,
    });
    for (const ban of expired) {
      try {
        await this.minecraft.runCommand(ban.serverId, `pardon ${ban.playerName}`);
        await this.prisma.minecraftBan.update({
          where: { id: ban.id },
          // pardonedById = null — снял не человек, а автоматика по сроку.
          data: { pardonedAt: new Date() },
        });
        this.logger.log(`Истёк бан ${ban.playerName} на сервере ${ban.serverId} — снят`);
      } catch (e) {
        // Сервер мог быть выключен — попробуем на следующей минуте.
        this.logger.warn(
          `Не удалось снять истёкший бан ${ban.playerName}: ${(e as Error).message}`,
        );
      }
    }
  }
}
