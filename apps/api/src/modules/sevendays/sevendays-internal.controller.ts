import { Body, Controller, Param, Post, UseGuards } from '@nestjs/common';
import {
  IsArray,
  IsISO8601,
  IsNumber,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import { Public } from '../../auth/decorators';
import { TicketsService } from '../../tickets/tickets.service';
import { SevenDaysCompanionGuard } from './sevendays-companion.guard';
import { SevenDaysEventsService } from './sevendays-events.service';

/**
 * Идентификатор игрока в 7 Days to Die — Steam_… / EOS_… , а не UUID.
 *
 * Отдельный DTO, а не общий с Minecraft, ровно поэтому: там @IsUUID, и
 * идентификатор платформы он бы отверг. Ядро тикетов при этом общее — оно
 * хранит идентификатор строкой и про игру ничего не знает.
 */
class PlayerIdMixin {
  @IsString()
  @MinLength(1)
  @MaxLength(128)
  playerId!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(64)
  playerName!: string;
}

class ModTicketDto extends PlayerIdMixin {
  @IsString()
  @MinLength(1)
  @MaxLength(500)
  text!: string;
}

class ModReportDto extends PlayerIdMixin {
  @IsString()
  @MinLength(1)
  @MaxLength(64)
  accusedName!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(500)
  reason!: string;

  @IsOptional() @IsNumber() x?: number | null;
  @IsOptional() @IsNumber() y?: number | null;
  @IsOptional() @IsNumber() z?: number | null;
}

class ModEventDto {
  @IsString() @MaxLength(32) kind!: string;
  @IsString() @MaxLength(128) playerId!: string;
  @IsString() @MaxLength(64) playerName!: string;
  @IsISO8601() occurredAt!: string;

  @IsOptional() @IsString() @MaxLength(500) text?: string | null;
  @IsOptional() @IsString() @MaxLength(128) actorId?: string | null;
  @IsOptional() @IsString() @MaxLength(64) actorName?: string | null;
  @IsOptional() @IsNumber() x?: number | null;
  @IsOptional() @IsNumber() y?: number | null;
  @IsOptional() @IsNumber() z?: number | null;
}

class ModEventBatchDto {
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => ModEventDto)
  events!: ModEventDto[];
}

/**
 * Обратное направление: companion-мод → панель.
 *
 * Роуты публичные для JWT (у мода нет пользователя), но закрыты токеном
 * сервера и проверкой приватной сети. Наружу через nginx их публиковать не
 * нужно — мод ходит на внутренний адрес панели.
 */
@Controller('internal/sevendays/servers/:serverId')
export class SevenDaysInternalController {
  constructor(
    private readonly tickets: TicketsService,
    private readonly events: SevenDaysEventsService,
  ) {}

  /** Игрок написал /ticket в игре. */
  @Public()
  @UseGuards(SevenDaysCompanionGuard)
  @Post('tickets')
  async createTicket(@Param('serverId') serverId: string, @Body() dto: ModTicketDto) {
    const before = await this.tickets.findOpenTicket(serverId, dto.playerId);
    const ticket = await this.tickets.createOrAppendTicket(
      serverId,
      dto.playerId,
      dto.playerName,
      dto.text,
    );
    // created нужен моду, чтобы сказать игроку «отправлено» или «дописано».
    return { ticketId: ticket.id, created: before === null };
  }

  /**
   * Игрок пожаловался на другого игрока.
   *
   * Жалоба заводится тем же тикетом, а не отдельной сущностью: по сути это
   * сообщение игрока администрации, просто про третьего. Отдельная сущность
   * потребовала бы своей ленты, своих ответов и своего закрытия — то есть
   * второй тикетной системы рядом с существующей.
   *
   * Ник обвиняемого и место складываются в текст: разбирающему нужно именно
   * это, а искать их в отдельных полях он всё равно не станет.
   */
  @Public()
  @UseGuards(SevenDaysCompanionGuard)
  @Post('reports')
  async createReport(@Param('serverId') serverId: string, @Body() dto: ModReportDto) {
    const where =
      typeof dto.x === 'number' && typeof dto.y === 'number' && typeof dto.z === 'number'
        ? ` (место: ${Math.round(dto.x)}, ${Math.round(dto.y)}, ${Math.round(dto.z)})`
        : '';
    const text = `Жалоба на ${dto.accusedName}: ${dto.reason}${where}`;

    const before = await this.tickets.findOpenTicket(serverId, dto.playerId);
    const ticket = await this.tickets.createOrAppendTicket(
      serverId,
      dto.playerId,
      dto.playerName,
      text.slice(0, 500),
    );
    return { ticketId: ticket.id, created: before === null };
  }

  /** Пачка событий игры: чат, входы, смерти, PvP. */
  @Public()
  @UseGuards(SevenDaysCompanionGuard)
  @Post('events')
  async ingestEvents(@Param('serverId') serverId: string, @Body() dto: ModEventBatchDto) {
    return this.events.ingest(serverId, dto.events);
  }
}
