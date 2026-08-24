import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { PlayerCountRegistry } from './player-count.registry';
import { ServerMetricsService } from './server-metrics.service';
import {
  METRICS_QUEUE,
  ServerMetricsProcessor,
  ServerMetricsScheduler,
} from './server-metrics.processor';

/**
 * Сбор нагрузки серверов, счётчик игроков и алерты о перегрузке.
 *
 * ЛИСТОВОЙ МОДУЛЬ БЕЗ КОНТРОЛЛЕРОВ — ровно по той же причине, что и
 * ActivityModule, и это не перестраховка. Реестр счётчика игроков нужен и
 * ядру (списку серверов), и игровым модулям, которые в него регистрируются.
 * Если бы он лежал в ServersModule, игровой модуль тянул бы за собой
 * ServersController, тот — PermissionsService, а тот — реестр игровых
 * модулей, и кольцо импортов замкнулось бы. В CommonJS такое кольцо не
 * падает, а возвращает undefined на полпути: сборка и юнит-тесты зелёные,
 * ломается только запуск.
 *
 * Всё, что нужно этому модулю снаружи (Prisma, Pterodactyl, настройки, почта,
 * аудит), объявлено @Global, поэтому импортов отсюда наружу нет вовсе.
 */
@Module({
  imports: [BullModule.registerQueue({ name: METRICS_QUEUE })],
  providers: [
    PlayerCountRegistry,
    ServerMetricsService,
    ServerMetricsScheduler,
    ServerMetricsProcessor,
  ],
  exports: [PlayerCountRegistry, ServerMetricsService],
})
export class ServerMetricsModule {}
