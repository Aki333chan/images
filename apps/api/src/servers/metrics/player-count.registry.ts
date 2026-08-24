import { Injectable, Logger } from '@nestjs/common';

/**
 * Сколько игроков сейчас на сервере.
 *
 * Ядро не знает, как считать игроков в конкретной игре: где-то это RCON-команда
 * `list`, где-то REST API, где-то telnet. Поэтому модули регистрируют здесь
 * свою реализацию по id модуля — ровно тем же приёмом, что и доставка ответов
 * на тикеты (TicketDeliveryRegistry).
 *
 * Зависимость односторонняя: ядро про игры не знает, модули про ядро — знают.
 */
export interface PlayerCountProvider {
  count(serverId: string): Promise<{ online: number; max: number | null }>;
}

@Injectable()
export class PlayerCountRegistry {
  private readonly logger = new Logger(PlayerCountRegistry.name);
  private readonly providers = new Map<string, PlayerCountProvider>();

  register(moduleId: string, provider: PlayerCountProvider): void {
    this.providers.set(moduleId, provider);
    this.logger.log(`Счётчик игроков подключён для модуля ${moduleId}`);
  }

  /**
   * Best-effort: сервер может быть выключен, RCON не настроен, сеть моргнуть.
   * Возвращаем null вместо нуля — «не знаем» и «никого нет» это разные вещи,
   * и рисовать «0 игроков» у сервера, до которого не достучались, было бы
   * враньём в самом заметном месте списка.
   */
  async count(
    moduleId: string | null,
    serverId: string,
  ): Promise<{ online: number; max: number | null } | null> {
    if (!moduleId) return null;
    const provider = this.providers.get(moduleId);
    if (!provider) return null;
    try {
      return await provider.count(serverId);
    } catch (e) {
      this.logger.debug(`Счётчик игроков для ${serverId} промолчал: ${(e as Error).message}`);
      return null;
    }
  }
}
