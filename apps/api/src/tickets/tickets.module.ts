import { Global, Module } from '@nestjs/common';
import { TicketsController } from './tickets.controller';
import { TicketsService } from './tickets.service';
import { TicketDeliveryRegistry } from './ticket-delivery.registry';

/** Глобальный модуль: TicketsService доступен любому игровому модулю. */
@Global()
@Module({
  controllers: [TicketsController],
  providers: [TicketsService, TicketDeliveryRegistry],
  exports: [TicketsService, TicketDeliveryRegistry],
})
export class TicketsModule {}
