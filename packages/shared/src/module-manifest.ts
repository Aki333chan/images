import type { Role } from './roles';

/**
 * Возможности игрового модуля. Каждая capability соответствует вкладке
 * на экране сервера; фронтенд рендерит вкладки динамически по этому списку.
 */
export const MODULE_CAPABILITIES = [
  'console',
  'playerList',
  'banKick',
  'whitelist',
  'inventory',
  'quickCommands',
  'tickets',
] as const;
export type ModuleCapability = (typeof MODULE_CAPABILITIES)[number];

/** Право, объявляемое модулем. */
export interface ModulePermission {
  /** Полный ключ, конвенция: `<moduleId>.<action>`, напр. `minecraft.ban`. */
  key: string;
  description: string;
  /** Роли, получающие право по умолчанию (OWNER имеет всё всегда). */
  defaultRoles: Role[];
}

/**
 * Манифест игрового модуля — сериализуемая часть, общая для бэка и фронта.
 * Backend-часть модуля (NestJS-модуль с роутами/WS/кроном) описывается
 * отдельным типом в apps/api (см. modules/module-registry.ts), потому что
 * ссылки на классы NestJS не должны попадать в браузерный бандл.
 */
export interface GameModuleManifest {
  /** Уникальный id, kebab-case: 'minecraft-vanilla', 'test-dummy'. */
  id: string;
  displayName: string;
  capabilities: ModuleCapability[];
  permissions: ModulePermission[];
}
