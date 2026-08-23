import { ServiceUnavailableException } from '@nestjs/common';
import { request } from 'undici';
import { env } from '../config/env';

/**
 * Тонкий HTTP-клиент к Pterodactyl Panel (localhost/внутренний адрес).
 * Эндпоинты сверены с исходниками panel 1.0-develop
 * (routes/api-application.php, routes/api-client.php).
 */
export async function pteroRequest<T>(
  apiKey: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<T> {
  const url = `${env.PTERO_BASE_URL.replace(/\/$/, '')}${path}`;
  const res = await request(url, {
    method,
    headers: {
      authorization: `Bearer ${apiKey}`,
      accept: 'application/json',
      'content-type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.statusCode === 204) return undefined as T;
  const text = await res.body.text();

  // Редиректы undici сам не следует, а 3xx не попадает в проверку >= 400 ниже.
  // Без этой ветки тело редиректа (HTML-заглушка nginx) уходило в JSON.parse,
  // и вместо понятной причины получалась ошибка «Unexpected token '<'».
  // Типичный случай: PTERO_BASE_URL начинается с http://, а nginx панели
  // принудительно уводит на https. Следовать такому редиректу нельзя:
  // он ведёт на тот же адрес по https, а сертификат выписан на домен, не на IP.
  if (res.statusCode >= 300 && res.statusCode < 400) {
    const location = res.headers.location;
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path}: ответ ${res.statusCode} (редирект на ${
        typeof location === 'string' ? location : 'адрес не указан'
      }). Проверьте PTERO_BASE_URL (сейчас ${env.PTERO_BASE_URL}) — обычно нужен https и доменное имя панели.`,
    );
  }

  if (res.statusCode >= 400) {
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path} -> ${res.statusCode}: ${text.slice(0, 300)}`,
    );
  }

  if (!text) return undefined as T;

  // Успешный ответ, но не JSON — значит запрос попал не в API, а на страницу
  // панели. Сообщаем об этом прямо, а не через сбой разбора.
  const contentType = String(res.headers['content-type'] ?? '');
  if (!contentType.includes('json')) {
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path}: вместо JSON вернулся «${
        contentType || 'ответ без content-type'
      }» со статусом ${res.statusCode}. Проверьте PTERO_BASE_URL (сейчас ${
        env.PTERO_BASE_URL
      }). Начало ответа: ${text.slice(0, 200)}`,
    );
  }

  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path}: ответ помечен как JSON, но не разбирается (статус ${
        res.statusCode
      }). Начало ответа: ${text.slice(0, 200)}`,
    );
  }
}

/**
 * Запрос с сырым телом — для загрузки файлов.
 *
 * Отдельно от pteroRequest: тот отправляет JSON, а маршрут files/write ждёт
 * само содержимое файла в теле. Для .jar это единственный способ положить
 * бинарник, и content-type тут не application/json.
 */
export async function pteroRawRequest(
  apiKey: string,
  method: 'POST' | 'PUT',
  path: string,
  body: Buffer,
): Promise<void> {
  const url = `${env.PTERO_BASE_URL.replace(/\/$/, '')}${path}`;
  const res = await request(url, {
    method,
    headers: {
      authorization: `Bearer ${apiKey}`,
      accept: 'application/json',
      'content-type': 'application/octet-stream',
      'content-length': String(body.length),
    },
    body,
    // Файл может быть в десятки мегабайт, а Wings пишет его на диск.
    headersTimeout: 120_000,
    bodyTimeout: 120_000,
  });

  if (res.statusCode >= 400) {
    const text = await res.body.text();
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path} -> ${res.statusCode}: ${text.slice(0, 300)}`,
    );
  }
  // Тело ответа читаем всегда: неосвобождённое соединение undici не переиспользует.
  await res.body.text().catch(() => undefined);
}

/**
 * GET, возвращающий тело как есть.
 *
 * Нужен для двух маршрутов Pterodactyl, которые отвечают НЕ JSON:
 * files/contents отдаёт содержимое файла с типом text/plain, а скачивание с
 * Wings — поток байтов. Общий pteroRequest такой ответ отвергает намеренно
 * (не-JSON там означает, что запрос попал не в API, а на страницу панели),
 * поэтому для них отдельная функция, а не послабление в общей.
 *
 * Размер ограничивается: файлом может оказаться что угодно, вплоть до
 * мировых данных, и втянуть его целиком в память бэкенда нельзя.
 */
export async function pteroRawGet(
  apiKey: string,
  path: string,
  maxBytes: number,
): Promise<{ body: Buffer; truncated: boolean; contentType: string }> {
  const url = `${env.PTERO_BASE_URL.replace(/\/$/, '')}${path}`;
  const res = await request(url, {
    method: 'GET',
    headers: { authorization: `Bearer ${apiKey}`, accept: '*/*' },
    headersTimeout: 60_000,
    bodyTimeout: 120_000,
  });

  if (res.statusCode >= 400) {
    const text = await res.body.text();
    throw new ServiceUnavailableException(
      `Pterodactyl API GET ${path} -> ${res.statusCode}: ${text.slice(0, 300)}`,
    );
  }

  const chunks: Buffer[] = [];
  let size = 0;
  let truncated = false;
  for await (const chunk of res.body) {
    const buf = Buffer.from(chunk);
    if (size + buf.length > maxBytes) {
      chunks.push(buf.subarray(0, maxBytes - size));
      truncated = true;
      // Соединение рвём сразу: дочитывать гигабайт, который всё равно
      // выбросим, значит зря занимать и сеть, и Wings.
      res.body.destroy();
      break;
    }
    chunks.push(buf);
    size += buf.length;
  }

  return {
    body: Buffer.concat(chunks),
    truncated,
    contentType: String(res.headers['content-type'] ?? 'application/octet-stream'),
  };
}
