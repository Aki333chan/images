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
  'guilds',
] as const;
export type ModuleCapability = (typeof MODULE_CAPABILITIES)[number];

/**
 * Состояние возможности:
 *  - true              — работает всегда;
 *  - 'requires-plugin' — вкладка показывается, но требует companion-плагина
 *                        на игровом сервере; без него рендерится инструкция.
 */
export type CapabilityState = true | 'requires-plugin';

/** Порядок ключей сохраняется и определяет порядок вкладок в интерфейсе. */
export type ModuleCapabilities = Partial<Record<ModuleCapability, CapabilityState>>;

/** Право, объявляемое модулем. */
export interface ModulePermission {
  /** Полный ключ, конвенция: `<moduleId>.<action>`, напр. `minecraft.ban`. */
  key: string;
  /**
   * Зачем это право — по-русски и для того, кто читает манифест.
   *
   * НА ЭКРАН НЕ ПОПАДАЕТ, и поэтому не переведено: доступы выдаются целыми
   * ролями и списком серверов, а не по одному праву, так что показывать эти
   * строки просто негде. Если когда-нибудь появится экран с правами
   * поштучно — здесь понадобится ключ словаря, а не текст.
   */
  description: string;
  /** Роли, получающие право по умолчанию. Пустой список — только ГМ (OWNER). */
  defaultRoles: Role[];
}

/**
 * Манифест игрового модуля — сериализуемая часть, общая для бэка и фронта.
 * Backend-часть модуля (NestJS-модуль с роутами/WS/кроном) описывается
 * отдельным типом в apps/api (см. modules/module-registry.ts), потому что
 * ссылки на классы NestJS не должны попадать в браузерный бандл.
 */
export interface GameModuleManifest {
  /** Уникальный id, kebab-case: 'minecraft', 'test-dummy'. */
  id: string;
  displayName: string;
  capabilities: ModuleCapabilities;
  permissions: ModulePermission[];
}

/** Возможности манифеста в порядке объявления. */
export function listCapabilities(
  manifest: GameModuleManifest,
): { capability: ModuleCapability; state: CapabilityState }[] {
  return (Object.entries(manifest.capabilities) as [ModuleCapability, CapabilityState][])
    .filter(([, state]) => state !== undefined)
    .map(([capability, state]) => ({ capability, state }));
}
