import { createParamDecorator, ExecutionContext, SetMetadata } from '@nestjs/common';
import type { EffectivePermissions } from '../rbac/permissions.service';

export const IS_PUBLIC_KEY = 'isPublic';
/** Роут доступен без access-токена (логин, refresh). */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);

export interface AuthUser {
  id: string;
  sessionId: string;
}

/**
 * Права текущего пользователя, уже посчитанные PermissionsGuard.
 *
 * Нужен, чтобы игровые модули могли узнать список доступных серверов, НЕ
 * импортируя PermissionsService. Прямой импорт оттуда даёт кольцо: сам сервис
 * читает реестр игровых модулей, а реестр тянет модули обратно. В CommonJS
 * такое кольцо не падает, а возвращает undefined, и Nest сообщает
 * «can't resolve dependencies … at index [1]» со знаком вопроса вместо имени —
 * ровно этим однажды кончился 502 на боевой машине.
 *
 * Тип импортируется через `import type`: он стирается при сборке и кольца не
 * создаёт, в отличие от импорта самого класса.
 */
export const CurrentPermissions = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): EffectivePermissions => {
    const req = ctx.switchToHttp().getRequest();
    return req.effectivePermissions as EffectivePermissions;
  },
);

/** Текущий пользователь из access-токена (id + sessionId, ничего больше). */
export const CurrentUser = createParamDecorator((_data: unknown, ctx: ExecutionContext): AuthUser => {
  const req = ctx.switchToHttp().getRequest();
  return req.user as AuthUser;
});
