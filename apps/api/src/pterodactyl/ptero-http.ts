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
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
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
  if (res.statusCode >= 400) {
    throw new ServiceUnavailableException(
      `Pterodactyl API ${method} ${path} -> ${res.statusCode}: ${text.slice(0, 300)}`,
    );
  }
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
