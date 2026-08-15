import { Body, Controller, Param, Post, UseGuards } from '@nestjs/common';
import { IsString, IsUUID, MaxLength, MinLength } from 'class-validator';
import { Public } from '../../auth/decorators';
import { TicketsService } from '../../tickets/tickets.service';
import { CompanionTokenGuard } from './companion-token.guard';

class PluginTicketDto {
  @IsUUID()
  playerUuid!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(32)
  playerName!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(500)
  text!: string;
}

/**
 * Обратное направление: companion-плагин → панель.
 *
 * Роут публичный для JWT (у плагина нет пользователя), но защищён токеном
 * сервера и проверкой приватной сети (CompanionTokenGuard). Наружу через
 * nginx его публиковать не нужно — плагин ходит на внутренний адрес панели.
 */
@Controller('internal/minecraft/servers/:serverId')
export class MinecraftInternalController {
  constructor(private readonly tickets: TicketsService) {}

  @Public()
  @UseGuards(CompanionTokenGuard)
  @Post('tickets')
  async createTicket(@Param('serverId') serverId: string, @Body() dto: PluginTicketDto) {
    const before = await this.tickets.findOpenTicket(serverId, dto.playerUuid);
    const ticket = await this.tickets.createOrAppendTicket(
      serverId,
      dto.playerUuid,
      dto.playerName,
      dto.text,
    );
    // created нужен плагину, чтобы показать игроку правильное подтверждение.
    return { ticketId: ticket.id, created: before === null };
  }
}
