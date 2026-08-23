import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { TicketDeliveryRegistry, TicketReplyDelivery } from '../../tickets/ticket-delivery.registry';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysConfigService } from './sevendays-config.service';

/** Префикс, по которому игрок узнаёт сообщение от администрации. */
const PREFIX = '[Поддержка] ';

/** Больше в игровой чат 7 Days to Die всё равно не влезает читаемо. */
const MAX_LENGTH = 240;

/**
 * Доставляет ответ модератора игроку прямо в игру.
 *
 * ПОЧЕМУ ЭТО ВООБЩЕ ТРЕБУЕТ МОДА. У ванильной консоли 7 Days to Die нет
 * команды «написать одному игроку» — есть только say на весь сервер. Без
 * мода ответ на жалобу пришлось бы зачитывать всем, включая того, на кого
 * жалуются. Поэтому доставка есть ровно тогда, когда есть мод.
 *
 * Всё best-effort: игрок может быть оффлайн, мод — не отвечать. Ответ в
 * любом случае остаётся в панели, и игрок увидит его, когда снова напишет
 * /ticket. Ошибки наружу не всплывают — их гасит реестр доставки.
 */
@Injectable()
export class SevenDaysTicketDelivery implements TicketReplyDelivery, OnModuleInit {
  private readonly logger = new Logger(SevenDaysTicketDelivery.name);

  constructor(
    private readonly registry: TicketDeliveryRegistry,
    private readonly companion: SevenDaysCompanionService,
    private readonly config: SevenDaysConfigService,
  ) {}

  onModuleInit(): void {
    this.registry.register('sevendays', this);
  }

  async deliver(input: { serverId: string; playerUuid: string; text: string }): Promise<void> {
    if (!(await this.config.hasCompanion(input.serverId))) {
      // Не ошибка, а известное ограничение голого сервера: сказать игроку
      // лично нечем. Ответ остался в панели, и это единственное, что панель
      // могла сделать.
      this.logger.debug('Companion-мод не настроен — ответ доставлен только в панель');
      return;
    }

    // playerUuid здесь — идентификатор платформы (Steam_… / EOS_…): ядро
    // тикетов хранит его строкой и про игру ничего не знает.
    const delivered = await this.companion.sendPrivateMessage(
      input.serverId,
      input.playerUuid,
      PREFIX + input.text.slice(0, MAX_LENGTH),
    );

    if (!delivered) {
      this.logger.debug('Игрок не в сети — ответ ждёт его в панели');
    }
  }
}
