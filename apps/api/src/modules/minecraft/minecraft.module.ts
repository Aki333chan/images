import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
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
  imports: [BullModule.registerQueue({ name: BAN_EXPIRY_QUEUE })],
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
  ],
})
export class MinecraftModule {}
