import type { Role } from './roles';

/**
 * Ключ права. Ядро определяет свои ключи (ниже), игровые модули добавляют
 * собственные вида `<moduleId>.<action>` через манифест.
 */
export type PermissionKey = string;

export const CORE_PERMISSIONS = [
  'users.manage',
  /**
   * Создание учётных записей только с ролью Модератор.
   * Отдельно от users.manage: Админ может завести модератора, но не
   * может менять роли, деактивировать людей и раздавать доступы.
   */
  'users.create.moderator',
  'servers.view',
  'servers.manage',
  'servers.power',
  'tickets.view',
  'tickets.respond',
  'tickets.close',
  'audit.view',
  /**
   * Общение с AI-ассистентом. Отдельным правом, а не «всем подряд»:
   * ассистент ходит в платный внешний сервис, и круг пользующихся им
   * должен быть управляемым. Права самих действий ассистент НЕ добавляет —
   * инструменты выполняются с правами того, кто ведёт диалог.
   */
  'ai.chat',
] as const;
export type CorePermission = (typeof CORE_PERMISSIONS)[number];

/**
 * Права ядра по ролям. '*' — все права, включая права модулей.
 * Права модулей раздаются ролям через `defaultRoles` в манифесте модуля.
 */
export const CORE_ROLE_PERMISSIONS: Record<Role, readonly CorePermission[] | '*'> = {
  OWNER: '*',
  ADMIN: [
    'ai.chat',
    'users.create.moderator',
    'servers.view',
    'servers.manage',
    'servers.power',
    'tickets.view',
    'tickets.respond',
    'tickets.close',
    'audit.view',
  ],
  MODERATOR: ['ai.chat', 'servers.view', 'tickets.view', 'tickets.respond', 'tickets.close'],
  VIEWER: ['servers.view', 'tickets.view'],
};
