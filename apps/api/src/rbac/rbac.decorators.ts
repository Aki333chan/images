import { SetMetadata } from '@nestjs/common';

export const PERMISSION_KEY = 'requiredPermission';
export const SERVER_SCOPE_PARAM = 'serverScopeParam';

/**
 * Роут требует право (ключ ядра или модуля). Проверяется по текущему
 * состоянию БД на каждый запрос.
 *
 * Можно перечислить несколько — тогда достаточно ЛЮБОГО из них. Это нужно
 * там, где один и тот же маршрут открыт разным ролям с разными ключами:
 * например, создать учётку может и ГМ (users.manage), и Админ
 * (users.create.moderator), а различие в том, что каждому позволено
 * дальше, решает уже сервис.
 */
export const RequirePermission = (...permissions: string[]) =>
  SetMetadata(PERMISSION_KEY, permissions);

/**
 * Роут привязан к серверу: значение указанного route-параметра (id сервера)
 * должно входить в список серверов, доступных пользователю.
 */
export const ServerScoped = (param = 'serverId') => SetMetadata(SERVER_SCOPE_PARAM, param);
