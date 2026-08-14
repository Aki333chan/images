import { Injectable, Logger } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import {
  OnGatewayConnection,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import {
  PermissionsUpdatedPayload,
  TicketsUpdatedPayload,
  WS_EVENTS,
} from '@aurum/shared';
import { env } from '../config/env';
import { AccessTokenPayload } from '../auth/jwt-auth.guard';

/**
 * Единый WS-канал панели. Аутентификация — access-токен в handshake.auth.token.
 * Каждый клиент попадает в комнату user:<id>; адресные события (permissions.updated)
 * шлются в комнату пользователя, широковещательные (tickets.updated) — всем:
 * событие лишь сигнал «перезапроси данные», сами данные клиент получает по REST
 * со своими правами.
 *
 * `path` (а не namespace) задаёт именно HTTP-путь эндпоинта: по умолчанию
 * socket.io стучится в /socket.io/, и его пришлось бы отдельно проксировать.
 * С path: '/ws' достаточно одного правила прокси на /ws — и в vite.config.ts,
 * и в nginx (см. README).
 */
@Injectable()
@WebSocketGateway({
  path: '/ws',
  cors: { origin: env.WEB_ORIGIN, credentials: true },
})
export class EventsGateway implements OnGatewayConnection {
  private readonly logger = new Logger(EventsGateway.name);

  @WebSocketServer()
  server!: Server;

  constructor(private readonly jwt: JwtService) {}

  async handleConnection(client: Socket) {
    try {
      const token: string | undefined = client.handshake.auth?.token;
      if (!token) throw new Error('нет токена');
      const payload = await this.jwt.verifyAsync<AccessTokenPayload>(token, {
        secret: env.JWT_ACCESS_SECRET,
      });
      if (payload.purpose !== 'access') throw new Error('неверный тип токена');
      await client.join(`user:${payload.sub}`);
    } catch (e) {
      this.logger.debug(`WS-подключение отклонено: ${(e as Error).message}`);
      client.disconnect(true);
    }
  }

  emitPermissionsUpdated(userId: string, payload: PermissionsUpdatedPayload) {
    this.server.to(`user:${userId}`).emit(WS_EVENTS.PERMISSIONS_UPDATED, payload);
  }

  emitTicketsUpdated(payload: TicketsUpdatedPayload) {
    this.server.emit(WS_EVENTS.TICKETS_UPDATED, payload);
  }
}
