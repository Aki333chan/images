import { CallHandler, ExecutionContext, Injectable, NestInterceptor } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Observable, tap } from 'rxjs';
import { AuthUser } from '../auth/decorators';
import { AuditService } from './audit.service';
import { AUDIT_REDACT_BODY } from './audit.decorators';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
/** Роуты, которые не аудируем (шумные/несущественные или содержат секреты). */
const SKIP_PATHS = [/^\/api\/auth\/refresh$/, /^\/api\/auth\/login$/, /^\/api\/auth\/2fa$/];
const SENSITIVE_KEYS = /pass(word)?|secret|token|code|key|credential/i;

function sanitize(value: unknown, depth = 0): unknown {
  if (depth > 3 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.slice(0, 20).map((v) => sanitize(v, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    out[k] = SENSITIVE_KEYS.test(k) ? '[redacted]' : sanitize(v, depth + 1);
  }
  return out;
}

/**
 * Глобальный интерцептор: автоматически пишет в аудит-лог каждый успешный
 * мутирующий HTTP-запрос. Тело запроса санитизируется (пароли/токены вырезаются).
 */
@Injectable()
export class AuditInterceptor implements NestInterceptor {
  constructor(
    private readonly audit: AuditService,
    private readonly reflector: Reflector,
  ) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (context.getType() !== 'http') return next.handle();
    const req = context.switchToHttp().getRequest();
    const method: string = req.method;
    const path: string = req.route?.path ?? req.url;

    if (!MUTATING_METHODS.has(method) || SKIP_PATHS.some((re) => re.test(req.url.split('?')[0]))) {
      return next.handle();
    }

    const redactBody = this.reflector.getAllAndOverride<boolean>(AUDIT_REDACT_BODY, [
      context.getHandler(),
      context.getClass(),
    ]);

    return next.handle().pipe(
      tap({
        next: () => {
          const user: AuthUser | undefined = req.user;
          const params: Record<string, string> = req.params ?? {};
          const targetId = params.serverId ?? params.id ?? null;
          void this.audit
            .log({
              actorId: user?.id ?? null,
              actorType: 'user',
              action: `${method} ${path}`,
              targetType: this.targetTypeFromPath(path),
              targetId,
              metadata: {
                params,
                body: redactBody ? '[redacted: секретный payload]' : sanitize(req.body),
              },
            })
            .catch(() => undefined); // аудит не должен ломать основной запрос
        },
      }),
    );
  }

  private targetTypeFromPath(path: string): string | null {
    const m = /^\/api\/([a-z-]+)/.exec(path);
    return m?.[1] ?? null;
  }
}
