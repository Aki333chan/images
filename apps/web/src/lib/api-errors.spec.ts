import { ApiError, api } from './api';
import { formatTransferLimit } from '@aurum/shared';

/**
 * Текст ошибки, когда отвечал НЕ бэкенд.
 *
 * До бэкенда стоит nginx, и на слишком большом теле он отвечает сам —
 * HTML-страницей. Разбор JSON тогда не удаётся, и раньше в интерфейс уходило
 * голое «Ошибка 413», из которого человеку не понять ни предела, ни что
 * делать. Именно так и выглядела жалоба: файл не грузится, в панели 413.
 */
describe('сообщения об ошибках API', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  function respondWith(status: number, body: string, contentType: string) {
    global.fetch = jest.fn(async () =>
      new Response(body, { status, headers: { 'content-type': contentType } }),
    ) as unknown as typeof fetch;
  }

  it('413 от прокси объясняет предел и куда смотреть', async () => {
    respondWith(413, '<html><head><title>413 Request Entity Too Large</title></head></html>', 'text/html');

    await expect(api('/api/servers/x/files/upload')).rejects.toThrow(ApiError);
    await expect(api('/api/servers/x/files/upload')).rejects.toThrow(
      new RegExp(`${formatTransferLimit()}`),
    );
    await expect(api('/api/servers/x/files/upload')).rejects.toThrow(/client_max_body_size/);
  });

  it('свой текст бэкенда важнее нашей заготовки', async () => {
    // У бэкенда есть собственная формулировка на тот же код — она точнее,
    // потому что он знает, какой именно файл и насколько велик.
    respondWith(413, JSON.stringify({ message: 'Файл больше 64 МиБ — загрузите по SFTP' }), 'application/json');

    await expect(api('/api/servers/x/files/upload')).rejects.toThrow(/загрузите по SFTP/);
  });

  it('502 и 504 не превращаются в загадочный номер', async () => {
    respondWith(502, '<html>502 Bad Gateway</html>', 'text/html');
    await expect(api('/api/servers')).rejects.toThrow(/не ответила вовремя/);
  });

  it('остальные коды остаются как есть — выдумывать нечего', async () => {
    respondWith(418, 'нечто', 'text/plain');
    await expect(api('/api/servers')).rejects.toThrow('Ошибка 418');
  });

  it('код ответа доезжает до вызывающего вместе с текстом', async () => {
    respondWith(413, '<html>413</html>', 'text/html');
    await expect(api('/api/x')).rejects.toMatchObject({ status: 413 });
  });
});
