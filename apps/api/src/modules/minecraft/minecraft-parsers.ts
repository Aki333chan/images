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
