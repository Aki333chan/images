import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { ServerMetricsModule } from '../../servers/metrics/server-metrics.module';
import { BanExpiryProcessor, BanExpiryScheduler, BAN_EXPIRY_QUEUE } from './ban-expiry.processor';
import { MinecraftPlayerCount } from './minecraft-player-count';
import { MinecraftConfigService } from './minecraft-config.service';
import { RconService } from './rcon/rcon.service';
import { VanillaRconService } from './vanilla-rcon.service';

/**
 * Общий слой всех модулей Minecraft: транспорт RCON, хранение кредов и
 * операции, которые умеет любой сервер игры независимо от ядра и загрузчика.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ МОДУЛЬ, А НЕ ПАПКА ВНУТРИ Paper. Модулей Minecraft три:
 * Paper, Forge и NeoForge. Если бы транспорт лежал внутри Paper, каждый из
 * остальных импортировал бы его из чужой папки — и в коде было бы написано,
 * что Forge зависит от Paper. Это неправда и вводит в заблуждение ровно там,
 * где важнее всего не запутаться: Forge и NeoForge к семейству Bukkit
 * отношения не имеют вовсе.
 *
 * Практическое следствие того же: сюда переехал крон снятия истёкших банов.
 * Раньше он жил в модуле Paper, и выключение Paper в modules.config.ts тихо
 * остановило бы снятие банов на серверах с модами.
 */
@Module({
  imports: [
    BullModule.registerQueue({ name: BAN_EXPIRY_QUEUE }),
    // Листовой модуль ядра: даёт реестр счётчика игроков. Обратной связи
    // из него сюда нет — см. пояснение в server-metrics.module.ts.
    ServerMetricsModule,
  ],
  providers: [
    RconService,
    MinecraftConfigService,
    VanillaRconService,
    MinecraftPlayerCount,
    BanExpiryScheduler,
    BanExpiryProcessor,
  ],
  exports: [RconService, MinecraftConfigService, VanillaRconService],
})
export class MinecraftSharedModule {}
