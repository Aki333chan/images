import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { JwtService } from '@nestjs/jwt';
import { env } from '../config/env';
import { IS_PUBLIC_KEY, AuthUser } from './decorators';

export interface AccessTokenPayload {
  sub: string;
  sid: string;
  purpose: 'access';
}

/**
 * Глобальный guard: проверяет access-токен. В токене только id пользователя и
 * id сессии — никаких ролей/прав: права всегда читаются из БД (PermissionsGuard).
 */
@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly jwt: JwtService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (isPublic) return true;

    const req = context.switchToHttp().getRequest();
    const header: string | undefined = req.headers['authorization'];
    const token = header?.startsWith('Bearer ') ? header.slice(7) : undefined;
    if (!token) throw new UnauthorizedException('auth.err.noAccessToken');

    let payload: AccessTokenPayload;
    try {
      payload = await this.jwt.verifyAsync<AccessTokenPayload>(token, {
        secret: env.JWT_ACCESS_SECRET,
      });
    } catch {
      throw new UnauthorizedException('auth.err.badAccessToken');
    }
    if (payload.purpose !== 'access') throw new UnauthorizedException('auth.err.badTokenType');

    req.user = { id: payload.sub, sessionId: payload.sid } satisfies AuthUser;
    return true;
  }
}
