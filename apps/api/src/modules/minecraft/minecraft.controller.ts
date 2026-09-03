import {
  Body,
  ConflictException,
  Controller,
  Delete,
  Get,
  NotFoundException,
  Param,
  ParseUUIDPipe,
  Post,
  Put,
  Query,
} from '@nestjs/common';
import {
  MINECRAFT_PERMISSIONS,
  type MinecraftBalanceChangeDto,
  type MinecraftBalanceDto,
  type MinecraftCommandResultDto,
  type MinecraftConfigStatusDto,
  type MinecraftConsoleCompletionDto,
  type MinecraftConsoleDictionaryDto,
  type MinecraftEconomyDto,
  type MinecraftGiveResponse,
  type MinecraftGuildBonusDto,
  type MinecraftGuildDto,
  type MinecraftGuildMembershipDto,
  type MinecraftPerformanceDto,
  type MinecraftPermissionsDto,
  type MinecraftPluginsDto,
  type MinecraftInventoryStatusDto,
  type MinecraftKnownPlayersResponse,
  type MinecraftPasswordResetDto,
  type MinecraftPlayerIpsResponse,
} from '@aurum/shared';
import { AuthUser, CurrentUser } from '../../auth/decorators';
import { PrismaService } from '../../prisma/prisma.service';
import { AuditRedactBody } from '../../audit/audit.decorators';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { COMPANION_DOCS_URL, CompanionService } from './companion.service';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import { MinecraftService } from './minecraft.service';
import {
  BalanceChangeDto,
  BanDto,
  CompanionConfigDto,
  GiveItemsDto,
  GuildBonusGrantDto,
  GuildRemoveMemberDto,
  GuildTransferDto,
  InventoryClearDto,
  KickDto,
  PermissionChangeDto,
  QuickCommandRunDto,
  RawCommandDto,
  RconConfigDto,
  WhitelistAddDto,
} from './dto';

/**
 * Роуты модуля Minecraft. Каждый защищён правом модуля и @ServerScoped —
 * доступ к конкретному серверу проверяется ядром по текущему состоянию БД.
 *
 * Мутирующие запросы автоматически попадают в audit_log через глобальный
 * AuditInterceptor ядра (пароли и токены в метаданных редактируются).
 */
@Controller('modules/minecraft/servers/:serverId')
export class MinecraftController {
  constructor(
    private readonly minecraft: MinecraftService,
    private readonly config: MinecraftConfigService,
    private readonly companion: CompanionService,
    private readonly prisma: PrismaService,
  ) {}

  // ---------- Игроки ----------

  @Get('players')
  @RequirePermission(MINECRAFT_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  players(@Param('serverId') serverId: string) {
    return this.minecraft.getPlayers(serverId);
  }

  /**
   * Все, кто когда-либо заходил на сервер.
   *
   * Постранично и с поиском по нику: список растёт вместе с возрастом
   * сервера, а игровой сервер читает его с диска. Право то же, что и на
   * список онлайна, — это те же игроки, просто ещё и вчерашние.
   */
  @Get('players/known')
  @RequirePermission(MINECRAFT_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  knownPlayers(
    @Param('serverId') serverId: string,
    @Query('query') query?: string,
    @Query('offset') offset?: string,
    @Query('limit') limit?: string,
  ): Promise<MinecraftKnownPlayersResponse> {
    return this.companion.getKnownPlayers(serverId, {
      query: query?.trim() || undefined,
      offset: positiveInt(offset),
      limit: positiveInt(limit),
    });
  }

  /**
   * Известные адреса игрока.
   *
   * Отдельное право, а не playersView: адрес — личные данные, и модератору
   * они для работы не нужны. По UUID, а не по нику: ник у офлайн-режима
   * меняется вместе с аккаунтом, а UUID — нет.
   */
  @Get('players/:uuid/ips')
  @RequirePermission(MINECRAFT_PERMISSIONS.playerIps)
  @ServerScoped('serverId')
  playerIps(
    @Param('serverId') serverId: string,
    @Param('uuid') uuid: string,
  ): Promise<MinecraftPlayerIpsResponse> {
    return this.companion.getIpHistory(serverId, uuid);
  }

  @Post('players/:name/kick')
  @RequirePermission(MINECRAFT_PERMISSIONS.kick)
  @ServerScoped('serverId')
  async kick(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: KickDto,
  ): Promise<MinecraftCommandResultDto> {
    return { output: await this.minecraft.kick(serverId, name, dto.reason) };
  }

  @Post('players/:name/ban')
  @RequirePermission(MINECRAFT_PERMISSIONS.ban)
  @ServerScoped('serverId')
  ban(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: BanDto,
  ) {
    return this.minecraft.ban(
      serverId,
      name,
      dto.reason,
      dto.expiresAt ? new Date(dto.expiresAt) : null,
      user.id,
    );
  }

  // ---------- Баны ----------

  @Get('bans')
  @RequirePermission(MINECRAFT_PERMISSIONS.ban)
  @ServerScoped('serverId')
  listBans(@Param('serverId') serverId: string, @Query('search') search?: string) {
    return this.minecraft.listBans(serverId, search || undefined);
  }

  @Post('bans/:banId/pardon')
  @RequirePermission(MINECRAFT_PERMISSIONS.pardon)
  @ServerScoped('serverId')
  pardon(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('banId') banId: string,
  ) {
    return this.minecraft.pardon(serverId, banId, user.id);
  }

  // ---------- Whitelist ----------

  @Get('whitelist')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelist(@Param('serverId') serverId: string) {
    return this.minecraft.getWhitelist(serverId);
  }

  @Post('whitelist')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelistAdd(@Param('serverId') serverId: string, @Body() dto: WhitelistAddDto) {
    return this.minecraft.addToWhitelist(serverId, dto.name);
  }

  @Delete('whitelist/:name')
  @RequirePermission(MINECRAFT_PERMISSIONS.whitelist)
  @ServerScoped('serverId')
  whitelistRemove(@Param('serverId') serverId: string, @Param('name') name: string) {
    return this.minecraft.removeFromWhitelist(serverId, name);
  }

  // ---------- Команды ----------

  /** Произвольная RCON-команда: только ГМ и Админ (см. defaultRoles манифеста). */
  @Post('command')
  @RequirePermission(MINECRAFT_PERMISSIONS.commandRaw)
  @ServerScoped('serverId')
  async rawCommand(
    @Param('serverId') serverId: string,
    @Body() dto: RawCommandDto,
  ): Promise<MinecraftCommandResultDto> {
    return { output: await this.minecraft.runCommand(serverId, dto.command) };
  }

  @Get('quick-commands')
  @RequirePermission(MINECRAFT_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  async quickCommands(@Param('serverId') serverId: string) {
    // Список плагинов спрашиваем у сервера: действия чужих плагинов
    // показываются, только если те действительно установлены.
    const installed = await this.minecraft.installedPluginNames(serverId);
    return { commands: this.minecraft.listQuickCommands(installed) };
  }

  // ---------- Автодополнение в консоли ----------
  //
  // Оба роута под servers.power — тем же правом закрыт ввод команд в консоль.
  // Тому, кто не может отправить команду, дополнять нечего.

  /**
   * Словарь для базового уровня: панель забирает его один раз при открытии
   * консоли и дальше дополняет локально, без запроса на каждое нажатие Tab.
   */
  @Get('console/dictionary')
  @RequirePermission('servers.power')
  @ServerScoped('serverId')
  consoleDictionary(@Param('serverId') serverId: string): Promise<MinecraftConsoleDictionaryDto> {
    return this.minecraft.getConsoleDictionary(serverId);
  }

  /**
   * Продвинутый уровень: настоящее автодополнение от Bukkit через
   * companion-плагин. Без плагина отвечает available:false — панель тогда
   * остаётся на словаре.
   */
  @Get('console/complete')
  @RequirePermission('servers.power')
  @ServerScoped('serverId')
  consoleComplete(
    @Param('serverId') serverId: string,
    @Query('line') line?: string,
  ): Promise<MinecraftConsoleCompletionDto> {
    return this.minecraft.completeConsoleCommand(serverId, line ?? '');
  }

  /** Что из известного панели стоит на этом сервере. */
  @Get('plugins')
  @RequirePermission('servers.view')
  @ServerScoped('serverId')
  plugins(@Param('serverId') serverId: string): Promise<MinecraftPluginsDto> {
    return this.minecraft.getPlugins(serverId);
  }

  // ---------- Права игрока (LuckPerms) ----------

  @Get('players/:uuid/permissions')
  @RequirePermission(MINECRAFT_PERMISSIONS.permissionsView)
  @ServerScoped('serverId')
  permissions(
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
  ): Promise<MinecraftPermissionsDto> {
    return this.companion.getPermissions(serverId, uuid);
  }

  @Post('players/:uuid/permissions')
  @RequirePermission(MINECRAFT_PERMISSIONS.permissionsEdit)
  @ServerScoped('serverId')
  changePermission(
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
    @Body() dto: PermissionChangeDto,
  ): Promise<MinecraftPermissionsDto> {
    return this.companion.changePermission(serverId, uuid, dto);
  }

  // ---------- Валюта (Vault) ----------
  //
  // Просмотр и изменение разведены по разным правам: смотреть баланс полезно
  // и модератору, а начислять деньги — это раздача ценностей.

  @Get('players/:uuid/balance')
  @RequirePermission(MINECRAFT_PERMISSIONS.economyView)
  @ServerScoped('serverId')
  balance(
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
  ): Promise<MinecraftBalanceDto> {
    return this.minecraft.getBalance(serverId, uuid);
  }

  @Post('players/:uuid/balance/deposit')
  @RequirePermission(MINECRAFT_PERMISSIONS.economyEdit)
  @ServerScoped('serverId')
  deposit(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
    @Body() dto: BalanceChangeDto,
  ): Promise<MinecraftBalanceChangeDto> {
    return this.minecraft.changeBalance(
      serverId,
      uuid,
      'deposit',
      dto.amount,
      dto.reason ?? null,
      user.id,
    );
  }

  @Post('players/:uuid/balance/withdraw')
  @RequirePermission(MINECRAFT_PERMISSIONS.economyEdit)
  @ServerScoped('serverId')
  withdraw(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
    @Body() dto: BalanceChangeDto,
  ): Promise<MinecraftBalanceChangeDto> {
    return this.minecraft.changeBalance(
      serverId,
      uuid,
      'withdraw',
      dto.amount,
      dto.reason ?? null,
      user.id,
    );
  }

  /**
   * Экономика сервера целиком. refresh=1 — пересчитать, минуя кэш; обычное
   * открытие страницы кэш не сбрасывает, иначе смысл кэша теряется.
   */
  /**
   * Сброс пароля игрока: выдать одноразовый токен.
   *
   * Токен возвращается один раз и живёт двадцать минут. В журнал он не
   * попадает: аудит записывает адрес запроса и тело (там только ник), а тела
   * ответа не хранит — что и нужно, потому что токен это временный ключ к
   * чужому аккаунту.
   */
  @Post('players/:name/password-reset')
  @RequirePermission(MINECRAFT_PERMISSIONS.passwordReset)
  @ServerScoped('serverId')
  async resetPassword(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
  ): Promise<MinecraftPasswordResetDto> {
    const reset = await this.companion.resetPassword(serverId, name);
    if (!reset) {
      // 409, а не 404: запрос корректен, отказало состояние сервера — нет
      // плагина авторизации либо нет такого аккаунта. Различать эти случаи
      // в ответе не станем: это подсказка тому, кто перебирает ники.
      throw new ConflictException(
        'Сбросить пароль не удалось: проверьте, что на сервере установлен AurumAuth и такой игрок зарегистрирован',
      );
    }
    return reset;
  }

  // ------------------------------------------------------------- гильдии
  //
  // Раздел работает через companion, который спрашивает у AurumGuilds по его
  // Java API. Плагина может не быть — тогда список приходит пустым, а действия
  // отвечают 409 с объяснением. Отдельного «раздел выключен» в контракте нет
  // намеренно: пустой список гильдий и отсутствие плагина выглядят для
  // человека одинаково, а лишний флаг пришлось бы поддерживать во всех трёх
  // слоях.

  @Get('guilds')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsView)
  @ServerScoped('serverId')
  async guilds(
    @Param('serverId') serverId: string,
    @Query('query') query?: string,
  ): Promise<MinecraftGuildDto[]> {
    return (await this.companion.getGuilds(serverId, query?.trim() || null)) ?? [];
  }

  @Get('guilds/:guildId')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsView)
  @ServerScoped('serverId')
  async guild(
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
  ): Promise<MinecraftGuildDto> {
    const guild = await this.companion.getGuild(serverId, Number(guildId));
    if (!guild) {
      throw new NotFoundException('Гильдия не найдена или плагин гильдий недоступен');
    }
    return guild;
  }

  /**
   * Гильдия игрока — для его карточки.
   *
   * null и когда игрок ни в какой гильдии не состоит, и когда плагина гильдий
   * нет: для строки в карточке разницы нет, показывать всё равно нечего.
   * Отдельный 404 здесь был бы ошибкой у каждого второго игрока.
   */
  @Get('players/:uuid/guild')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsView)
  @ServerScoped('serverId')
  guildOfPlayer(
    @Param('serverId') serverId: string,
    @Param('uuid', ParseUUIDPipe) uuid: string,
  ): Promise<MinecraftGuildMembershipDto | null> {
    return this.companion.getPlayerGuild(serverId, uuid);
  }

  /**
   * Распустить гильдию помимо воли лидера.
   *
   * Необратимо и уносит состав вместе с общаком, поэтому право отдельное от
   * просмотра. Кто это сделал, уходит и в журнал панели (глобальный
   * AuditInterceptor), и в лог игрового сервера — там это видно тем, кто
   * разбирается уже на месте.
   */
  /**
   * Бонусы гильдии. Право просмотра гильдий, а не управления: видеть, что
   * действует, полезно и модератору — вопрос «почему у них столько дропа»
   * возникает именно у него.
   */
  @Get('guilds/:guildId/bonuses')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsView)
  @ServerScoped('serverId')
  async guildBonuses(
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
  ): Promise<MinecraftGuildBonusDto[]> {
    const bonuses = await this.companion.getGuildBonuses(serverId, Number(guildId));
    if (bonuses === null) {
      throw new NotFoundException('Плагин гильдий недоступен');
    }
    return bonuses;
  }

  /**
   * Выдать бонус гильдии.
   *
   * Право guildsManage: бонус — это выданное преимущество одной группы игроков
   * над другими, и раздавать его должен тот же, кто вправе распустить гильдию,
   * а не всякий, кто видит список. В журнал попадает автоматически общим
   * аудит-перехватчиком вместе с телом запроса — по нему потом и видно, кто
   * что выдал.
   */
  @Post('guilds/:guildId/bonuses')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsManage)
  @ServerScoped('serverId')
  async grantGuildBonus(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
    @Body() dto: GuildBonusGrantDto,
  ): Promise<MinecraftCommandResultDto> {
    const result = await this.companion.guildBonusAction(
      serverId,
      `/guilds/${Number(guildId)}/bonuses`,
      'POST',
      {
        type: dto.type,
        magnitude: dto.magnitude,
        seconds: dto.seconds ?? 0,
        actor: await this.actorName(user.id),
      },
    );
    return { output: result.message };
  }

  @Delete('guilds/:guildId/bonuses/:type')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsManage)
  @ServerScoped('serverId')
  async revokeGuildBonus(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
    @Param('type') type: string,
  ): Promise<MinecraftCommandResultDto> {
    const result = await this.companion.guildBonusAction(
      serverId,
      `/guilds/${Number(guildId)}/bonuses/${encodeURIComponent(type)}`,
      'DELETE',
      { actor: await this.actorName(user.id) },
    );
    return { output: result.message };
  }

  @Post('guilds/:guildId/disband')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsManage)
  @ServerScoped('serverId')
  async disbandGuild(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
  ): Promise<MinecraftCommandResultDto> {
    return this.guildAction(serverId, `/guilds/${Number(guildId)}/disband`, {
      actor: await this.actorName(user.id),
    });
  }

  @Post('guilds/:guildId/transfer')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsManage)
  @ServerScoped('serverId')
  async transferGuild(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('guildId') guildId: string,
    @Body() dto: GuildTransferDto,
  ): Promise<MinecraftCommandResultDto> {
    return this.guildAction(serverId, `/guilds/${Number(guildId)}/transfer`, {
      actor: await this.actorName(user.id),
      target: dto.target,
    });
  }

  @Post('guilds/members/remove')
  @RequirePermission(MINECRAFT_PERMISSIONS.guildsManage)
  @ServerScoped('serverId')
  async removeGuildMember(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: GuildRemoveMemberDto,
  ): Promise<MinecraftCommandResultDto> {
    return this.guildAction(serverId, `/guilds/members/${encodeURIComponent(dto.target)}/remove`, {
      actor: await this.actorName(user.id),
    });
  }

  /**
   * Общий хвост трёх действий с гильдиями.
   *
   * Отказ превращается в 409, а не в 200 с флагом: панель обязана показать
   * человеку, что действие не выполнено, и делать это по полю в теле успешного
   * ответа — верный способ однажды пропустить неудачу.
   */
  private async guildAction(
    serverId: string,
    path: string,
    body: Record<string, unknown>,
  ): Promise<MinecraftCommandResultDto> {
    const result = await this.companion.guildAction(serverId, path, body);
    if (!result.ok) throw new ConflictException(result.message);
    return { output: result.message };
  }

  /**
   * Как назвать сотрудника в логе ИГРОВОГО сервера.
   *
   * Ник, а не id: журнал панели и так знает, кто это, а строчку в консоли
   * сервера читает администратор, у которого панели под рукой может и не быть.
   * Пока ник не выбран (человек ещё не входил), честнее показать email, чем
   * пустоту — так же, как в журнале банов.
   */
  private async actorName(userId: string): Promise<string> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { nickname: true, email: true },
    });
    return user?.nickname ?? user?.email ?? 'панель';
  }

  @Get('economy')
  @RequirePermission(MINECRAFT_PERMISSIONS.economyView)
  @ServerScoped('serverId')
  economy(
    @Param('serverId') serverId: string,
    @Query('refresh') refresh?: string,
  ): Promise<MinecraftEconomyDto> {
    return this.minecraft.getEconomy(serverId, { refresh: refresh === '1' || refresh === 'true' });
  }

  @Post('quick-commands/:commandId')
  @RequirePermission(MINECRAFT_PERMISSIONS.quickCommands)
  @ServerScoped('serverId')
  async runQuickCommand(
    @Param('serverId') serverId: string,
    @Param('commandId') commandId: string,
    @Body() dto: QuickCommandRunDto,
  ): Promise<MinecraftCommandResultDto> {
    return {
      output: await this.minecraft.runQuickCommand(serverId, commandId, dto.args ?? {}),
    };
  }

  // ---------- Инвентарь ----------

  /**
   * Доступен ли инвентарь на этом сервере. Отдельный роут нужен, чтобы вкладка
   * могла отличить «плагин не установлен» от «этот игрок офлайн», не спрашивая
   * инвентарь наугад. Секретов не содержит и доступен всем, кто видит инвентарь.
   */
  @Get('inventory-status')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryView)
  @ServerScoped('serverId')
  async inventoryStatus(@Param('serverId') serverId: string): Promise<MinecraftInventoryStatusDto> {
    return {
      companionConfigured: await this.companion.isConfigured(serverId),
      docsUrl: COMPANION_DOCS_URL,
    };
  }

  @Get('inventory/:name')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryView)
  @ServerScoped('serverId')
  inventory(@Param('serverId') serverId: string, @Param('name') name: string) {
    return this.minecraft.getInventory(serverId, name);
  }

  /**
   * Выдать игроку набор предметов.
   *
   * Ответ построчный: список выдают целиком, и частичный успех тут норма —
   * что-то не поместилось, где-то опечатка в идентификаторе. Общий
   * «получилось/не получилось» заставил бы гадать, какая строка виновата.
   */
  @Post('inventory/:name/give')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryEdit)
  @ServerScoped('serverId')
  giveItems(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: GiveItemsDto,
  ): Promise<MinecraftGiveResponse> {
    return this.companion.giveItems(serverId, name, dto.items);
  }

  /**
   * Очистить выбранные слоты или инвентарь целиком.
   *
   * Полная очистка — по явному `all`, а не по пустому выбору: вернуть стёртое
   * панель не умеет. Подтверждение спрашивает интерфейс, а здесь остаётся
   * запись в журнале — её делает общий аудит-перехватчик, тело запроса в нём
   * видно, и по нему потом понятно, что именно стёрли.
   */
  @Post('inventory/:name/clear')
  @RequirePermission(MINECRAFT_PERMISSIONS.inventoryEdit)
  @ServerScoped('serverId')
  async clearInventory(
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: InventoryClearDto,
  ): Promise<{ ok: true }> {
    await this.companion.clearInventory(serverId, name, dto);
    return { ok: true };
  }

  /** TPS и время тика. Право то же, что на просмотр игроков. */
  @Get('performance')
  @RequirePermission(MINECRAFT_PERMISSIONS.playersView)
  @ServerScoped('serverId')
  performance(@Param('serverId') serverId: string): Promise<MinecraftPerformanceDto> {
    return this.minecraft.getPerformance(serverId);
  }

  // ---------- Настройки подключения ----------

  /** Только флаги — ни пароля, ни хоста, ни адреса плагина наружу. */
  @Get('config')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  async configStatus(@Param('serverId') serverId: string): Promise<MinecraftConfigStatusDto> {
    const creds = await this.config.read(serverId);
    return {
      rconConfigured: !!creds.rcon,
      companionConfigured: !!creds.companion,
      lastSeenAt: creds.lastSeenAt ?? null,
    };
  }

  @Put('config/rcon')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // приватный адрес через туннель, порт и пароль
  async setRcon(@Param('serverId') serverId: string, @Body() dto: RconConfigDto) {
    await this.config.setRcon(serverId, dto.host, dto.port, dto.password);
    // Сразу проверяем связь, чтобы ошибка не всплыла позже у модератора.
    const output = await this.minecraft.runCommand(serverId, 'list');
    return { ok: true, probe: output.slice(0, 200) };
  }

  @Put('config/companion')
  @RequirePermission(MINECRAFT_PERMISSIONS.configure)
  @ServerScoped('serverId')
  @AuditRedactBody() // адрес companion-плагина и его токен
  async setCompanion(@Param('serverId') serverId: string, @Body() dto: CompanionConfigDto) {
    await this.config.setCompanion(serverId, dto.baseUrl ?? null, dto.token ?? null);
    return { ok: true, configured: await this.companion.isConfigured(serverId) };
  }
}

/**
 * Число из строки запроса. Мусор и отрицательные — как будто параметра нет:
 * пагинация не то место, где стоит отвечать 400 на опечатку в адресной строке.
 */
function positiveInt(raw: string | undefined): number | undefined {
  if (!raw) return undefined;
  const value = Number(raw);
  return Number.isInteger(value) && value > 0 ? value : undefined;
}
