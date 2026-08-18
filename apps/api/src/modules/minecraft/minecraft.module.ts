import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { ActivityModule } from '../../servers/activity.module';
import {
  ActivitySamplerProcessor,
  ActivitySamplerScheduler,
  ACTIVITY_QUEUE,
} from './activity-sampler.processor';
import { BanExpiryProcessor, BanExpiryScheduler, BAN_EXPIRY_QUEUE } from './ban-expiry.processor';
import { CompanionService } from './companion.service';
import { CompanionTokenGuard } from './companion-token.guard';
import { MinecraftConfigService } from './minecraft-config.service';
import { MinecraftController } from './minecraft.controller';
import { MinecraftInternalController } from './minecraft-internal.controller';
import { MinecraftTicketDelivery } from './minecraft-ticket-delivery';
import { MinecraftService } from './minecraft.service';
import { RconService } from './rcon/rcon.service';

@Module({
  imports: [
    BullModule.registerQueue({ name: BAN_EXPIRY_QUEUE }),
    BullModule.registerQueue({ name: ACTIVITY_QUEUE }),
    // ActivityService живёт в ядре: график активности — свойство сервера,
    // а не игрового модуля. Модуль только поставляет замеры.
    // Именно ActivityModule, а не ServersModule: см. пояснение в activity.module.ts.
    ActivityModule,
  ],
  controllers: [MinecraftController, MinecraftInternalController],
  providers: [
    RconService,
    MinecraftConfigService,
    CompanionService,
    CompanionTokenGuard,
    MinecraftService,
    MinecraftTicketDelivery,
    BanExpiryScheduler,
    BanExpiryProcessor,
    ActivitySamplerScheduler,
    ActivitySamplerProcessor,
  ],
  // MinecraftService и CompanionService нужны AI-ассистенту: его инструменты —
  // тонкие обёртки над этими же сервисами, чтобы не заводить вторую реализацию
  // правил. Права и инвентарь панель зовёт прямо у CompanionService — ассистент
  // ходит туда же, а не через второй слой.
  // Зависимость односторонняя: модуль про ассистента не знает.
  exports: [MinecraftService, CompanionService],
})
export class MinecraftModule {}
