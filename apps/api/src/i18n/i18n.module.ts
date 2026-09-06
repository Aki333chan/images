import { Global, Module } from '@nestjs/common';
import { I18nService } from './i18n.service';

/**
 * Глобальный: переводы нужны и контроллерам, и рассыльщику писем, и
 * фоновым задачам. Прописывать импорт в каждый модуль ради одного сервиса
 * без состояния — лишний шум.
 */
@Global()
@Module({
  providers: [I18nService],
  exports: [I18nService],
})
export class I18nModule {}
