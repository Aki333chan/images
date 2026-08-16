import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { AuthUser } from '../auth/decorators';
import { PERMISSION_KEY, SERVER_SCOPE_PARAM } from './rbac.decorators';
import { PermissionsService } from './permissions.service';

/**
 * Глобальный guard (после JwtAuthGuard): читает @RequirePermission/@ServerScoped
 * и проверяет права по ТЕКУЩЕМУ состоянию БД на каждый запрос.
 * Роут без метаданных доступен любому аутентифицированному пользователю.
 */
@Injectable()
export class PermissionsGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly permissions: PermissionsService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const rawRequired = this.reflector.getAllAndOverride<string | string[] | undefined>(
      PERMISSION_KEY,
      [context.getHandler(), context.getClass()],
    );
    // Декоратор кладёт массив, но принимаем и одиночную строку: если где-то
    // метаданные проставят напрямую через SetMetadata, страж должен проверить
    // право, а не молча пропустить всех, не найдя у строки метода some.
    const required =
      rawRequired === undefined ? undefined : Array.isArray(rawRequired) ? rawRequired : [rawRequired];
    const scopeParam = this.reflector.getAllAndOverride<string | undefined>(SERVER_SCOPE_PARAM, [
      context.getHandler(),
      context.getClass(),
    ]);
    if ((!required || required.length === 0) && !scopeParam) return true;

    const req = context.switchToHttp().getRequest();
    const user: AuthUser | undefined = req.user;
    if (!user) return false; // public-роут с RBAC-метаданными — некорректная конфигурация

    const eff = await this.permissions.getEffectivePermissions(user.id);
    req.effectivePermissions = eff;

    // Достаточно любого из перечисленных прав.
    if (required?.length && !required.some((key) => eff.permissions.has(key))) {
      throw new ForbiddenException(`Недостаточно прав (${required.join(' или ')})`);
    }
    if (scopeParam) {
      const serverId: string | undefined = req.params?.[scopeParam];
      if (!serverId) throw new ForbiddenException('Не указан сервер');
      await this.permissions.assertServerAccess(eff, serverId);
    }
    return true;
  }
}
