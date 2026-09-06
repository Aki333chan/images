import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { ArrayMaxSize, IsArray, IsString, MaxLength } from 'class-validator';
import {
  PLUGIN_PERMISSIONS,
  type AddonInstallResponseDto,
  type ServerAddonsDto,
} from '@aurum/shared';
import { AuthUser, CurrentPermissions, CurrentUser } from '../auth/decorators';
import type { EffectivePermissions } from '../rbac/permissions.service';
import { ServerScoped } from '../rbac/rbac.decorators';
import { AddonsService } from './addons.service';

class InstallAddonsDto {
  /**
   * Пусто — допустимо и не ошибка.
   *
   * В поп-апе можно не отметить ни одного плагина и нажать «Установить
   * выбранное»: человек передумал уже у кнопки. Отвечать на это ошибкой
   * значило бы требовать от него вернуться и нажать другую кнопку.
   */
  @IsArray()
  @ArrayMaxSize(10)
  @IsString({ each: true })
  @MaxLength(64, { each: true })
  ids!: string[];
}

/**
 * Наши собственные плагины на игровом сервере.
 *
 * Роуты вне модуля игры и намеренно: пакет аддонов — свойство панели, а не
 * Minecraft. Появится пакет для другой игры — добавится строка в реестре
 * MODULE_ADDONS, и эти же роуты начнут работать для неё, ничего не переезжая.
 */
@Controller('servers/:serverId/addons')
export class AddonsController {
  constructor(private readonly addons: AddonsService) {}

  /**
   * Состояние без побочных действий — для кнопки «Рекомендуемые плагины».
   *
   * Права на установку здесь не требуется: состояние читает каждый, у кого
   * есть доступ к серверу, а вот кнопка установки в поп-апе появится только
   * при `canInstall`. Показать модератору, что панель поставила companion, —
   * не утечка, а объяснение, откуда на сервере взялся новый плагин.
   */
  @Get()
  @ServerScoped('serverId')
  state(
    @Param('serverId') serverId: string,
    @CurrentPermissions() perms: EffectivePermissions,
  ): Promise<ServerAddonsDto> {
    return this.addons.state(serverId, canInstall(perms));
  }

  /**
   * Заход на страницу сервера: поставить обязательное и сказать, что дальше.
   *
   * POST, а не GET, потому что метод меняет состояние игрового сервера —
   * ставит companion, если его нет. Прятать установку внутрь чтения значило
   * бы, что обновление страницы браузером само по себе пишет файлы на чужой
   * сервер.
   */
  @Post('bootstrap')
  @ServerScoped('serverId')
  bootstrap(
    @Param('serverId') serverId: string,
    @CurrentUser() user: AuthUser,
    @CurrentPermissions() perms: EffectivePermissions,
  ): Promise<ServerAddonsDto> {
    return this.addons.bootstrap(serverId, user.id, canInstall(perms));
  }

  /** Поставить отмеченное в поп-апе. Право то же, что у маркета. */
  @Post('install')
  @ServerScoped('serverId')
  async install(
    @Param('serverId') serverId: string,
    @Body() dto: InstallAddonsDto,
    @CurrentUser() user: AuthUser,
  ): Promise<AddonInstallResponseDto> {
    return { results: await this.addons.install(serverId, dto.ids, user.id) };
  }

  /**
   * «Закрыть и не предлагать» — навсегда для этого сервера.
   *
   * Вернуться к выбору можно кнопкой «Рекомендуемые плагины»: она видна
   * всегда и этим флагом не гасится. Иначе решение, принятое в одну секунду
   * и, возможно, не тем человеком, оказалось бы окончательным.
   */
  @Post('dismiss')
  @ServerScoped('serverId')
  async dismiss(
    @Param('serverId') serverId: string,
    @CurrentUser() user: AuthUser,
    @CurrentPermissions() perms: EffectivePermissions,
  ): Promise<ServerAddonsDto> {
    await this.addons.dismiss(serverId, user.id);
    return this.addons.state(serverId, canInstall(perms));
  }
}

/**
 * Можно ли этому человеку ставить плагины.
 *
 * ГМ проходит без записи в списке прав: у него их все по определению, и
 * заводить ему явное `minecraft.plugins.install` значило бы, что забытая
 * строка в раскладке ролей отнимет у владельца панели его же кнопку.
 */
function canInstall(perms: EffectivePermissions): boolean {
  return perms.isOwner || perms.permissions.has(PLUGIN_PERMISSIONS.install as never);
}
