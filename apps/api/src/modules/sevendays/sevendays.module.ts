import { Module } from '@nestjs/common';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysConfigService } from './sevendays-config.service';
import { SevenDaysConsoleService } from './sevendays-console.service';
import { SevenDaysController } from './sevendays.controller';
import { SevenDaysEventsService } from './sevendays-events.service';
import { SevenDaysInternalController } from './sevendays-internal.controller';
import { SevenDaysService } from './sevendays.service';
import { SevenDaysTicketDelivery } from './sevendays-ticket-delivery';
import { ServerMetricsModule } from '../../servers/metrics/server-metrics.module';
import { SevenDaysPlayerCount } from './sevendays-player-count';

/**
 * Модуль 7 Days to Die.
 *
 * Зависимостей на другие игровые модули нет и быть не должно: общие вещи
 * (шифрование кредов, Prisma, RBAC, аудит, тикеты) берутся из ядра —
 * TicketsModule объявлен @Global, поэтому импортировать его сюда не нужно
 * и вредно: повторный импорт создал бы второй его экземпляр. Списки
 * банов и белого списка модуль не хранит — их ведёт сам игровой сервер.
 * Кронов и очередей тоже нет: временные баны сервер отсчитывает сам, а
 * замеры онлайна снимает ядро.
 *
 * Своя таблица одна — журнал событий: события игра нигде не хранит, они
 * происходят и исчезают, а разбирают их задним числом.
 *
 * Companion-мод не обязателен. Без него работает всё, кроме обращений
 * игроков из игры, ответов им в чат и достоверного состояния мира.
 */
@Module({
  imports: [ServerMetricsModule],
  controllers: [SevenDaysController, SevenDaysInternalController],
  providers: [SevenDaysPlayerCount, 
    SevenDaysConfigService,
    SevenDaysConsoleService,
    SevenDaysService,
    SevenDaysCompanionService,
    SevenDaysEventsService,
    SevenDaysTicketDelivery,
  ],
})
export class SevenDaysModule {}
