import { ServiceUnavailableException } from '@nestjs/common';

// undici подменяется до импорта клиента: настоящих сетевых запросов в тестах нет.
const requestMock = jest.fn();
jest.mock('undici', () => ({ request: (...args: unknown[]) => requestMock(...args) }));

import { pteroRequest } from './ptero-http';

/** Ответ в форме, которую отдаёт undici. */
function reply(statusCode: number, headers: Record<string, string>, body: string) {
  return { statusCode, headers, body: { text: async () => body } };
}

describe('pteroRequest', () => {
  beforeEach(() => requestMock.mockReset());

  it('разбирает нормальный JSON-ответ', async () => {
    requestMock.mockResolvedValue(
      reply(200, { 'content-type': 'application/json' }, '{"data":[{"id":1}]}'),
    );
    await expect(pteroRequest('key', 'GET', '/api/application/servers')).resolves.toEqual({
      data: [{ id: 1 }],
    });
  });

  it('на 204 возвращает undefined и не трогает тело', async () => {
    requestMock.mockResolvedValue(reply(204, {}, ''));
    await expect(
      pteroRequest('key', 'DELETE', '/api/application/servers/1'),
    ).resolves.toBeUndefined();
  });

  // Регрессия: раньше 3xx проскакивал мимо проверки >= 400, и HTML-страница
  // редиректа nginx уходила в JSON.parse — «Unexpected token '<'».
  it('на редирект даёт понятную ошибку, а не сбой разбора JSON', async () => {
    requestMock.mockResolvedValue(
      reply(
        301,
        { location: 'https://127.0.0.1/api/application/servers', 'content-type': 'text/html' },
        '<html>\n<head><title>301 Moved Permanently</title></head>\n</html>',
      ),
    );

    const err = (await pteroRequest('key', 'GET', '/api/application/servers').catch(
      (e: Error) => e,
    )) as Error;

    expect(err).toBeInstanceOf(ServiceUnavailableException);
    expect(err.message).toContain('301');
    expect(err.message).toContain('PTERO_BASE_URL');
    expect(err.message).toContain('https://127.0.0.1/api/application/servers');
    expect(err.message).not.toContain('JSON.parse');
  });

  it('на успешный HTML-ответ сообщает про content-type, а не падает на разборе', async () => {
    requestMock.mockResolvedValue(
      reply(200, { 'content-type': 'text/html; charset=UTF-8' }, '<!DOCTYPE html><html>...'),
    );

    const err = (await pteroRequest('key', 'GET', '/api/application/servers').catch(
      (e: Error) => e,
    )) as Error;

    expect(err).toBeInstanceOf(ServiceUnavailableException);
    expect(err.message).toContain('text/html');
    expect(err.message).toContain('PTERO_BASE_URL');
  });

  it('на битый JSON с правильным content-type сообщает об этом отдельно', async () => {
    requestMock.mockResolvedValue(reply(200, { 'content-type': 'application/json' }, '{"data":'));

    const err = (await pteroRequest('key', 'GET', '/api/application/servers').catch(
      (e: Error) => e,
    )) as Error;

    expect(err).toBeInstanceOf(ServiceUnavailableException);
    expect(err.message).toContain('не разбирается');
  });

  it('на 4xx показывает статус и начало тела', async () => {
    requestMock.mockResolvedValue(
      reply(403, { 'content-type': 'application/json' }, '{"errors":[{"code":"Forbidden"}]}'),
    );

    const err = (await pteroRequest('key', 'GET', '/api/application/servers').catch(
      (e: Error) => e,
    )) as Error;

    expect(err).toBeInstanceOf(ServiceUnavailableException);
    expect(err.message).toContain('403');
    expect(err.message).toContain('Forbidden');
  });

  // Ключ передаётся заголовком и не должен попадать в текст ошибки:
  // сообщения уходят в ответ API и в журнал.
  it('не раскрывает API-ключ в сообщениях об ошибках', async () => {
    const secret = 'ptla_ochenSekretnyKluch';
    requestMock.mockResolvedValue(reply(301, { location: 'https://x/' }, '<html>'));

    const err = (await pteroRequest(secret, 'GET', '/api/application/servers').catch(
      (e: Error) => e,
    )) as Error;

    expect(err.message).not.toContain(secret);
    expect(requestMock.mock.calls[0][1].headers.authorization).toBe(`Bearer ${secret}`);
  });
});
