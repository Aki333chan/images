import { Injectable } from '@nestjs/common';
import {
  ALERT_SETTINGS_LIMITS,
  DEFAULT_ALERT_SETTINGS,
  type AlertSettingsDto,
  type AppSettingsDto,
  type SmtpSettingsDto,
} from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { CryptoService } from '../common/crypto.service';

/** Ключи настроек. Строки попадают в БД — менять их без миграции нельзя. */
export const SETTING_KEYS = {
  REQUIRE_GM_APPROVAL: 'users.requireGmApprovalForAdminCreatedAccounts',
  /**
   * Настройки алертов о перегрузке — одним JSON под одним ключом.
   *
   * Одним, а не пятью полями: они меняются вместе (включил алерты — задал
   * пороги и задержку) и читаются вместе кроном на каждом тике. Пять
   * отдельных ключей означали бы пять запросов и возможность сохранить
   * половину настроек.
   */
  ALERTS: 'alerts.overload',
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

  // -------------------------------------------------------------- Алерты

  /**
   * Настройки алертов. Незаданные и битые значения заменяются дефолтами:
   * крон ходит сюда каждые полминуты и падать из-за кривой строки в базе не
   * должен — тогда встанут и алерты, и сбор метрик для списка серверов.
   */
  async getAlertSettings(): Promise<AlertSettingsDto> {
    const row = await this.prisma.appSetting.findUnique({
      where: { key: SETTING_KEYS.ALERTS },
    });
    if (!row) return { ...DEFAULT_ALERT_SETTINGS };
    try {
      return normalizeAlertSettings(JSON.parse(row.value) as Partial<AlertSettingsDto>);
    } catch {
      return { ...DEFAULT_ALERT_SETTINGS };
    }
  }

  async setAlertSettings(input: Partial<AlertSettingsDto>): Promise<AlertSettingsDto> {
    const value = normalizeAlertSettings(input);
    await this.prisma.appSetting.upsert({
      where: { key: SETTING_KEYS.ALERTS },
      create: { key: SETTING_KEYS.ALERTS, value: JSON.stringify(value) },
      update: { value: JSON.stringify(value) },
    });
    return value;
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

/**
 * Приведение настроек алертов к допустимому виду.
 *
 * Экспортируется ради тестов: сюда приезжает и пользовательский ввод, и то,
 * что когда-то записали в базу, и молча принять порог в 5000% значило бы
 * выключить алерты, ничего об этом не сказав.
 */
export function normalizeAlertSettings(input: Partial<AlertSettingsDto>): AlertSettingsDto {
  const L = ALERT_SETTINGS_LIMITS;
  return {
    enabled: input.enabled ?? DEFAULT_ALERT_SETTINGS.enabled,
    // null означает «по этому ресурсу не следим» и сохраняется как есть —
    // это осмысленный выбор, а не отсутствие значения.
    cpuThresholdPercent: clampOrNull(input.cpuThresholdPercent, L.minThreshold, L.maxThreshold),
    memoryThresholdPercent: clampOrNull(
      input.memoryThresholdPercent,
      L.minThreshold,
      L.maxThreshold,
    ),
    sustainedMinutes: clamp(
      input.sustainedMinutes ?? DEFAULT_ALERT_SETTINGS.sustainedMinutes,
      L.minSustainedMinutes,
      L.maxSustainedMinutes,
    ),
    cooldownMinutes: clamp(
      input.cooldownMinutes ?? DEFAULT_ALERT_SETTINGS.cooldownMinutes,
      L.minCooldownMinutes,
      L.maxCooldownMinutes,
    ),
  };
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(Math.max(Math.round(value), min), max);
}

function clampOrNull(value: number | null | undefined, min: number, max: number): number | null {
  if (value === null || value === undefined) return null;
  return clamp(value, min, max);
}
