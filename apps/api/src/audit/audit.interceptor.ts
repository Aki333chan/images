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

/**
 * Сколько ключей объекта разбирать и какой длины строки хранить.
 *
 * Аудит отвечает на вопрос «кто что сделал», а не хранит данные целиком.
 * Пределы — страховка от тела, которого никто не ожидал: без них один
 * запрос способен положить в журнал десятки мегабайт и занять процессор на
 * минуту (ровно это и случилось с загрузкой файла, см. ниже).
 */
const MAX_KEYS = 50;
const MAX_STRING = 2000;

function describeBytes(length: number): string {
  const mb = length / 1024 / 1024;
  const size = mb >= 1 ? `${mb.toFixed(1)} МБ` : `${Math.ceil(length / 1024)} КБ`;
  return `[двоичные данные, ${size}]`;
}

/**
 * Тело запроса в вид, пригодный для журнала.
 *
 * <h2>Двоичное тело — только размером</h2>
 *
 * ЭТО НЕ ОПТИМИЗАЦИЯ, А ИСПРАВЛЕНИЕ. Загрузка файла приходит сюда сырым
 * Buffer. Буфер — это объект, но НЕ массив: {@code Array.isArray} на нём даёт
 * false, и прошлая версия уходила в ветку {@code Object.entries}, где буфер
 * разворачивается по байтам. Пятнадцать мегабайт превращались в объект на
 * 15 728 640 ключей — пятьдесят секунд синхронной работы, ещё десять на
 * сериализацию и сто восемьдесят четыре мегабайта JSON в таблицу журнала.
 *
 * Синхронной — то есть на это время вставал весь Node, и панель переставала
 * отвечать всем сразу, а не только тому, кто грузил файл.
 *
 * Содержимое файла в аудите не нужно и вредно: журнал отвечает на вопрос
 * «кто и что загрузил», а сам файл лежит на игровом сервере. Поэтому от
 * двоичного тела остаётся размер.
 */
export function sanitize(value: unknown, depth = 0): unknown {
  if (typeof value === 'string') {
    return value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}… [обрезано]` : value;
  }
  if (depth > 3 || value === null || typeof value !== 'object') return value;

  // Buffer и любой другой вид на двоичные данные — до всех прочих проверок.
  if (ArrayBuffer.isView(value)) return describeBytes((value as ArrayBufferView).byteLength);
  if (value instanceof ArrayBuffer) return describeBytes(value.byteLength);

  if (Array.isArray(value)) return value.slice(0, 20).map((v) => sanitize(v, depth + 1));

  const entries = Object.entries(value as Record<string, unknown>);
  const out: Record<string, unknown> = {};
  for (const [k, v] of entries.slice(0, MAX_KEYS)) {
    out[k] = SENSITIVE_KEYS.test(k) ? '[redacted]' : sanitize(v, depth + 1);
  }
  // Про опущенное говорим прямо: молча урезанный журнал хуже урезанного с
  // пометкой — по нему делают выводы о том, что произошло.
  if (entries.length > MAX_KEYS) {
    out['…'] = `[ещё ${entries.length - MAX_KEYS} полей опущено]`;
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
