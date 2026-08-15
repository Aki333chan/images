import { Injectable, Logger } from '@nestjs/common';

/**
 * Доставка ответа модератора игроку в игру.
 *
 * Ядро не знает, как разговаривать с конкретной игрой, поэтому модули
 * регистрируют здесь свою реализацию по id модуля. Если для сервера модуль
 * не назначен или доставку не поддерживает — ответ просто остаётся в панели.
 */
export interface TicketReplyDelivery {
  deliver(input: {
    serverId: string;
    playerUuid: string;
    playerName: string;
    text: string;
  }): Promise<void>;
}

@Injectable()
export class TicketDeliveryRegistry {
  private readonly logger = new Logger(TicketDeliveryRegistry.name);
  private readonly handlers = new Map<string, TicketReplyDelivery>();

  register(moduleId: string, handler: TicketReplyDelivery): void {
    this.handlers.set(moduleId, handler);
    this.logger.log(`Доставка ответов на тикеты включена для модуля ${moduleId}`);
  }

  /**
   * Best-effort: игрок может быть оффлайн, а игровой сервер — недоступен.
   * Ошибка доставки не должна ломать сохранение ответа в панели.
   */
  async deliver(
    moduleId: string | null,
    input: { serverId: string; playerUuid: string; playerName: string; text: string },
  ): Promise<void> {
    if (!moduleId) return;
    const handler = this.handlers.get(moduleId);
    if (!handler) return;
    try {
      await handler.deliver(input);
    } catch (e) {
      this.logger.warn(
        `Не удалось доставить ответ игроку ${input.playerName}: ${(e as Error).message}`,
      );
    }
  }
}
