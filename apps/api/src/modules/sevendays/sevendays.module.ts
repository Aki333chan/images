import { Module } from '@nestjs/common';
import { SevenDaysConfigService } from './sevendays-config.service';
import { SevenDaysConsoleService } from './sevendays-console.service';
import { SevenDaysController } from './sevendays.controller';
import { SevenDaysService } from './sevendays.service';

/**
 * Модуль 7 Days to Die.
 *
 * Зависимостей на другие игровые модули нет и быть не должно: общие вещи
 * (шифрование кредов, Prisma, RBAC, аудит) берутся из ядра. Своих таблиц и
 * миграций у модуля нет — списки банов и белого списка ведёт сам игровой
 * сервер. Кронов и очередей тоже нет: временные баны сервер отсчитывает
 * сам, а замеры онлайна снимает ядро.
 */
@Module({
  controllers: [SevenDaysController],
  providers: [SevenDaysConfigService, SevenDaysConsoleService, SevenDaysService],
})
export class SevenDaysModule {}
