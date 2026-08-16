import { config as loadDotenv } from 'dotenv';

// .env лежит рядом с package.json приложения; в проде переменные обычно
// приходят из systemd/окружения — тогда файла просто нет и это не ошибка.
loadDotenv();

/**
 * Валидация окружения на старте: приложение падает сразу с понятной ошибкой,
 * а не в рантайме при первом обращении к отсутствующей переменной.
 */
function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Переменная окружения ${name} обязательна (см. .env.example)`);
  return v;
}

function optional(name: string, def = ''): string {
  return process.env[name] ?? def;
}

function intVar(name: string, def: number): number {
  const raw = process.env[name];
  if (!raw) return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n <= 0) throw new Error(`${name} должна быть положительным целым`);
  return n;
}

const isTest = process.env.NODE_ENV === 'test';

export const env = {
  NODE_ENV: optional('NODE_ENV', 'development'),
  isProd: process.env.NODE_ENV === 'production',
  API_PORT: intVar('API_PORT', 3001),
  /**
   * Адрес прослушивания. По умолчанию только localhost — наружу ничего не
   * торчит. В проде ставится адрес WireGuard (10.0.0.1): туда ходит и nginx
   * с той же машины, и companion-плагин через туннель, а на публичном
   * интерфейсе порт при этом не слушается вовсе.
   */
  API_BIND: optional('API_BIND', '127.0.0.1'),
  /**
   * Адреса реверс-прокси, чьему X-Forwarded-For можно верить (через запятую).
   * Пусто — не верить никому: тогда req.ip всегда реальный источник.
   * Важно для журнала сессий и для проверки «запрос из приватной сети».
   */
  TRUST_PROXY: optional('TRUST_PROXY', ''),
  WEB_ORIGIN: optional('WEB_ORIGIN', 'http://localhost:5173'),
  /** Адрес панели для ссылок в письмах. По умолчанию совпадает с WEB_ORIGIN. */
  PANEL_URL: optional('PANEL_URL', optional('WEB_ORIGIN', 'http://localhost:5173')),
  DATABASE_URL: isTest ? optional('DATABASE_URL') : required('DATABASE_URL'),
  REDIS_URL: optional('REDIS_URL', 'redis://localhost:6379'),
  JWT_ACCESS_SECRET: isTest ? 'test-access' : required('JWT_ACCESS_SECRET'),
  JWT_REFRESH_SECRET: isTest ? 'test-refresh' : required('JWT_REFRESH_SECRET'),
  ACCESS_TOKEN_TTL_SEC: intVar('ACCESS_TOKEN_TTL_SEC', 900),
  REFRESH_TOKEN_TTL_SEC: intVar('REFRESH_TOKEN_TTL_SEC', 14 * 24 * 3600),
  APP_ENCRYPTION_KEY: isTest
    ? Buffer.alloc(32).toString('base64')
    : required('APP_ENCRYPTION_KEY'),
  // Схема важна: если nginx панели уводит http на https, обращение по
  // http://127.0.0.1 вернёт HTML-заглушку редиректа вместо JSON.
  PTERO_BASE_URL: optional('PTERO_BASE_URL', 'https://panel.aurumgg.ovh'),
  PTERO_APP_API_KEY: optional('PTERO_APP_API_KEY'),
  PTERO_CLIENT_API_KEY: optional('PTERO_CLIENT_API_KEY'),
};
