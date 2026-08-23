import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import { SevenDaysConfigService } from './sevendays-config.service';

/**
 * Исходящее направление: панель → companion-мод.
 *
 * Мод слушает внутри приватного туннеля, и его адрес с токеном не покидают
 * бэкенд — как и пароль telnet-консоли. Порт мода наружу не выставляется:
 * он такой же чувствительный, как RCON.
 *
 * Мод не обязателен. Поэтому каждый метод здесь делится на два вида:
 * `require…` — вызывающий знает, что мод нужен, и хочет внятную ошибку;
 * `try…` — вызывающему достаточно «не получилось», потому что без мода
 * панель просто показывает меньше.
 */
@Injectable()
export class SevenDaysCompanionService {
  private readonly logger = new Logger(SevenDaysCompanionService.name);

  /** Мод отвечает мгновенно — он в той же сети и ничего не считает. */
  private static readonly TIMEOUT_MS = 4000;

  constructor(private readonly config: SevenDaysConfigService) {}

  /**
   * Проверка связи: жив ли мод и какой у него контракт.
   *
   * Возвращает null вместо исключения, потому что «мода нет» — это обычное
   * состояние, а не ошибка: модуль рассчитан на голый сервер.
   */
  async ping(serverId: string): Promise<{ version: string; contract: string } | null> {
    try {
      const body = await this.call<{ version?: string; contract?: string }>(serverId, 'GET', '/ping');
      return { version: body.version ?? '?', contract: body.contract ?? '?' };
    } catch (e) {
      this.logger.debug(`Companion не ответил: ${(e as Error).message}`);
      return null;
    }
  }

  /**
   * Состояние мира от самой игры.
   *
   * Ради двух полей это и нужно: идёт ли кровавая луна на самом деле (панель
   * без мода вынуждена считать «день кратен семи», хотя частота
   * настраивается) и FPS сервера.
   */
  async state(serverId: string): Promise<CompanionWorldState | null> {
    try {
      return await this.call<CompanionWorldState>(serverId, 'GET', '/state');
    } catch {
      return null;
    }
  }

  /**
   * Личное сообщение игроку в игровой чат.
   *
   * Ванильная консоль 7 Days to Die этого не умеет вовсе — есть только say
   * на весь сервер. Ответ модератора на жалобу иначе пришлось бы зачитывать
   * всему серверу.
   *
   * `delivered: false` означает «игрок не в сети» — это не ошибка: модератор
   * отвечает, когда удобно ему.
   */
  async sendPrivateMessage(serverId: string, playerId: string, text: string): Promise<boolean> {
    const body = await this.call<{ delivered?: boolean }>(
      serverId,
      'POST',
      `/players/${encodeURIComponent(playerId)}/message`,
      { text },
    );
    return body.delivered === true;
  }

  private async call<T>(
    serverId: string,
    method: 'GET' | 'POST',
    path: string,
    payload?: unknown,
  ): Promise<T> {
    const creds = await this.config.readCompanion(serverId);
    const url = `http://${creds.host}:${creds.port}${path}`;

    let response;
    try {
      response = await request(url, {
        method,
        headers: {
          authorization: `Bearer ${creds.token}`,
          ...(payload === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: payload === undefined ? undefined : JSON.stringify(payload),
        headersTimeout: SevenDaysCompanionService.TIMEOUT_MS,
        bodyTimeout: SevenDaysCompanionService.TIMEOUT_MS,
      });
    } catch {
      // В тексте ошибки undici бывает адрес — а он приватный и секретный,
      // поэтому саму ошибку наружу не пускаем и не связываем с ответом.
      throw new BadRequestException('Companion-мод не отвечает');
    }

    const text = await response.body.text();
    if (response.statusCode >= 400) {
      // Причину мода показываем как есть: она написана для человека.
      const reason = safeMessage(text) ?? `код ${response.statusCode}`;
      throw new BadRequestException(`Companion-мод отказал: ${reason}`);
    }

    await this.config.markCompanionSeen(serverId);
    return (text ? JSON.parse(text) : {}) as T;
  }
}

/** Состояние мира, как его отдаёт мод. */
export interface CompanionWorldState {
  day: number;
  hour: number;
  minute: number;
  /** Идёт ли орда прямо сейчас — факт от игры, а не расчёт по номеру дня. */
  bloodMoonActive: boolean;
  /** Частота орды из настроек сервера. null — сервер не сказал. */
  bloodMoonFrequency: number | null;
  fps: number;
  zombies: number;
  maxZombies: number;
  animals: number;
  onlinePlayers: number;
  maxPlayers: number;
  version: string | null;
}

/** Достаёт поле error из ответа мода, не падая на не-JSON. */
function safeMessage(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { error?: string };
    return typeof parsed.error === 'string' ? parsed.error : null;
  } catch {
    return null;
  }
}
