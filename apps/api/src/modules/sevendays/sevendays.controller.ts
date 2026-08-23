import {
  Body,
  Controller,
  Delete,
  ForbiddenException,
  Get,
  Param,
  Post,
  Put,
} from '@nestjs/common';
import {
  SEVENDAYS_PERMISSIONS,
  type SevenDaysBanDto,
  type SevenDaysConfigStatusDto,
  type SevenDaysPlayersResponse,
  type SevenDaysStateDto,
  type SevenDaysWhitelistEntryDto,
} from '@aurum/shared';
import { AuditRedactBody } from '../../audit/audit.decorators';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { SevenDaysConfigService } from './sevendays-config.service';
import { SevenDaysService } from './sevendays.service';
import { ActionRunDto, BanDto, KickDto, TelnetConfigDto, WhitelistEntryDto } from './dto';

/**
 * Роуты модуля 7 Days to Die. Как и в остальных модулях: каждый закрыт
 * правом модуля и @ServerScoped, права проверяются ядром по текущему
 * состоянию БД, а мутирующие запросы попадают в audit_log.
 *
 * Чего здесь НЕТ и почему:
 *   инвентарь — сервер не отдаёт содержимое рюкзака никак, и плагинов,
 *               которые бы его отдали, в этой игре не бывает;
 *   тикеты    — заводит их игрок командой в игре, а обратного канала из
 *               игры в панель у 7 Days to Die нет.
 */
@Controller('modules/sevendays/servers/:serverId')
export class SevenDaysController {
  constructor(
    private readonly sevendays: SevenDaysService,
    private readonly config: SevenDaysConfigService,
  ) {}

  // ---------- Игроки ----------

  @Get('players')
  @RequirePermission(SEVENDAYS_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  players(@Param('serverId') serverId: string): Promise<SevenDaysPlayersResponse> {
    return this.sevendays.getPlayers(serverId);
  }

  /** Игровой день, время суток, версия, онлайн. */
  @Get('state')
  @RequirePermission(SEVENDAYS_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  state(@Param('serverId') serverId: string): Promise<SevenDaysStateDto> {
    return this.sevendays.getState(serverId);
  }

  @Post('players/kick')
  @RequirePermission(SEVENDAYS_PERMISSIONS.kick)
  @ServerScoped('serverId')
  kick(@Param('serverId') serverId: string, @Body() dto: KickDto) {
    return this.sevendays.kick(serverId, dto.target, dto.reason ?? '');
  }

  @Post('players/ban')
  @RequirePermission(SEVENDAYS_PERMISSIONS.ban)
  @ServerScoped('serverId')
  ban(@Param('serverId') serverId: string, @Body() dto: BanDto) {
    return this.sevendays.ban(serverId, dto.target, dto.duration, dto.unit, dto.reason ?? '');
  }

  // ---------- Баны ----------

  /** Список ведёт сам игровой сервер — панель его только показывает. */
  @Get('bans')
  @RequirePermission(SEVENDAYS_PERMISSIONS.ban)
  @ServerScoped('serverId')
  listBans(@Param('serverId') serverId: string): Promise<SevenDaysBanDto[]> {
    return this.sevendays.listBans(serverId);
  }

  @Post('bans/pardon')
  @RequirePermission(SEVENDAYS_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  pardon(@Param('serverId') serverId: string, @Body() dto: WhitelistEntryDto) {
    return this.sevendays.pardon(serverId, dto.target);
  }

  // ---------- Белый список ----------

  @Get('whitelist')
  @RequirePermission(SEVENDAYS_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelist(@Param('serverId') serverId: string): Promise<SevenDaysWhitelistEntryDto[]> {
    return this.sevendays.listWhitelist(serverId);
  }

  @Post('whitelist')
  @RequirePermission(SEVENDAYS_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  addToWhitelist(@Param('serverId') serverId: string, @Body() dto: WhitelistEntryDto) {
    return this.sevendays.addToWhitelist(serverId, dto.target);
  }

  /**
   * Удаление идёт телом, а не путём: идентификатор платформы содержит
   * символы, которые в пути пришлось бы кодировать, и одна пропущенная
   * кодировка вычеркнула бы из списка не того игрока.
   */
  @Delete('whitelist')
  @RequirePermission(SEVENDAYS_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  removeFromWhitelist(@Param('serverId') serverId: string, @Body() dto: WhitelistEntryDto) {
    return this.sevendays.removeFromWhitelist(serverId, dto.target);
  }

  // ---------- Быстрые действия ----------

  @Get('actions')
  @RequirePermission(SEVENDAYS_PERMISSIONS.quickActions)
  @ServerScoped('serverId')
  actions() {
    return { actions: this.sevendays.listActions() };
  }

  /**
   * Обычные действия: объявление в чат, сохранение мира.
   *
   * Остановка сервера сюда НЕ попадает — у неё свой роут со своим правом.
   * Иначе модератор с правом на объявления выключил бы сервер, послав сюда
   * actionId остановки.
   */
  @Post('actions/:actionId')
  @RequirePermission(SEVENDAYS_PERMISSIONS.quickActions)
  @ServerScoped('serverId')
  runAction(
    @Param('serverId') serverId: string,
    @Param('actionId') actionId: string,
    @Body() dto: ActionRunDto,
  ) {
    const action = this.sevendays.findAction(actionId);
    if (action.permission !== SEVENDAYS_PERMISSIONS.quickActions) {
      throw new ForbiddenException(
        `Действие «${action.label}» выполняется отдельным роутом со своим правом`,
      );
    }
    return this.sevendays.runAction(serverId, actionId, dto.args ?? {});
  }

  /** Остановка сервера — отдельное право. */
  @Post('shutdown')
  @RequirePermission(SEVENDAYS_PERMISSIONS.shutdown)
  @ServerScoped('serverId')
  shutdown(@Param('serverId') serverId: string) {
    return this.sevendays.runAction(serverId, 'shutdown', {});
  }

  // ---------- Настройки подключения ----------

  /** Только флаги — ни адреса, ни порта, ни пароля наружу. */
  @Get('config')
  @RequirePermission(SEVENDAYS_PERMISSIONS.configure)
  @ServerScoped('serverId')
  async configStatus(@Param('serverId') serverId: string): Promise<SevenDaysConfigStatusDto> {
    const creds = await this.config.read(serverId);
    return {
      telnetConfigured: !!creds.sevendays,
      lastSeenAt: creds.sevendays?.lastSeenAt ?? null,
    };
  }

  @Put('config')
  @RequirePermission(SEVENDAYS_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес консоли и её пароль
  async setConfig(@Param('serverId') serverId: string, @Body() dto: TelnetConfigDto) {
    await this.config.setTelnet(serverId, dto.host ?? null, dto.port ?? null, dto.password ?? null);
    if (!dto.host) return { ok: true, configured: false };

    // Сразу проверяем связь, чтобы ошибка не всплыла позже у модератора.
    const state = await this.sevendays.getState(serverId);
    return {
      ok: true,
      configured: true,
      probe: state.available
        ? `сервер ответил${state.day ? `, идёт день ${state.day}` : ''}`
        : state.reason,
    };
  }
}
