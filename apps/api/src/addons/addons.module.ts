import { Module } from '@nestjs/common';
import { AuditModule } from '../audit/audit.module';
import { PrismaModule } from '../prisma/prisma.module';
import { SettingsModule } from '../settings/settings.module';
import { MinecraftModule } from '../modules/minecraft/minecraft.module';
import { AddonsController } from './addons.controller';
import { AddonsService } from './addons.service';

/**
 * Установка наших собственных плагинов.
 *
 * Зависит от модуля Minecraft ради PluginFilesService: скачивание, проверка
 * и запись jar — ровно те же, что у маркета, и второй такой путь означал бы
 * второй набор проверок, который рано или поздно разойдётся с первым.
 */
@Module({
  imports: [PrismaModule, SettingsModule, AuditModule, MinecraftModule],
  controllers: [AddonsController],
  providers: [AddonsService],
  exports: [AddonsService],
})
export class AddonsModule {}
