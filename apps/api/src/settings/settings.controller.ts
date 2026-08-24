import { Body, Controller, Get, Post, Put } from '@nestjs/common';
import { IsBoolean, IsEmail, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';
import {
  ALERT_SETTINGS_LIMITS,
  type AlertSettingsDto,
  type AppSettingsDto,
  type SmtpSettingsDto,
  type SmtpTestResultDto,
} from '@aurum/shared';
import { RequirePermission } from '../rbac/rbac.decorators';
import { SettingsService } from './settings.service';
import { MailService } from '../mail/mail.service';
import { AuditRedactBody } from '../audit/audit.decorators';

class AppSettingsPatchDto {
  @IsBoolean()
  requireGmApprovalForAdminCreatedAccounts!: boolean;
}

/** Экспортируется ради теста валидации: см. settings.dto.spec.ts. */
export class SmtpPatchDto {
  @IsString()
  @MaxLength(255)
  host!: string;

  @IsInt()
  @Min(1)
  @Max(65535)
  port!: number;

  /** true — TLS с первого байта (465); false — STARTTLS (587). */
  @IsBoolean()
  secure!: boolean;

  @IsString()
  @MaxLength(255)
  user!: string;

  /** Пусто — оставить сохранённый пароль. */
  @IsOptional()
  @IsString()
  @MaxLength(255)
  password?: string;

  /**
   * Адрес отправителя. Допускается и голый адрес, и форма с именем —
   * «Aurum Panel <panel@aurumgg.ovh>»: именно её советуют интерфейс и
   * инструкция, и именно её понимает nodemailer. Без allow_display_name
   * такое значение отвергалось бы как «from must be an email».
   */
  @IsEmail({ allow_display_name: true })
  from!: string;
}

/**
 * Пороги алертов о перегрузке.
 *
 * Пороги — в процентах ОТ ЛИМИТА сервера, а не от абстрактных 100: сырой
 * процент CPU у Pterodactyl сравнивать не с чем, см. resources.ts.
 * null означает «по этому ресурсу не следим» — это осмысленный выбор, и
 * поэтому поле nullable, а не просто необязательное.
 */
class AlertSettingsPatchDto {
  @IsBoolean()
  enabled!: boolean;

  @IsOptional()
  @IsInt()
  @Min(ALERT_SETTINGS_LIMITS.minThreshold)
  @Max(ALERT_SETTINGS_LIMITS.maxThreshold)
  cpuThresholdPercent!: number | null;

  @IsOptional()
  @IsInt()
  @Min(ALERT_SETTINGS_LIMITS.minThreshold)
  @Max(ALERT_SETTINGS_LIMITS.maxThreshold)
  memoryThresholdPercent!: number | null;

  @IsInt()
  @Min(ALERT_SETTINGS_LIMITS.minSustainedMinutes)
  @Max(ALERT_SETTINGS_LIMITS.maxSustainedMinutes)
  sustainedMinutes!: number;

  @IsInt()
  @Min(ALERT_SETTINGS_LIMITS.minCooldownMinutes)
  @Max(ALERT_SETTINGS_LIMITS.maxCooldownMinutes)
  cooldownMinutes!: number;
}

/**
 * Настройки панели — только для ГМ (users.manage есть только у него).
 * Пароль SMTP наружу не отдаётся ни в одном ответе.
 */
@Controller('settings')
@RequirePermission('users.manage')
export class SettingsController {
  constructor(
    private readonly settings: SettingsService,
    private readonly mail: MailService,
  ) {}

  @Get()
  app(): Promise<AppSettingsDto> {
    return this.settings.getAppSettings();
  }

  @Put()
  updateApp(@Body() dto: AppSettingsPatchDto): Promise<AppSettingsDto> {
    return this.settings.setRequireGmApproval(dto.requireGmApprovalForAdminCreatedAccounts);
  }

  @Get('smtp')
  smtp(): Promise<SmtpSettingsDto> {
    return this.settings.getSmtpSettings();
  }

  @Put('smtp')
  @AuditRedactBody() // в теле пароль от почтового ящика
  updateSmtp(@Body() dto: SmtpPatchDto): Promise<SmtpSettingsDto> {
    return this.settings.setSmtpConfig(dto);
  }

  /** Проверка соединения без отправки письма. */
  @Get('alerts')
  alerts(): Promise<AlertSettingsDto> {
    return this.settings.getAlertSettings();
  }

  @Put('alerts')
  setAlerts(@Body() dto: AlertSettingsPatchDto): Promise<AlertSettingsDto> {
    return this.settings.setAlertSettings(dto);
  }

  @Post('smtp/test')
  async testSmtp(): Promise<SmtpTestResultDto> {
    const result = await this.mail.verify();
    return { ok: result.sent, ...(result.error ? { error: result.error } : {}) };
  }
}
