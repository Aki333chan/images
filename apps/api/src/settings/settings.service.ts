import { Injectable } from '@nestjs/common';
import type { AppSettingsDto, SmtpSettingsDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { CryptoService } from '../common/crypto.service';

/** Ключи настроек. Строки попадают в БД — менять их без миграции нельзя. */
export const SETTING_KEYS = {
  REQUIRE_GM_APPROVAL: 'users.requireGmApprovalForAdminCreatedAccounts',
} as const;

/** Ключ, под которым лежит зашифрованный JSON с параметрами SMTP. */
export const SMTP_SECRET_KEY = 'smtp';

export interface SmtpConfig {
  host: string;
  port: number;
  secure: boolean;
  user: string;
  password: string;
  from: string;
}

/**
 * Настройки панели.
 *
 * Разделение намеренное: обычные настройки лежат открытым текстом в
 * app_settings, а всё, что содержит пароль, — зашифрованным в
 * integration_secrets, тем же механизмом, что ключи Pterodactyl и пароли RCON.
 * Класть пароль SMTP в общую таблицу настроек было бы понижением планки.
 */
@Injectable()
export class SettingsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
  ) {}

  async getAppSettings(): Promise<AppSettingsDto> {
    return {
      // По умолчанию включено: пока ГМ явно не разрешил обратное, аккаунты
      // от Админов проходят через его подтверждение.
      requireGmApprovalForAdminCreatedAccounts: await this.getBoolean(
        SETTING_KEYS.REQUIRE_GM_APPROVAL,
        true,
      ),
    };
  }

  async setRequireGmApproval(value: boolean): Promise<AppSettingsDto> {
    await this.prisma.appSetting.upsert({
      where: { key: SETTING_KEYS.REQUIRE_GM_APPROVAL },
      create: { key: SETTING_KEYS.REQUIRE_GM_APPROVAL, value: String(value) },
      update: { value: String(value) },
    });
    return this.getAppSettings();
  }

  private async getBoolean(key: string, fallback: boolean): Promise<boolean> {
    const row = await this.prisma.appSetting.findUnique({ where: { key } });
    if (!row) return fallback;
    return row.value === 'true';
  }

  // ---------------------------------------------------------------- SMTP

  /** Конфигурация для отправки; null — не настроена. */
  async getSmtpConfig(): Promise<SmtpConfig | null> {
    const row = await this.prisma.integrationSecret.findUnique({
      where: { key: SMTP_SECRET_KEY },
    });
    if (!row) return null;
    try {
      return JSON.parse(this.crypto.decrypt(row.valueEnc)) as SmtpConfig;
    } catch {
      // Ключ шифрования сменили — настройки нечитаемы; просим завести заново.
      return null;
    }
  }

  /** То же для показа в интерфейсе: пароль наружу не отдаётся никогда. */
  async getSmtpSettings(): Promise<SmtpSettingsDto> {
    const config = await this.getSmtpConfig();
    if (!config) {
      return {
        configured: false,
        host: '',
        port: 587,
        secure: false,
        user: '',
        from: '',
        hasPassword: false,
      };
    }
    return {
      configured: true,
      host: config.host,
      port: config.port,
      secure: config.secure,
      user: config.user,
      from: config.from,
      hasPassword: config.password.length > 0,
    };
  }

  /**
   * Сохранение настроек SMTP.
   *
   * Пустой пароль означает «оставить прежний»: интерфейс не показывает
   * сохранённый пароль, и требовать вводить его заново ради смены порта
   * было бы издевательством.
   */
  async setSmtpConfig(input: Omit<SmtpConfig, 'password'> & { password?: string }): Promise<
    SmtpSettingsDto
  > {
    const existing = await this.getSmtpConfig();
    const password = input.password?.length ? input.password : (existing?.password ?? '');
    const config: SmtpConfig = {
      host: input.host,
      port: input.port,
      secure: input.secure,
      user: input.user,
      password,
      from: input.from,
    };
    const valueEnc = this.crypto.encrypt(JSON.stringify(config));
    await this.prisma.integrationSecret.upsert({
      where: { key: SMTP_SECRET_KEY },
      create: { key: SMTP_SECRET_KEY, valueEnc },
      update: { valueEnc },
    });
    return this.getSmtpSettings();
  }
}
