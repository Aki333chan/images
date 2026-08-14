import { SetMetadata } from '@nestjs/common';

export const PERMISSION_KEY = 'requiredPermission';
export const SERVER_SCOPE_PARAM = 'serverScopeParam';

/** Роут требует право (ключ ядра или модуля). Проверяется по текущему состоянию БД. */
export const RequirePermission = (permission: string) => SetMetadata(PERMISSION_KEY, permission);

/**
 * Роут привязан к серверу: значение указанного route-параметра (id сервера)
 * должно входить в список серверов, доступных пользователю.
 */
export const ServerScoped = (param = 'serverId') => SetMetadata(SERVER_SCOPE_PARAM, param);
