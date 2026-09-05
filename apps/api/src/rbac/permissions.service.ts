import { ForbiddenException, Injectable, UnauthorizedException } from '@nestjs/common';
import {
  CORE_PERMISSIONS,
  CORE_ROLE_PERMISSIONS,
  isLocale,
  MeResponse,
  PermissionKey,
  Role,
} from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { getEnabledManifests } from '../modules/module-registry';

export interface EffectivePermissions {
  userId: string;
  role: Role;
  permissions: Set<PermissionKey>;
  /** null — доступ ко всем серверам (OWNER). */
  allowedServerIds: Set<string> | null;
  isOwner: boolean;
}

/**
 * Единственный источник истины о правах. ВСЕГДА читает состояние из БД —
 * ничего не берётся из JWT, поэтому смена роли действует немедленно.
 */
@Injectable()
export class PermissionsService {
  constructor(private readonly prisma: PrismaService) {}

  /** Все ключи прав, которые роль имеет по умолчанию (ядро + включённые модули). */
  private permissionsForRole(role: Role): Set<PermissionKey> {
    const result = new Set<PermissionKey>();
    const core = CORE_ROLE_PERMISSIONS[role];
    const modulePerms = getEnabledManifests().flatMap((m) => m.permissions);
    if (core === '*') {
      // OWNER: все ядровые + все модульные права.
      for (const p of CORE_PERMISSIONS) result.add(p);
      for (const p of modulePerms) result.add(p.key);
      return result;
    }
    for (const p of core) result.add(p);
    for (const p of modulePerms) {
      if (p.defaultRoles.includes(role)) result.add(p.key);
    }
    return result;
  }

  async getEffectivePermissions(userId: string): Promise<EffectivePermissions> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      include: { serverAccess: { select: { serverId: true } } },
    });
    if (!user || !user.isActive) {
      throw new UnauthorizedException('Пользователь не найден или деактивирован');
    }
    const isOwner = user.role === 'OWNER';
    return {
      userId: user.id,
      role: user.role,
      permissions: this.permissionsForRole(user.role),
      // Пустой список привязок у не-OWNER означает «нет доступа ни к одному серверу»,
      // а не «ко всем»: доступ всегда выдаётся явно.
      allowedServerIds: isOwner ? null : new Set(user.serverAccess.map((a) => a.serverId)),
      isOwner,
    };
  }

  async assertServerAccess(eff: EffectivePermissions, serverId: string): Promise<void> {
    if (eff.allowedServerIds === null) return;
    if (!eff.allowedServerIds.has(serverId)) {
      throw new ForbiddenException('Нет доступа к этому серверу');
    }
  }

  async buildMeResponse(userId: string): Promise<MeResponse> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    const eff = await this.getEffectivePermissions(userId);
    return {
      user: {
        id: user.id,
        email: user.email,
        nickname: user.nickname,
        nicknameChangeAllowed: user.nicknameChangeAllowed,
        role: user.role,
        totpEnabled: user.totpEnabled,
        mustChangePassword: user.mustChangePassword,
        // Не выбирал язык — null, и панель пойдёт за браузером. Подставлять
        // здесь DEFAULT_LOCALE нельзя: тогда переключатель показывал бы
        // «Русский» человеку, который русский не выбирал.
        locale: isLocale(user.locale) ? user.locale : null,
      },
      permissions: [...eff.permissions].sort(),
      allowedServerIds: eff.allowedServerIds === null ? null : [...eff.allowedServerIds],
    };
  }
}
