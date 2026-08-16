import { Injectable, Logger } from '@nestjs/common';
import { createTransport, type Transporter } from 'nodemailer';
import { SettingsService, type SmtpConfig } from '../settings/settings.service';
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

  constructor(private readonly settings: SettingsService) {}

  async isConfigured(): Promise<boolean> {
    const config = await this.settings.getSmtpConfig();
    return !!config?.host && !!config.from;
  }

  private transport(config: SmtpConfig): Transporter {
    return createTransport({
      host: config.host,
      port: config.port,
      // secure=true — TLS с первого байта (обычно порт 465).
      // false — открытое соединение с обязательным STARTTLS (587).
      secure: config.secure,
      requireTLS: !config.secure,
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
    const config = await this.settings.getSmtpConfig();
    if (!config?.host || !config.from) {
      return { sent: false, error: 'SMTP не настроен — письмо не отправлено' };
    }

    const { subject, html, text } = welcomeMail(input);
    const transport = this.transport(config);
    try {
      await transport.sendMail({ from: config.from, to, subject, html, text });
      // В журнал — только адрес получателя и факт отправки. Пароль в логи
      // не попадает ни при каких условиях.
      this.logger.log(`Письмо с доступом отправлено на ${to}`);
      return { sent: true };
    } catch (e) {
      const error = describe(e);
      this.logger.warn(`Не удалось отправить письмо на ${to}: ${error}`);
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
