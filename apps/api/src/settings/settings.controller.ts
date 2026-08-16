import { Body, Controller, Get, Post, Put } from '@nestjs/common';
import { IsBoolean, IsEmail, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';
import type { AppSettingsDto, SmtpSettingsDto, SmtpTestResultDto } from '@aurum/shared';
import { RequirePermission } from '../rbac/rbac.decorators';
import { SettingsService } from './settings.service';
import { MailService } from '../mail/mail.service';
import { AuditRedactBody } from '../audit/audit.decorators';

class AppSettingsPatchDto {
  @IsBoolean()
  requireGmApprovalForAdminCreatedAccounts!: boolean;
}

class SmtpPatchDto {
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

  @IsEmail()
  from!: string;
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
  @Post('smtp/test')
  async testSmtp(): Promise<SmtpTestResultDto> {
    const result = await this.mail.verify();
    return { ok: result.sent, ...(result.error ? { error: result.error } : {}) };
  }
}
