import { BadGatewayException, Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import { PALWORLD_API_USER, PalworldConfigService } from './palworld-config.service';

/**
 * Клиент REST API Palworld.
 *
 * Транспорт: HTTP + JSON, Basic-аутентификация (логин всегда `admin`).
 * HTTPS сервер не умеет — поэтому ходить к нему можно только по приватному
 * адресу через туннель, ровно как к RCON и companion-плагину: пароль
 * администратора уходит в заголовке практически открытым текстом.
 *
 * Ни адрес, ни пароль не попадают ни в ответы API панели, ни в логи —
 * в сообщениях остаются только код ответа и id сервера.
 */
@Injectable()
export class PalworldApiService {
  private readonly logger = new Logger(PalworldApiService.name);

  constructor(private readonly config: PalworldConfigService) {}

  async isConfigured(serverId: string): Promise<boolean> {
    return !!(await this.config.read(serverId)).palworld;
  }

  /**
   * Запрос к серверу. Бросает BadGateway с текстом, пригодным для показа:
   * человек должен понять, что чинить, не заглядывая в журнал.
   */
  private async call<T>(
    serverId: string,
    path: string,
    init?: { method?: 'GET' | 'POST'; body?: unknown },
  ): Promise<T> {
    const creds = await this.config.require(serverId);
    const method = init?.method ?? 'GET';

    let response;
    try {
      response = await request(`${creds.baseUrl}/v1/api${path}`, {
        method,
        headers: {
          authorization: basicAuth(PALWORLD_API_USER, creds.adminPassword),
          accept: 'application/json',
          ...(init?.body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: init?.body === undefined ? undefined : JSON.stringify(init.body),
        headersTimeout: 6000,
        bodyTimeout: 6000,
      });
    } catch (e) {
      // Адреса в сообщении нет намеренно — он секретный.
      this.logger.warn(`Palworld API сервера ${serverId} недоступен: ${(e as Error).message}`);
      throw new BadGatewayException(
        'Сервер Palworld не отвечает. Проверьте, что он запущен, что в PalWorldSettings.ini ' +
          'стоит RESTAPIEnabled=True и что порт REST API открыт в приватной сети.',
      );
    }

    const text = await response.body.text();

    if (response.statusCode === 401) {
      throw new BadGatewayException(
        'Сервер Palworld отклонил пароль администратора. Сверьте AdminPassword ' +
          'в PalWorldSettings.ini с тем, что задан в настройках модуля.',
      );
    }
    if (response.statusCode >= 400) {
      this.logger.warn(`Palworld API сервера ${serverId} ответил ${response.statusCode}`);
      throw new BadGatewayException(
        `Сервер Palworld ответил ошибкой ${response.statusCode}. ` +
          'Возможно, версия сервера не поддерживает эту команду.',
      );
    }

    await this.config.markSeen(serverId).catch(() => undefined);

    // Действия (kick/ban/announce) отвечают пустым телом или простым текстом —
    // это успех, а не повод падать при разборе JSON.
    if (!text.trim()) return {} as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      return {} as T;
    }
  }

  get<T>(serverId: string, path: string): Promise<T> {
    return this.call<T>(serverId, path);
  }

  post<T = unknown>(serverId: string, path: string, body?: unknown): Promise<T> {
    return this.call<T>(serverId, path, { method: 'POST', body });
  }
}

/** Basic-заголовок. Отдельной функцией — чтобы было одно место со сборкой. */
function basicAuth(user: string, password: string): string {
  return `Basic ${Buffer.from(`${user}:${password}`, 'utf8').toString('base64')}`;
}
