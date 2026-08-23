import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { ServersController } from './servers.controller';
import { ServersService } from './servers.service';
import { PteroFilesController } from './ptero/ptero-files.controller';
import { PteroFilesService } from './ptero/ptero-files.service';
import { PteroOpsController } from './ptero/ptero-ops.controller';
import { PteroOpsService } from './ptero/ptero-ops.service';
import { PteroSettingsController } from './ptero/ptero-settings.controller';
import { PteroSettingsService } from './ptero/ptero-settings.service';
import { ActivityModule } from './activity.module';
import { ServersSyncProcessor, ServersSyncScheduler, SYNC_QUEUE } from './servers-sync.processor';

@Module({
  imports: [BullModule.registerQueue({ name: SYNC_QUEUE }), ActivityModule],
  controllers: [
    ServersController,
    // Общие возможности Pterodactyl. Отдельными контроллерами, а не одним
    // распухшим: у файлов, настроек и операций разные права и разный ритм
    // изменений.
    PteroFilesController,
    PteroSettingsController,
    PteroOpsController,
  ],
  providers: [
    ServersService,
    ServersSyncProcessor,
    ServersSyncScheduler,
    PteroFilesService,
    PteroSettingsService,
    PteroOpsService,
  ],
  exports: [ServersService, ActivityModule],
})
export class ServersModule {}
