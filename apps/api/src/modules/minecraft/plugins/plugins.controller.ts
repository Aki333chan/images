import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Param,
  Post,
  Query,
  Res,
} from '@nestjs/common';
import { IsBoolean, IsIn, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';
import {
  MARKET_SORTS,
  MARKET_SOURCES,
  PLUGIN_PERMISSIONS,
  type InstalledPluginsResponseDto,
  type MarketFilters,
  type MarketPluginDto,
  type MarketProjectType,
  type MarketSearchResponseDto,
  type MarketSort,
  type MarketSourceId,
  type MarketVersionsResponseDto,
  type PluginInstallResultDto,
  type ServerTargetDto,
} from '@aurum/shared';
import { AuthUser, CurrentPermissions, CurrentUser } from '../../../auth/decorators';
import type { EffectivePermissions } from '../../../rbac/permissions.service';
import { RequirePermission, ServerScoped } from '../../../rbac/rbac.decorators';
import type { Response } from 'express';
import { MarketService } from './market.service';
import { PluginFilesService } from './plugin-files.service';
import { PluginTargetsService } from './plugin-targets.service';

const SOURCE_IDS = MARKET_SOURCES.map((s) => s.id);

class InstallDto {
  @IsIn(SOURCE_IDS)
  source!: MarketSourceId;

  @IsString()
  @MinLength(1)
  @MaxLength(200)
  pluginId!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(200)
  versionId!: string;
}

class ToggleDto {
  @IsBoolean()
  enabled!: boolean;
}

class FileToggleDto {
  @IsString()
  @MaxLength(200)
  fileName!: string;

  @IsBoolean()
  disabled!: boolean;
}

class RemoveDto {
  @IsString()
  @MaxLength(200)
  fileName!: string;

  @IsOptional()
  @IsString()
  @MaxLength(200)
  pluginName?: string;

  /** Папку данных удаляем только по явной просьбе. */
  @IsOptional()
  @IsBoolean()
  withData?: boolean;
}

/**
 * Маркет плагинов и управление установленными.
 *
 * ПРАВА. Установка плагина — это запуск произвольного кода на игровом сервере,
 * поэтому оба права по умолчанию только у ГМ и Админа (см. манифест модуля).
 * Модератору здесь делать нечего.
 *
 * СОВМЕСТИМОСТЬ. Ни один маршрут не отсеивает результаты по версии сервера.
 * Сравнение считается и отдаётся полем compatibility — это подсказка для
 * бейджа, решение всегда за человеком.
 */
@Controller('modules/minecraft')
export class PluginsController {
  constructor(
    private readonly market: MarketService,
    private readonly files: PluginFilesService,
    private readonly targets: PluginTargetsService,
  ) {}

  // ------------------------------------------------------------- Маркет

  /**
   * Поиск по маркету.
   *
   * Фильтры приезжают списками через запятую, а не повторяющимися
   * параметрами: так адрес читаем глазами и его можно переслать коллеге,
   * а разбор не зависит от того, как конкретная библиотека складывает
   * повторы в массив.
   *
   * НИЧЕГО ИЗ ЭТОГО НЕ ПОДСТАВЛЯЕТСЯ ПАНЕЛЬЮ САМОСТОЯТЕЛЬНО. Пустой фильтр
   * означает «без ограничения»: панель не сужает выдачу по версии сервера
   * за человека — это его галочка.
   */
  @Get('market/search')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  search(
    @Query('q') q?: string,
    @Query('limit') limit?: string,
    @Query('offset') offset?: string,
    @Query('type') type?: string,
    @Query('sort') sort?: string,
    @Query('gameVersions') gameVersions?: string,
    @Query('loaders') loaders?: string,
    @Query('sources') sources?: string,
  ): Promise<MarketSearchResponseDto> {
    const filters: MarketFilters = {
      gameVersions: csv(gameVersions),
      loaders: csv(loaders),
      // Незнакомый источник молча отбрасываем: набор источников — это код
      // панели, а не пользовательский ввод, и падать из-за опечатки в
      // адресной строке незачем.
      sources: csv(sources).filter((s): s is MarketSourceId =>
        (SOURCE_IDS as string[]).includes(s),
      ),
    };

    return this.market.search(
      (q ?? '').slice(0, 120),
      clamp(Number(limit) || 20, 1, 50),
      clamp(Number(offset) || 0, 0, 5000),
      { type: asProjectType(type), sort: asSort(sort), filters },
    );
  }

  /**
   * Версии игры для галочек фильтра.
   *
   * Список не зашит в код: он устаревал бы с каждым релизом Minecraft.
   * Берётся у Modrinth (у него есть отдельный справочник версий) и кэшируется;
   * если Modrinth молчит, вернётся то, что успело закэшироваться, либо пусто —
   * и тогда фильтр по версии просто не показывается, а поиск работает.
   */
  @Get('market/game-versions')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  gameVersions(): Promise<string[]> {
    return this.market.listGameVersions();
  }

  /**
   * Серверы, на которые можно поставить плагин.
   *
   * Шаг выбора сервера показывается всегда, даже когда сервер один: серверов
   * станет больше, и интерфейс сразу делается под это, а не под частный случай.
   */
  @Get('market/targets')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  targetList(@CurrentPermissions() eff: EffectivePermissions): Promise<ServerTargetDto[]> {
    return this.targets.listForUser(eff);
  }

  /**
   * Иконка плагина через панель.
   *
   * Отдельным маршрутом ВЫШЕ market/:source/:pluginId: у Nest маршруты
   * сопоставляются по порядку объявления, и «market/icon» иначе поймался бы
   * шаблоном с двумя параметрами.
   */
  @Get('market/icon')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  async icon(@Query('url') url: string, @Res() res: Response): Promise<void> {
    const icon = await this.market.getIcon(url ?? '');
    res.setHeader('content-type', icon.contentType);
    // Сутки в кэше браузера: иконка плагина меняется раз в год, а список
    // маркета открывают подряд и помногу.
    res.setHeader('cache-control', 'private, max-age=86400');
    // Явный запрет на угадывание типа: содержимое пришло с чужого сервера,
    // и браузер не должен решать, что это на самом деле, сам.
    res.setHeader('x-content-type-options', 'nosniff');
    res.end(icon.body);
  }

  @Get('market/:source/:pluginId')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  plugin(
    @Param('source') source: MarketSourceId,
    @Param('pluginId') pluginId: string,
  ): Promise<MarketPluginDto> {
    return this.market.getPlugin(assertSource(source), decodeURIComponent(pluginId));
  }

  /**
   * ВСЕ опубликованные версии плагина.
   *
   * serverId необязателен: он нужен только чтобы посчитать бейдж совпадения.
   * Без него версии те же самые, просто без пометок.
   */
  @Get('market/:source/:pluginId/versions')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  async versions(
    @CurrentPermissions() eff: EffectivePermissions,
    @Param('source') source: MarketSourceId,
    @Param('pluginId') pluginId: string,
    @Query('serverId') serverId?: string,
  ): Promise<MarketVersionsResponseDto> {
    const target = serverId ? await this.targets.forUser(eff, serverId) : null;
    return {
      versions: await this.market.getVersions(
        assertSource(source),
        decodeURIComponent(pluginId),
        target,
      ),
      comparedTo: target,
    };
  }

  // -------------------------------------------------- Установка на сервер

  @Post('servers/:serverId/plugins/install')
  @RequirePermission(PLUGIN_PERMISSIONS.install)
  @ServerScoped('serverId')
  install(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: InstallDto,
  ): Promise<PluginInstallResultDto> {
    return this.files.install(serverId, dto.source, dto.pluginId, dto.versionId, user.id);
  }

  // ---------------------------------------------- Установленные плагины

  @Get('servers/:serverId/plugins/installed')
  @RequirePermission(PLUGIN_PERMISSIONS.manage)
  @ServerScoped('serverId')
  installed(@Param('serverId') serverId: string): Promise<InstalledPluginsResponseDto> {
    return this.files.listInstalled(serverId);
  }

  /** Горячее переключение через PluginManager — без перезапуска, best-effort. */
  @Post('servers/:serverId/plugins/:name/enabled')
  @RequirePermission(PLUGIN_PERMISSIONS.manage)
  @ServerScoped('serverId')
  setEnabled(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Param('name') name: string,
    @Body() dto: ToggleDto,
  ) {
    return this.files.setEnabled(serverId, decodeURIComponent(name), dto.enabled, user.id);
  }

  /** Отключение переносом файла — переживает перезапуск кем угодно. */
  @Post('servers/:serverId/plugins/file-state')
  @RequirePermission(PLUGIN_PERMISSIONS.manage)
  @ServerScoped('serverId')
  setFileState(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: FileToggleDto,
  ) {
    return this.files.setFileDisabled(serverId, dto.fileName, dto.disabled, user.id);
  }

  @Post('servers/:serverId/plugins/remove')
  @RequirePermission(PLUGIN_PERMISSIONS.manage)
  @ServerScoped('serverId')
  remove(
    @CurrentUser() user: AuthUser,
    @Param('serverId') serverId: string,
    @Body() dto: RemoveDto,
  ) {
    return this.files.remove(
      serverId,
      { fileName: dto.fileName, pluginName: dto.pluginName, withData: dto.withData === true },
      user.id,
    );
  }
}

function assertSource(raw: string): MarketSourceId {
  if ((SOURCE_IDS as string[]).includes(raw)) return raw as MarketSourceId;
  throw new BadRequestException(`Неизвестный источник ${raw}`);
}

/** Список через запятую -> массив без пустот и дублей, с разумным потолком. */
function csv(raw: string | undefined): string[] {
  if (!raw) return [];
  return [
    ...new Set(
      raw
        .split(',')
        .map((part) => part.trim())
        .filter((part) => part.length > 0 && part.length <= 40),
    ),
  ].slice(0, 40);
}

/** Тип проекта по умолчанию — плагины: их на свете больше. */
function asProjectType(raw: string | undefined): MarketProjectType {
  return raw === 'mod' ? 'mod' : 'plugin';
}

function asSort(raw: string | undefined): MarketSort {
  return (MARKET_SORTS as readonly string[]).includes(raw ?? '')
    ? (raw as MarketSort)
    : 'relevance';
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(Number.isFinite(value) ? value : min, min), max);
}
