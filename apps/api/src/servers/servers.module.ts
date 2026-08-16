import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { ServersController } from './servers.controller';
import { ServersService } from './servers.service';
import { ActivityService } from './activity.service';
import { ServersSyncProcessor, ServersSyncScheduler, SYNC_QUEUE } from './servers-sync.processor';

@Module({
  imports: [BullModule.registerQueue({ name: SYNC_QUEUE })],
  controllers: [ServersController],
  providers: [ServersService, ActivityService, ServersSyncProcessor, ServersSyncScheduler],
  exports: [ServersService, ActivityService],
})
export class ServersModule {}
