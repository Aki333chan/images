import { CanActivate, ExecutionContext, ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { constantTimeEquals, isPrivateAddress } from '../../common/private-network';
import { SevenDaysConfigService } from './sevendays-config.service';

/**
 * Авторизация companion-мода по токену сервера.
 *
 * Тот же токен, которым панель ходит в мод: он хранится зашифрованным и
 * сравнивается за постоянное время. Дополнительно запрос обязан приходить из
 * приватной сети — internal-эндпоинт живёт на внутреннем адресе панели
 * (10.0.0.1) и через nginx наружу не публикуется.
 *
 * Два условия, а не одно, намеренно: утёкший токен без доступа в туннель
 * бесполезен, а доступ в туннель без токена — тоже.
 */
@Injectable()
export class SevenDaysCompanionGuard implements CanActivate {
  private readonly logger = new Logger(SevenDaysCompanionGuard.name);

  constructor(private readonly config: SevenDaysConfigService) {}

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
      throw new ForbiddenException('Для этого сервера не настроен companion-мод');
    }
    if (!constantTimeEquals(provided, expected)) {
      this.logger.warn(`Неверный токен companion-мода для сервера ${serverId}`);
      throw new ForbiddenException('Неверный токен сервера');
    }
    return true;
  }
}
