export const ROLES = ['OWNER', 'ADMIN', 'MODERATOR', 'VIEWER'] as const;
export type Role = (typeof ROLES)[number];

/** Подписи ролей в интерфейсе. */
export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'ГМ',
  ADMIN: 'Админ',
  MODERATOR: 'Модератор',
  VIEWER: 'Наблюдатель',
};
