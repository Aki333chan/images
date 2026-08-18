/**
 * ASCII-арт во внутренних сообщениях.
 *
 * ПРОБЛЕМА. Обычный текст в чате переносится по ширине пузыря и склеивает
 * пробелы — для арта это смерть: кот превращается в кашу из скобок. Значит,
 * нужно уметь отличать «это арт» от «это текст», иначе одно из двух будет
 * отображаться неправильно.
 *
 * РЕШЕНИЕ. Арт обрамляется тройными обратными кавычками — тем же способом,
 * что и код в мессенджерах и на GitHub. Выбрано не случайно:
 *
 *   - соглашение знакомое, человек может прислать арт руками, не изучая
 *     ничего нового;
 *   - признак явный, а не угаданный эвристикой. Эвристика («много скобок —
 *     наверное, арт») рано или поздно ошибётся на обычном сообщении, и
 *     объяснить человеку, почему его текст вдруг стал моноширинным, будет
 *     нечем;
 *   - в базе лежит обычный текст, никаких новых колонок и типов сообщений.
 *
 * Разбор живёт здесь, а не во фронтенде, потому что правило одно на обе
 * стороны: бэкенд по нему обрамляет арт, фронтенд по нему же рисует.
 */

/** Ограждение блока — три обратные кавычки в начале строки. */
export const ASCII_ART_FENCE = '```';

/**
 * Пределы арта.
 *
 * Ширина в 120 символов — это примерно то, что ещё читается на десктопе без
 * прокрутки и влезает в письмо. Высота в 40 строк — чтобы одно сообщение не
 * занимало весь экран собеседника.
 */
export const ASCII_ART_LIMITS = {
  maxLines: 40,
  maxLineLength: 120,
  /** Совпадает с общим пределом длины сообщения. */
  maxChars: 4000,
} as const;

/** Кусок сообщения: обычный текст либо моноширинный блок. */
export type MessageSegment =
  | { kind: 'text'; content: string }
  | { kind: 'art'; content: string };

/**
 * Разбор сообщения на куски.
 *
 * Незакрытое ограждение считается артом до конца сообщения: человек мог
 * отправить, не дописав, и показать остаток кашей — худшее из решений.
 */
export function parseMessageSegments(text: string): MessageSegment[] {
  const lines = text.split('\n');
  const segments: MessageSegment[] = [];
  let buffer: string[] = [];
  let inArt = false;

  const flush = () => {
    if (buffer.length === 0) return;
    const content = buffer.join('\n');
    // Пустой текстовый кусок между двумя блоками — не содержимое, а шов.
    if (inArt || content.trim().length > 0) {
      segments.push({ kind: inArt ? 'art' : 'text', content: inArt ? content : content.trim() });
    }
    buffer = [];
  };

  for (const line of lines) {
    if (line.trimEnd() === ASCII_ART_FENCE) {
      flush();
      inArt = !inArt;
      continue;
    }
    buffer.push(line);
  }
  flush();

  return segments;
}

/** Есть ли в сообщении моноширинный блок — по этому пузырь делается шире. */
export function hasAsciiArt(text: string): boolean {
  return parseMessageSegments(text).some((s) => s.kind === 'art');
}

/** Обрамляет арт, если он ещё не обрамлён. */
export function wrapAsciiArt(art: string): string {
  const trimmed = art.replace(/^\n+|\n+$/g, '');
  if (trimmed.startsWith(ASCII_ART_FENCE)) return trimmed;
  return `${ASCII_ART_FENCE}\n${trimmed}\n${ASCII_ART_FENCE}`;
}

/**
 * Проверка арта перед отправкой.
 *
 * Табуляции разворачиваются в пробелы: ширина табуляции у отправителя и
 * получателя разная, и арт с табами разъезжается именно у собеседника —
 * то есть там, где автор этого уже не увидит.
 *
 * @returns очищенный арт либо причина отказа
 */
export function validateAsciiArt(
  raw: string,
): { ok: true; art: string } | { ok: false; reason: string } {
  const art = raw
    .replace(/\r\n?/g, '\n')
    .replace(/\t/g, '    ')
    // Ограждение внутри самого арта закрыло бы блок раньше времени.
    .replace(/^```/gm, "'''")
    .replace(/^\n+|\n+$/g, '');

  if (art.trim().length === 0) return { ok: false, reason: 'Арт пустой' };

  const lines = art.split('\n');
  if (lines.length > ASCII_ART_LIMITS.maxLines) {
    return {
      ok: false,
      reason: `Слишком высокий арт: ${lines.length} строк при пределе ${ASCII_ART_LIMITS.maxLines}`,
    };
  }
  const widest = Math.max(...lines.map((l) => l.length));
  if (widest > ASCII_ART_LIMITS.maxLineLength) {
    return {
      ok: false,
      reason: `Слишком широкий арт: ${widest} символов при пределе ${ASCII_ART_LIMITS.maxLineLength}`,
    };
  }
  if (art.length > ASCII_ART_LIMITS.maxChars) {
    return { ok: false, reason: 'Арт длиннее допустимого размера сообщения' };
  }

  return { ok: true, art };
}
