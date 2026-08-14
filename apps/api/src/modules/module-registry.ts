import type { Type } from '@nestjs/common';
import type { GameModuleManifest } from '@aurum/shared';

/**
 * Backend-описание игрового модуля: сериализуемый манифест + NestJS-модуль
 * с роутами/WS-gateway/кронами и опциональной папкой Prisma-миграций.
 *
 * Конвенции:
 *  - контроллеры модуля монтируют роуты под `api/modules/<id>/...`;
 *  - права модуля объявляются в manifest.permissions с ключами `<id>.<action>`;
 *  - модели БД модуля добавляются в общий prisma/schema.prisma с префиксом
 *    таблиц `mod_<id>_`; миграции — общие prisma-миграции. Выключение модуля
 *    из modules.config.ts убирает роуты/крон/вкладки, но НЕ трогает данные.
 */
export interface BackendGameModule {
  manifest: GameModuleManifest;
  /** NestJS-модуль: контроллеры, WS-gateway, крон-провайдеры. Монтируется при старте. */
  nestModule?: Type<unknown>;
  /** Путь к папке с SQL-миграциями модуля (документация/справка, применяются общим `prisma migrate`). */
  migrationsDir?: string;
}

import { testDummyModule } from './test-dummy/test-dummy.def';
import { ENABLED_MODULE_IDS } from './modules.config';

/** Полный реестр известных модулей (включённые выбираются в modules.config.ts). */
const ALL_MODULES: BackendGameModule[] = [testDummyModule];

export function getEnabledModules(): BackendGameModule[] {
  const byId = new Map(ALL_MODULES.map((m) => [m.manifest.id, m]));
  return ENABLED_MODULE_IDS.map((id) => {
    const mod = byId.get(id);
    if (!mod) throw new Error(`modules.config.ts: неизвестный id модуля '${id}'`);
    return mod;
  });
}

export function getEnabledManifests(): GameModuleManifest[] {
  return getEnabledModules().map((m) => m.manifest);
}
