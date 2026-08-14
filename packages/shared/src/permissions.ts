import type { Role } from './roles';

/**
 * Ключ права. Ядро определяет свои ключи (ниже), игровые модули добавляют
 * собственные вида `<moduleId>.<action>` через манифест.
 */
export type PermissionKey = string;

export const CORE_PERMISSIONS = [
  'users.manage',
  'servers.view',
  'servers.manage',
  'servers.power',
  'tickets.view',
  'tickets.respond',
  'tickets.close',
  'audit.view',
] as const;
export type CorePermission = (typeof CORE_PERMISSIONS)[number];

/**
 * Права ядра по ролям. '*' — все права, включая права модулей.
 * Права модулей раздаются ролям через `defaultRoles` в манифесте модуля.
 */
export const CORE_ROLE_PERMISSIONS: Record<Role, readonly CorePermission[] | '*'> = {
  OWNER: '*',
  ADMIN: [
    'servers.view',
    'servers.manage',
    'servers.power',
    'tickets.view',
    'tickets.respond',
    'tickets.close',
    'audit.view',
  ],
  MODERATOR: ['servers.view', 'tickets.view', 'tickets.respond', 'tickets.close'],
  VIEWER: ['servers.view', 'tickets.view'],
};
