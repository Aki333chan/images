import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { TicketDeliveryRegistry, TicketReplyDelivery } from '../../tickets/ticket-delivery.registry';
import { MinecraftService } from './minecraft.service';
import {
  escapeForJsonLiteral,
  isValidNickname,
  sanitizeCommandArgument,
} from './minecraft-parsers';

/** Префикс, по которому игрок узнаёт сообщение от администрации. */
const PREFIX = '[Поддержка] ';

/**
 * Доставляет ответ модератора игроку прямо в игру.
 *
 * ПОЧЕМУ tellraw, А НЕ tell. Обе команды приватны — сообщение видит только
 * адресат. Но tell отправляет голый текст, а tellraw принимает текстовый
 * компонент: можно выделить префикс цветом и жирным, чтобы ответ не
 * потерялся в потоке чата. Команда ванильная, работает на любом
 * Bukkit/Paper/Spigot без плагинов.
 *
 * ПОЧЕМУ ЕЩЁ И actionbar. Чат бывает забит, и сообщение уезжает вверх
 * прежде, чем его заметят. Короткая надпись над панелью быстрого доступа
 * держится пару секунд и не требует смотреть в чат. Сам ответ туда не
 * дублируется — только пометка, что он пришёл: в actionbar помещается
 * немного, а длинный текст обрежется на середине слова.
 *
 * Всё это best-effort: если игрок оффлайн, сервер ответит «No player was
 * found». Ответ всё равно останется в панели, и игрок увидит его, когда
 * снова напишет /ticket. Ошибки RCON наружу не всплывают.
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

  async deliver(input: { serverId: string; playerName: string; text: string }): Promise<void> {
    if (!isValidNickname(input.playerName)) {
      this.logger.warn(`Ник «${input.playerName}» не подходит для доставки — пропущено`);
      return;
    }

    const text = escapeForJsonLiteral(sanitizeCommandArgument(input.text, 240));
    const player = input.playerName;

    await this.minecraft.runCommand(input.serverId, buildTellraw(player, text));

    // Отдельной командой и после основной: если actionbar не поддержан
    // (сильно старый сервер), сам ответ игрок всё равно уже получил.
    await this.minecraft
      .runCommand(input.serverId, buildActionbar(player))
      .catch((e: Error) => this.logger.debug(`actionbar не отправлен: ${e.message}`));
  }
}

/**
 * Приватное сообщение с оформленным префиксом.
 *
 * `"bold":false` у вложенного текста обязателен: компоненты в `extra`
 * наследуют форматирование родителя, и без явного сброса жирным стал бы
 * весь ответ, а не только префикс.
 */
export function buildTellraw(player: string, escapedText: string): string {
  return (
    `tellraw ${player} {"text":"${PREFIX}","color":"gold","bold":true,` +
    `"extra":[{"text":"${escapedText}","color":"white","bold":false}]}`
  );
}

/** Короткая надпись поверх экрана — чтобы ответ заметили при активном чате. */
export function buildActionbar(player: string): string {
  return `title ${player} actionbar {"text":"${PREFIX}ответ в чате","color":"gold"}`;
}
