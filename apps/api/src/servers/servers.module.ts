import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { ServersController } from './servers.controller';
import { ServersService } from './servers.service';
import { ActivityModule } from './activity.module';
import { ServersSyncProcessor, ServersSyncScheduler, SYNC_QUEUE } from './servers-sync.processor';

@Module({
  imports: [BullModule.registerQueue({ name: SYNC_QUEUE }), ActivityModule],
  controllers: [ServersController],
  providers: [ServersService, ServersSyncProcessor, ServersSyncScheduler],
  exports: [ServersService, ActivityModule],
})
export class ServersModule {}
