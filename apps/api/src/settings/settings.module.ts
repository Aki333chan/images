import { Global, Module } from '@nestjs/common';
import { SettingsController } from './settings.controller';
import { SettingsService } from './settings.service';
import { MailService } from '../mail/mail.service';

/**
 * Настройки и почта в одном модуле: MailService без SettingsService
 * бесполезен, а разносить их значило бы плодить импорты ради формальности.
 *
 * Global — чтобы users-модуль мог отправлять письма, не импортируя ничего:
 * это ровно тот случай, для которого @Global и предназначен (сквозная
 * инфраструктурная возможность без обратных связей).
 */
@Global()
@Module({
  controllers: [SettingsController],
  providers: [SettingsService, MailService],
  exports: [SettingsService, MailService],
})
export class SettingsModule {}
