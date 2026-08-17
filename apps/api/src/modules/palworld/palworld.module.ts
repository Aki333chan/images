import { Module } from '@nestjs/common';
import { PalworldApiService } from './palworld-api.service';
import { PalworldConfigService } from './palworld-config.service';
import { PalworldController } from './palworld.controller';
import { PalworldService } from './palworld.service';

/**
 * Модуль Palworld.
 *
 * Зависимостей на другие игровые модули нет и быть не должно: общие вещи
 * (шифрование кредов, Prisma, RBAC, аудит) берутся из ядра. Очередей и
 * кронов у модуля тоже нет — временных банов Palworld не поддерживает,
 * а замеры онлайна снимает ядро.
 */
@Module({
  controllers: [PalworldController],
  providers: [PalworldConfigService, PalworldApiService, PalworldService],
})
export class PalworldModule {}
