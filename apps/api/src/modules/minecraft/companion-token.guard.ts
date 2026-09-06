import { CanActivate, ExecutionContext, ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { constantTimeEquals, isPrivateAddress } from '../../common/private-network';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

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
    if (!serverId) throw new ForbiddenException('mc.err.noServer');

    if (!isPrivateAddress(req.ip)) {
      this.logger.warn(`Обращение к internal-эндпоинту из публичной сети отклонено (${req.ip})`);
      throw new ForbiddenException('mc.err.internalOnly');
    }

    const header: string | undefined = req.headers?.['authorization'];
    const provided = header?.startsWith('Bearer ') ? header.slice(7).trim() : header?.trim();
    if (!provided) throw new ForbiddenException('mc.err.noServerToken');

    const creds = await this.config.read(serverId);
    const expected = creds.companion?.token;
    if (!expected) {
      throw new ForbiddenException('mc.err.companionNotConfigured');
    }
    if (!constantTimeEquals(provided, expected)) {
      this.logger.warn(`Неверный токен companion-плагина для сервера ${serverId}`);
      throw new ForbiddenException('mc.err.badServerToken');
    }
    return true;
  }
}
