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
