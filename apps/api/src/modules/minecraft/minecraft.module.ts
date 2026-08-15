import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { BanExpiryProcessor, BanExpiryScheduler, BAN_EXPIRY_QUEUE } from './ban-expiry.processor';
import { CompanionService } from './companion.service';
import { MinecraftConfigService } from './minecraft-config.service';
import { MinecraftController } from './minecraft.controller';
import { MinecraftService } from './minecraft.service';
import { RconService } from './rcon/rcon.service';

@Module({
  imports: [BullModule.registerQueue({ name: BAN_EXPIRY_QUEUE })],
  controllers: [MinecraftController],
  providers: [
    RconService,
    MinecraftConfigService,
    CompanionService,
    MinecraftService,
    BanExpiryScheduler,
    BanExpiryProcessor,
  ],
})
export class MinecraftModule {}
