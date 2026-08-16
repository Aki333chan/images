import { Module } from '@nestjs/common';
import { ActivityService } from './activity.service';

/**
 * Отдельный модуль ради одного сервиса — намеренно.
 *
 * Историю онлайна пишет игровой модуль, а читает ядро, поэтому ActivityService
 * нужен обоим. Импортировать ради него ServersModule нельзя: тот тянет за
 * собой ServersController, а через него — PermissionsService, который сам
 * импортирует реестр игровых модулей. Получается кольцо
 *   permissions.service -> module-registry -> minecraft.def -> minecraft.module
 *   -> servers.module -> servers.controller -> permissions.service
 * В CommonJS такое кольцо не падает, а возвращает undefined на полпути, и Nest
 * сообщает про «dependency at index [1]» со знаком вопроса вместо имени класса.
 * Сборка и юнит-тесты этого не видят — ломается только запуск.
 *
 * Здесь контроллеров нет и импортов, ведущих обратно в rbac, тоже — модуль
 * заведомо листовой, и подключать его безопасно откуда угодно.
 */
@Module({
  providers: [ActivityService],
  exports: [ActivityService],
})
export class ActivityModule {}
