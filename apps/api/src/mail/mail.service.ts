import { Injectable, Logger } from '@nestjs/common';
import { createTransport, type Transporter } from 'nodemailer';
import { SettingsService, type SmtpConfig } from '../settings/settings.service';
import { I18nService } from '../i18n/i18n.service';
import { welcomeMail, type WelcomeMailInput } from './mail-templates';

export interface SendResult {
  sent: boolean;
  /** Причина, пригодная для показа. Пароля здесь никогда нет. */
  error?: string;
}

/**
 * Отправка почты через обычный SMTP.
 *
 * Транспорт создаётся под каждую отправку, а не хранится: настройки меняются
 * из интерфейса, и закэшированный транспорт продолжал бы ходить на старый
 * сервер до перезапуска. Отправок здесь единицы в неделю, стоимость создания
 * соединения роли не играет.
 */
@Injectable()
export class MailService {
  private readonly logger = new Logger(MailService.name);

  constructor(
    private readonly settings: SettingsService,
    private readonly i18n: I18nService,
  ) {}

  async isConfigured(): Promise<boolean> {
    const config = await this.settings.getSmtpConfig();
    return !!config?.host && !!config.from;
  }

  private transport(config: SmtpConfig): Transporter {
    return createTransport({
      host: config.host,
      port: config.port,
      // secure=true — TLS с первого байта (обычно порт 465).
      // false — открытое соединение, дальше STARTTLS.
      secure: config.secure,
      // Требовать STARTTLS везде, кроме порта 25: в интерфейсе он подписан
      // «без шифрования» и нужен для почтового сервера на той же машине.
      // Требовать TLS там, где мы сами обещали обойтись без него, значит
      // отвергать корректную настройку с невнятной ошибкой.
      requireTLS: !config.secure && config.port !== 25,
      auth: config.user ? { user: config.user, pass: config.password } : undefined,
      connectionTimeout: 10_000,
      greetingTimeout: 10_000,
      socketTimeout: 20_000,
    });
  }

  /** Проверка настроек без отправки письма. */
  async verify(): Promise<SendResult> {
    const config = await this.settings.getSmtpConfig();
    if (!config?.host) return { sent: false, error: 'SMTP не настроен' };
    const transport = this.transport(config);
    try {
      await transport.verify();
      return { sent: true };
    } catch (e) {
      return { sent: false, error: describe(e) };
    } finally {
      transport.close();
    }
  }

  /**
   * Письмо с одноразовым паролем.
   *
   * Возвращает результат, а не бросает: неотправленное письмо не должно
   * отменять создание аккаунта — пароль в этом случае передают лично, и
   * интерфейс об этом прямо говорит.
   */
  async sendWelcome(to: string, input: WelcomeMailInput): Promise<SendResult> {
    const t = (key: string, values?: Record<string, string | number>) =>
      this.i18n.t(input.locale, key, values);
    return this.send(to, welcomeMail(input, t), 'письмо с доступом');
  }

  /**
   * Готовое письмо кому-то одному.
   *
   * Общий метод для писем, у которых нет секретов в теле, — алертов и всего,
   * что появится дальше. sendWelcome остаётся отдельным: там в теле
   * одноразовый пароль, и подпись в журнале должна об этом напоминать.
   */
  async sendTo(
    to: string,
    message: { subject: string; html: string; text: string },
    what = 'письмо',
  ): Promise<SendResult> {
    return this.send(to, message, what);
  }

  private async send(
    to: string,
    message: { subject: string; html: string; text: string },
    what: string,
  ): Promise<SendResult> {
    const config = await this.settings.getSmtpConfig();
    if (!config?.host || !config.from) {
      return { sent: false, error: 'SMTP не настроен — письмо не отправлено' };
    }

    const transport = this.transport(config);
    try {
      await transport.sendMail({ from: config.from, to, ...message });
      // В журнал — только адрес получателя и факт отправки. Содержимое письма
      // (в том числе одноразовый пароль) в логи не попадает никогда.
      this.logger.log(`Отправлено ${what} на ${to}`);
      return { sent: true };
    } catch (e) {
      const error = describe(e);
      this.logger.warn(`Не удалось отправить ${what} на ${to}: ${error}`);
      return { sent: false, error };
    } finally {
      transport.close();
    }
  }
}

/** Сообщение об ошибке SMTP без стека и без учётных данных. */
function describe(e: unknown): string {
  const error = e as { code?: string; responseCode?: number; message?: string };
  if (error.code === 'EAUTH') return 'SMTP отверг логин или пароль';
  if (error.code === 'ECONNECTION' || error.code === 'ESOCKET') {
    return 'Не удалось соединиться с SMTP-сервером — проверьте host, port и TLS';
  }
  if (error.code === 'ETIMEDOUT') return 'SMTP-сервер не ответил вовремя';
  return error.message ?? 'Неизвестная ошибка SMTP';
}
