process.env.NODE_ENV = 'test';

import { BadRequestException, HttpStatus, NotFoundException } from '@nestjs/common';
import { I18nExceptionFilter } from './i18n.filter';
import { I18nService } from './i18n.service';

/** Подставные объекты запроса и ответа: настоящего express здесь нет. */
function host(acceptLanguage?: string) {
  const sent: { status?: number; body?: unknown } = {};
  const response = {
    status(code: number) {
      sent.status = code;
      return this;
    },
    json(body: unknown) {
      sent.body = body;
    },
  };
  const request = {
    method: 'GET',
    url: '/api/test',
    headers: acceptLanguage ? { 'accept-language': acceptLanguage } : {},
  };
  return {
    sent,
    args: {
      switchToHttp: () => ({ getRequest: () => request, getResponse: () => response }),
    } as never,
  };
}

describe('перевод ошибок на краю', () => {
  const i18n = new I18nService();
  const filter = new I18nExceptionFilter(i18n);

  it('код ответа и форма тела не меняются', () => {
    const { sent, args } = host('ru');
    filter.catch(new NotFoundException('Игрок не найден'), args);

    expect(sent.status).toBe(404);
    expect(sent.body).toMatchObject({ statusCode: 404, message: 'Игрок не найден' });
  });

  it('текст, которого нет в словаре, доезжает как есть', () => {
    // Панель переводится по частям: непереведённый модуль должен продолжать
    // работать по-русски, а не показывать пустоту.
    const { sent, args } = host('pl');
    filter.catch(new NotFoundException('Пока не переведено'), args);

    expect((sent.body as { message: string }).message).toBe('Пока не переведено');
  });

  it('список сообщений от валидатора обрабатывается построчно', () => {
    const { sent, args } = host('en');
    filter.catch(new BadRequestException(['первое поле', 'второе поле']), args);

    expect((sent.body as { message: string[] }).message).toEqual(['первое поле', 'второе поле']);
  });

  it('чужое исключение не показывает человеку свой текст', () => {
    // В сообщении сбоя бывают пути, запросы и куски конфигурации — наружу
    // это не отдаём, в журнал пишем целиком.
    const { sent, args } = host('ru');
    filter.catch(new Error('connect ECONNREFUSED 10.0.0.2:3306 password=hunter2'), args);

    expect(sent.status).toBe(HttpStatus.INTERNAL_SERVER_ERROR);
    expect(JSON.stringify(sent.body)).not.toContain('hunter2');
  });

  it('язык берётся из Accept-Language, мусор в заголовке не ломает ответ', () => {
    expect(i18n.localeOf('pl-PL,pl;q=0.9,en;q=0.8')).toBe('pl');
    expect(i18n.localeOf(undefined)).toBe('ru');
    expect(i18n.localeOf(';;;')).toBe('ru');
  });
});
