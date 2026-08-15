import { CanActivate, ExecutionContext, ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { timingSafeEqual } from 'crypto';
import { MinecraftConfigService } from './minecraft-config.service';

/**
 * Авторизация companion-плагина по токену сервера.
 *
 * Токен тот же, которым панель ходит в плагин, — он хранится зашифрованным
 * и сравнивается за постоянное время. Дополнительно запрос должен приходить
 * из приватной сети: internal-эндпоинт живёт на внутреннем адресе панели
 * (10.0.0.1) и через nginx наружу не публикуется.
 */
@Injectable()
export class CompanionTokenGuard implements CanActivate {
  private readonly logger = new Logger(CompanionTokenGuard.name);

  constructor(private readonly config: MinecraftConfigService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest();
    const serverId: string | undefined = req.params?.serverId;
    if (!serverId) throw new ForbiddenException('Не указан сервер');

    if (!isPrivateAddress(req.ip)) {
      this.logger.warn(`Обращение к internal-эндпоинту из публичной сети отклонено (${req.ip})`);
      throw new ForbiddenException('Эндпоинт доступен только из внутренней сети');
    }

    const header: string | undefined = req.headers?.['authorization'];
    const provided = header?.startsWith('Bearer ') ? header.slice(7).trim() : header?.trim();
    if (!provided) throw new ForbiddenException('Нет токена сервера');

    const creds = await this.config.read(serverId);
    const expected = creds.companion?.token;
    if (!expected) {
      throw new ForbiddenException('Для этого сервера не настроен companion-плагин');
    }
    if (!constantTimeEquals(provided, expected)) {
      this.logger.warn(`Неверный токен companion-плагина для сервера ${serverId}`);
      throw new ForbiddenException('Неверный токен сервера');
    }
    return true;
  }
}

function constantTimeEquals(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  // timingSafeEqual требует одинаковой длины, а сама длина секретом не является.
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

/**
 * Приватные диапазоны + loopback. Плагин ходит по туннелю (10.0.0.2 -> 10.0.0.1),
 * поэтому публичных адресов здесь быть не должно.
 */
export function isPrivateAddress(rawIp: string | undefined): boolean {
  if (!rawIp) return false;
  // Express отдаёт IPv4-mapped адреса вида ::ffff:10.0.0.2.
  const ip = rawIp.replace(/^::ffff:/i, '');
  if (ip === '::1' || ip === '127.0.0.1') return true;
  const octets = ip.split('.');
  if (octets.length !== 4) return false;
  const [a, b] = octets.map((part) => Number(part));
  if (a === undefined || b === undefined || Number.isNaN(a) || Number.isNaN(b)) return false;
  if (a === 10) return true;
  if (a === 127) return true;
  if (a === 192 && b === 168) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  return false;
}
