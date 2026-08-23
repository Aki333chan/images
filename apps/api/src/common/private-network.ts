import { timingSafeEqual } from 'crypto';

/**
 * Проверка, что запрос пришёл из приватной сети.
 *
 * Общая на все игровые модули: internal-эндпоинты панели живут на её
 * внутреннем адресе (10.0.0.1) и через nginx наружу не публикуются, а
 * companion-плагины и моды ходят к ним по туннелю. Правило одно для всех,
 * поэтому и живёт в ядре: у каждого модуля своя копия рано или поздно
 * разошлась бы, и разошлась бы именно в сторону «пустим ещё и вот этих».
 */

/** Приватные диапазоны и петля. Публичных адресов здесь быть не должно. */
export function isPrivateAddress(rawIp: string | undefined): boolean {
  if (!rawIp) return false;
  // Express отдаёт IPv4-mapped адреса вида ::ffff:10.0.0.2.
  const ip = rawIp.replace(/^::ffff:/i, '');
  if (ip === '::1' || ip === '127.0.0.1') return true;
  const octets = ip.split('.');
  if (octets.length !== 4) return false;
  const [a, b] = octets.map((part) => Number(part));
  if (a === undefined || b === undefined || Number.isNaN(a) || Number.isNaN(b)) return false;
  if (a === 10) return true;
  if (a === 127) return true;
  if (a === 192 && b === 168) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  return false;
}

/**
 * Сравнение секретов за постоянное время.
 *
 * Обычное сравнение строк выходит на первом несовпавшем символе, и по
 * времени ответа токен подбирается посимвольно. Сама длина секретом не
 * является, поэтому её различие можно вернуть сразу.
 */
export function constantTimeEquals(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}
