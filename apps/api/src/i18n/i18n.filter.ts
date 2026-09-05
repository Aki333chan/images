import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { I18nService } from './i18n.service';

/**
 * Переводит текст ошибки на язык того, кто её увидит.
 *
 * ПОЧЕМУ НА КРАЮ, А НЕ В МЕСТЕ БРОСКА. Исключения летят из сервисов,
 * лежащих глубоко: чтобы каждый знал язык запроса, пришлось бы протащить
 * его через все конструкторы или сделать сервисы request-scoped. Здесь же
 * запрос под рукой — заголовок Accept-Language читается прямо из него.
 *
 * КАК ОТЛИЧАЕТСЯ КЛЮЧ ОТ ГОТОВОГО ТЕКСТА. Никак специально: ключ — это то,
 * что есть в русском словаре. Панель переводится по частям, и пока в
 * исключениях лежат и ключи вида «errors.user.notFound», и обычные русские
 * фразы. Фраза, которой в словаре нет, проходит насквозь и доезжает до
 * человека как есть. Значит непереведённый модуль продолжает работать
 * по-русски, а не показывает пустоту или своё же служебное имя.
 *
 * Форма ответа не меняется: те же поля statusCode/message/error, что и у
 * стандартного фильтра Nest, — панель разбирает их и не должна заметить
 * подмены.
 */
@Catch()
export class I18nExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(I18nExceptionFilter.name);

  constructor(private readonly i18n: I18nService) {}

  catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const request = http.getRequest<Request>();
    const response = http.getResponse<Response>();
    const locale = this.i18n.localeOf(request.headers['accept-language']);

    if (!(exception instanceof HttpException)) {
      // Не наше исключение — это ошибка в коде, а не в запросе. Текст
      // человеку не показываем: в нём бывают пути, запросы и куски
      // конфигурации. В журнал он попадает целиком.
      this.logger.error(
        `Необработанная ошибка на ${request.method} ${request.url}`,
        exception instanceof Error ? exception.stack : String(exception),
      );
      response.status(HttpStatus.INTERNAL_SERVER_ERROR).json({
        statusCode: HttpStatus.INTERNAL_SERVER_ERROR,
        message: this.i18n.t(locale, 'errors.internal'),
      });
      return;
    }

    const status = exception.getStatus();
    const body = exception.getResponse();

    if (typeof body === 'string') {
      response.status(status).json({ statusCode: status, message: this.translate(locale, body) });
      return;
    }

    const record = body as Record<string, unknown>;
    const message = record['message'];
    response.status(status).json({
      ...record,
      statusCode: status,
      // message бывает массивом: так class-validator отдаёт разбор тела
      // запроса, по строке на каждое непрошедшее поле.
      message: Array.isArray(message)
        ? message.map((item) => this.translate(locale, String(item)))
        : typeof message === 'string'
          ? this.translate(locale, message)
          : message,
    });
  }

  private translate(locale: Parameters<I18nService['t']>[0], text: string): string {
    return this.i18n.known(text) ? this.i18n.t(locale, text) : text;
  }
}
