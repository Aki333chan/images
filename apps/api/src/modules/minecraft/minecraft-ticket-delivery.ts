import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { TicketDeliveryRegistry, TicketReplyDelivery } from '../../tickets/ticket-delivery.registry';
import { MinecraftService } from './minecraft.service';
import { isValidNickname, sanitizeCommandArgument } from './minecraft-parsers';

/**
 * Доставляет ответ модератора игроку прямо в игру командой `tell`.
 *
 * Это best-effort: если игрок оффлайн, сервер ответит «No player was found» —
 * ответ всё равно останется в панели и игрок увидит его, когда снова напишет
 * /ticket. Ошибки RCON здесь не должны всплывать наружу.
 */
@Injectable()
export class MinecraftTicketDelivery implements TicketReplyDelivery, OnModuleInit {
  private readonly logger = new Logger(MinecraftTicketDelivery.name);

  constructor(
    private readonly registry: TicketDeliveryRegistry,
    private readonly minecraft: MinecraftService,
  ) {}

  onModuleInit(): void {
    this.registry.register('minecraft', this);
  }

  async deliver(input: {
    serverId: string;
    playerName: string;
    text: string;
  }): Promise<void> {
    if (!isValidNickname(input.playerName)) {
      this.logger.warn(`Ник «${input.playerName}» не подходит для tell — доставка пропущена`);
      return;
    }
    const message = sanitizeCommandArgument(`[Панель] ${input.text}`, 240);
    await this.minecraft.runCommand(input.serverId, `tell ${input.playerName} ${message}`);
  }
}
