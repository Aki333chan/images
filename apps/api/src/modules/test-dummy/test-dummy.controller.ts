import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { IsString, IsUUID, MinLength } from 'class-validator';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { TicketsService } from '../../tickets/tickets.service';

class FakeTicketDto {
  @IsUUID()
  playerUuid!: string;

  @IsString()
  @MinLength(1)
  playerName!: string;

  @IsString()
  @MinLength(1)
  text!: string;
}

/**
 * Стенд для проверки механизмов ядра. Роуты модуля живут под
 * /api/modules/test-dummy/... и исчезают при выключении модуля в modules.config.ts.
 */
@Controller('modules/test-dummy/servers/:serverId')
export class TestDummyController {
  constructor(private readonly tickets: TicketsService) {}

  @Get('players')
  @RequirePermission('test-dummy.players')
  @ServerScoped('serverId')
  players(@Param('serverId') _serverId: string) {
    return {
      // UUID валидны по RFC 4122 (версия 4, вариант 8) — их принимает @IsUUID().
      players: [
        { uuid: '11111111-1111-4111-8111-111111111111', name: 'TestPlayerOne', online: true },
        { uuid: '22222222-2222-4222-8222-222222222222', name: 'TestPlayerTwo', online: false },
      ],
    };
  }

  @Get('console')
  @RequirePermission('test-dummy.console')
  @ServerScoped('serverId')
  console(@Param('serverId') serverId: string) {
    return { lines: [`[test-dummy] фейковая консоль сервера ${serverId}`, '[test-dummy] ok'] };
  }

  /** Демонстрация вызова core-сервиса тикетов из игрового модуля. */
  @Post('fake-ticket')
  @RequirePermission('tickets.respond')
  @ServerScoped('serverId')
  fakeTicket(@Param('serverId') serverId: string, @Body() dto: FakeTicketDto) {
    return this.tickets.createOrAppendTicket(serverId, dto.playerUuid, dto.playerName, dto.text);
  }
}
