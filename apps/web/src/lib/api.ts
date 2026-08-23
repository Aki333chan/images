/**
 * API-клиент: access-токен хранится только в памяти; при 401 делается
 * одна попытка /auth/refresh (httpOnly-cookie) и повтор запроса.
 */
let accessToken: string | null = null;
let onSessionExpired: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler;
}

async function rawFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'content-type': 'application/json',
      ...(accessToken ? { authorization: `Bearer ${accessToken}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
}

export async function tryRefresh(): Promise<boolean> {
  const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
  if (!res.ok) return false;
  const data = (await res.json()) as { accessToken: string };
  accessToken = data.accessToken;
  return true;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let res = await rawFetch(path, init);
  if (res.status === 401 && !path.startsWith('/api/auth/login') && !path.startsWith('/api/auth/2fa')) {
    if (await tryRefresh()) {
      res = await rawFetch(path, init);
    } else {
      onSessionExpired?.();
    }
  }
  if (!res.ok) {
    let message = `Ошибка ${res.status}`;
    try {
      const body = await res.json();
      message = Array.isArray(body.message) ? body.message.join(', ') : (body.message ?? message);
    } catch {
      // тело не JSON
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/**
 * Запрос с сырым телом — содержимое файла.
 *
 * Отдельно от api(): тот шлёт JSON, а файл в JSON заворачивать незачем —
 * экранирование раздуло бы бинарник, а разбор мегабайтной строки это лишняя
 * работа на ровном месте. Заголовок content-type здесь обязателен: без него
 * бэкенд не разберёт тело и запишет пустой файл поверх конфига.
 */
export async function apiRaw<T>(path: string, body: Blob | File): Promise<T> {
  return api<T>(path, {
    method: 'POST',
    body,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

/**
 * Скачивание файла через API.
 *
 * Простой ссылкой это сделать нельзя: доступ подтверждается токеном в
 * памяти вкладки, а обычная навигация его не отправит и получит 401.
 * Поэтому забираем содержимое запросом и отдаём браузеру уже готовый blob.
 */
export async function apiDownload(path: string, fileName: string): Promise<void> {
  const res = await rawFetch(path);
  if (!res.ok) {
    let message = `Ошибка ${res.status}`;
    try {
      const body = (await res.json()) as { message?: string | string[] };
      message = Array.isArray(body.message) ? body.message.join(', ') : (body.message ?? message);
    } catch {
      // тело не JSON
    }
    throw new ApiError(res.status, message);
  }

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Освобождаем сразу: объект держит blob в памяти вкладки до перезагрузки.
  URL.revokeObjectURL(url);
}
