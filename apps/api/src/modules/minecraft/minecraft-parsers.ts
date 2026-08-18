import { stripColorCodes } from './rcon/rcon-packet';

/**
 * Разбор текстовых ответов ванильного сервера. Плагины иногда меняют формулировки,
 * поэтому парсеры намеренно терпимы: не сумев разобрать заголовок, возвращаем
 * то, что удалось извлечь, а не бросаем исключение.
 */

export interface ParsedPlayerList {
  online: number;
  max: number | null;
  names: string[];
}

/**
 * Ответ команды `list`:
 *   «There are 3 of a max of 20 players online: Alice, Bob, Carol»  (совр.)
 *   «There are 3/20 players online:»                                 (старые)
 */
export function parsePlayerList(raw: string): ParsedPlayerList {
  const text = stripColorCodes(raw).trim();

  let online: number | null = null;
  let max: number | null = null;

  const modern = /there are (\d+) of a max(?:imum)? of (\d+) players online/i.exec(text);
  const legacy = /there are (\d+)\/(\d+) players online/i.exec(text);
  const match = modern ?? legacy;
  if (match) {
    online = Number(match[1]);
    max = Number(match[2]);
  }

  // Имена идут после первого двоеточия.
  const colon = text.indexOf(':');
  const namesPart = colon >= 0 ? text.slice(colon + 1) : '';
  const names = namesPart
    .split(',')
    .map((n) => n.trim())
    // Paper может дописывать «(Мир)» или суффиксы — берём первый токен.
    .map((n) => n.split(/\s+/)[0] ?? '')
    .filter((n) => n.length > 0 && /^[A-Za-z0-9_.]{1,32}$/.test(n));

  return { online: online ?? names.length, max, names };
}

export interface ParsedPerformance {
  /** TPS за 1, 5 и 15 минут. null — сервер не отдал это значение. */
  tps1m: number | null;
  tps5m: number | null;
  tps15m: number | null;
  /** Среднее время тика в миллисекундах. Норма — меньше 50. */
  mspt: number | null;
}

/**
 * Ответ команды `tps` (Paper/Spigot, ванили такой команды нет):
 *   «TPS from last 1m, 5m, 15m: 20.0, 19.98, 19.5»
 *   «TPS от последних 1m, 5m, 15m: *20.0, 20.0, 20.0»  — со звёздочкой,
 *   когда значение округлено вверх, и с переводом на некоторых сборках.
 *
 * Разбираем максимально терпимо: берём первые три числа после двоеточия.
 * Названия меняются от сборки к сборке, а формат чисел — нет.
 */
export function parseTps(raw: string): Pick<ParsedPerformance, 'tps1m' | 'tps5m' | 'tps15m'> {
  const text = stripColorCodes(raw);
  const colon = text.indexOf(':');
  const tail = colon >= 0 ? text.slice(colon + 1) : text;
  // Звёздочка перед числом — пометка Paper, что TPS «подтянут» до 20.
  const numbers = (tail.match(/\*?\d+(?:[.,]\d+)?/g) ?? [])
    .map((n) => Number(n.replace('*', '').replace(',', '.')))
    // TPS выше 20 не бывает; так отсекаем случайные числа из текста.
    .filter((n) => Number.isFinite(n) && n >= 0 && n <= 20);

  return {
    tps1m: numbers[0] ?? null,
    tps5m: numbers[1] ?? null,
    tps15m: numbers[2] ?? null,
  };
}

/**
 * Ответ команды `mspt` (Paper):
 *   «Server tick times (avg/min/max) from last 5s, 10s, 1m:»
 *   «◴ 1.5/0.8/12.3, 1.6/0.7/40.1, 1.9/0.6/55.0»
 *
 * Нас интересует первое среднее — это текущее время тика.
 */
export function parseMspt(raw: string): number | null {
  const text = stripColorCodes(raw);
  // Первое число после переноса строки со списком значений либо в конце строки.
  const match = /(\d+(?:[.,]\d+)?)\s*\//.exec(text);
  if (!match) {
    // Некоторые сборки отвечают одним числом без слэшей.
    const single = /(\d+(?:[.,]\d+)?)\s*ms/i.exec(text);
    const raw = single?.[1];
    return raw ? Number(raw.replace(',', '.')) : null;
  }
  const value = Number((match[1] ?? '').replace(',', '.'));
  return Number.isFinite(value) ? value : null;
}

/** Команда не поддерживается сервером (ваниль без Paper и т.п.). */
export function looksLikeUnknownCommand(raw: string): boolean {
  const text = stripColorCodes(raw).toLowerCase();
  return (
    text.includes('unknown command') ||
    text.includes('unknown or incomplete command') ||
    text.includes('неизвестная команда') ||
    text.trim().length === 0
  );
}

/**
 * Ответ команды `whitelist list`:
 *   «There are 2 whitelisted players: Alice, Bob»
 *   «There are no whitelisted players»
 */
export function parseWhitelist(raw: string): string[] {
  const text = stripColorCodes(raw).trim();
  if (/no whitelisted players/i.test(text)) return [];
  const colon = text.indexOf(':');
  if (colon < 0) return [];
  return text
    .slice(colon + 1)
    .split(',')
    .map((n) => n.trim())
    .filter((n) => n.length > 0 && /^[A-Za-z0-9_.]{1,32}$/.test(n));
}

/**
 * Ник Minecraft: 3–16 символов [A-Za-z0-9_].
 * Проверка обязательна — ник подставляется в RCON-команду.
 */
const NICKNAME_RE = /^[A-Za-z0-9_]{3,16}$/;

export function isValidNickname(name: string): boolean {
  return NICKNAME_RE.test(name);
}

/**
 * Экранирование текстового аргумента (причина бана/кика) для RCON.
 * У RCON нет кавычек и escape-последовательностей: команда — это одна строка,
 * поэтому единственная надёжная защита — вырезать переводы строк и управляющие
 * символы, которыми можно было бы «дописать» вторую команду, и ограничить длину.
 */
export function sanitizeCommandArgument(value: string, maxLength = 200): string {
  return (
    // Цветовые коды (§c) убираем целиком, вместе с буквой кода.
    stripColorCodes(value)
      // Управляющие символы (в т.ч. \n и \r), которыми можно было бы
      // «дописать» вторую команду в ту же строку.
      // eslint-disable-next-line no-control-regex
      .replace(/[\x00-\x1f\x7f]/g, ' ')
      .replace(/\u00a7/g, '')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, maxLength)
  );
}

/**
 * Экранирование значения для вставки внутрь JSON-строки команды.
 *
 * sanitizeCommandArgument уже вырезал управляющие символы и переводы строк,
 * поэтому остаётся закрыть кавычку и обратный слэш. Через JSON.stringify —
 * чтобы не воспроизводить правила экранирования вручную; кавычки по краям
 * срезаем, они уже есть в шаблоне.
 *
 * Без этого кавычка в тексте разрывает JSON, и сервер отвергает всю команду.
 */
export function escapeForJsonLiteral(value: string): string {
  return JSON.stringify(value).slice(1, -1);
}
