/**
 * Разбор ANSI-последовательностей из консоли сервера.
 *
 * ЗАЧЕМ ЭТО ВООБЩЕ НУЖНО. Wings отдаёт вывод контейнера байт в байт — со всеми
 * escape-последовательностями, которые печатает сама игра. Minecraft красит
 * консоль именно ими: ESC[96m — голубой, ESC[1m — жирный, ESC[0m — сброс.
 * Панель Pterodactyl рисует терминал через xterm.js и поэтому показывает цвет;
 * наша консоль — обычный список строк в React, и неразобранные
 * последовательности вываливались в текст мусорными символами:
 * «⯐[37m⯐[1m[LP]⯐[0m» вместо голубого «[LP]».
 *
 * Функции здесь чистые и без React — ровно потому, что такой разбор ломается
 * молча: строка остаётся читаемой, просто цвет пропадает или, наоборот,
 * протекает на весь остаток журнала. Проверять это глазами по скриншоту
 * бесполезно, поэтому — тестами.
 */

/** Готовые к отрисовке свойства куска строки. */
export interface AnsiStyle {
  color?: string;
  background?: string;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
  strike?: boolean;
  dim?: boolean;
}

/** Кусок строки с одинаковым оформлением. href — если этот кусок адрес. */
export interface AnsiSegment {
  text: string;
  style: AnsiStyle;
  href?: string;
}

/**
 * Палитра взята из темы консоли Pterodactyl — чтобы одна и та же строка лога
 * выглядела в обеих панелях одинаково и цвет не приходилось «переучивать».
 *
 * Единственное отступление — brightBlack: у Pterodactyl это
 * rgba(255,255,255,0.2) на полностью чёрном фоне, а наша консоль стоит на
 * подложке посветлее, и на ней такой серый уже не читается.
 */
const BASE = ['#000000', '#E54B4B', '#9ECE58', '#FAED70', '#396FE2', '#BB80B3', '#2DDAFD', '#d0d0d0'];
const BRIGHT = [
  'rgba(255,255,255,0.38)',
  '#FF5370',
  '#C3E88D',
  '#FFCB6B',
  '#82AAFF',
  '#C792EA',
  '#89DDFF',
  '#ffffff',
];

/** Цвет из палитры на 256 значений — так её задаёт xterm, и так же ждут игры. */
function xterm256(n: number): string {
  if (n < 8) return BASE[n]!;
  if (n < 16) return BRIGHT[n - 8]!;
  if (n < 232) {
    const i = n - 16;
    const level = (v: number) => (v === 0 ? 0 : 55 + v * 40);
    return `rgb(${level(Math.floor(i / 36) % 6)},${level(Math.floor(i / 6) % 6)},${level(i % 6)})`;
  }
  const gray = 8 + (n - 232) * 10;
  return `rgb(${gray},${gray},${gray})`;
}

type Ink = { basic: number } | { css: string } | null;

interface State {
  fg: Ink;
  bg: Ink;
  bold: boolean;
  dim: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
}

const EMPTY: State = {
  fg: null,
  bg: null,
  bold: false,
  dim: false,
  italic: false,
  underline: false,
  strike: false,
};

/**
 * Цвет с учётом жирности.
 *
 * Терминалы (и xterm.js, на котором стоит Pterodactyl) рисуют жирный текст
 * базовым цветом в яркой версии — поэтому ESC[1m ESC[32m в консоли выглядит
 * салатовым, а не тёмно-зелёным. Без этого правила цвета были бы «те же, но
 * не такие».
 */
function ink(value: Ink, bold: boolean): string | undefined {
  if (!value) return undefined;
  if ('css' in value) return value.css;
  return bold ? BRIGHT[value.basic] : BASE[value.basic];
}

function toStyle(state: State): AnsiStyle {
  const style: AnsiStyle = {};
  const color = ink(state.fg, state.bold);
  const background = ink(state.bg, false);
  if (color) style.color = color;
  if (background) style.background = background;
  if (state.bold) style.bold = true;
  if (state.dim) style.dim = true;
  if (state.italic) style.italic = true;
  if (state.underline) style.underline = true;
  if (state.strike) style.strike = true;
  return style;
}

/**
 * Применение SGR-кодов (то, что стоит перед «m»).
 *
 * Неизвестные коды пропускаются молча: список SGR длинный, игры и плагины
 * используют из него горстку, а падать или показывать сырой номер из-за
 * незнакомого кода — худшее из возможных поведений.
 */
function applySgr(state: State, params: number[]): State {
  let next = { ...state };
  for (let i = 0; i < params.length; i += 1) {
    const code = params[i]!;
    if (code === 0) next = { ...EMPTY };
    else if (code === 1) next.bold = true;
    else if (code === 2) next.dim = true;
    else if (code === 3) next.italic = true;
    else if (code === 4) next.underline = true;
    else if (code === 9) next.strike = true;
    else if (code === 21 || code === 22) {
      next.bold = false;
      next.dim = false;
    } else if (code === 23) next.italic = false;
    else if (code === 24) next.underline = false;
    else if (code === 29) next.strike = false;
    else if (code >= 30 && code <= 37) next.fg = { basic: code - 30 };
    else if (code === 39) next.fg = null;
    else if (code >= 40 && code <= 47) next.bg = { basic: code - 40 };
    else if (code === 49) next.bg = null;
    else if (code >= 90 && code <= 97) next.fg = { css: BRIGHT[code - 90]! };
    else if (code >= 100 && code <= 107) next.bg = { css: BRIGHT[code - 100]! };
    else if (code === 38 || code === 48) {
      // Расширенный цвет: 5;<номер в палитре> или 2;<r>;<g>;<b>.
      const mode = params[i + 1];
      let value: string;
      if (mode === 5 && params.length > i + 2) {
        value = xterm256(params[i + 2]!);
        i += 2;
      } else if (mode === 2 && params.length > i + 4) {
        value = `rgb(${params[i + 2]},${params[i + 3]},${params[i + 4]})`;
        i += 4;
      } else {
        // Обрезанная последовательность: дальше по этой строке параметры уже
        // не разобрать — прекращаем, оставив прежнее оформление.
        break;
      }
      if (code === 38) next.fg = { css: value };
      else next.bg = { css: value };
    }
  }
  return next;
}

/**
 * Escape-последовательности целиком: CSI (ESC[…буква), OSC (заголовок окна и
 * прочее) и короткие двухсимвольные. Оформление меняет только CSI,
 * оканчивающийся на «m»; всё остальное к списку строк отношения не имеет и
 * просто выбрасывается — показывать это человеку незачем.
 */
const ESCAPE =
  // Разбирать управляющие символы и есть задача этого файла: правило
  // предупреждает о них как о случайной опечатке, а здесь они намеренны.
  // eslint-disable-next-line no-control-regex
  /\u001b(?:\[[0-9;:?]*[ -/]*[@-~]|\][^\u0007\u001b]*(?:\u0007|\u001b\\)?|[@-Z\\-_])/g;

/** Управляющие символы, которые в списке строк рисуются «квадратиками». */
// Разбирать управляющие символы и есть задача этого файла: правило
// предупреждает о них как о случайной опечатке, а здесь они намеренны.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/g;

/**
 * Подготовка сырой строки от Wings.
 *
 * Отдельно обрабатывается возврат каретки: прогресс-бары (скачивание модов,
 * распаковка) печатают несколько состояний одной строки через \r, рассчитывая,
 * что терминал перезатрёт предыдущее. В списке строк перезатирать нечего,
 * поэтому оставляем последнее состояние — то самое, что человек увидел бы в
 * терминале.
 */
function prepare(raw: string): string {
  const line = raw.replace(/(?:\r\n|\r|\n)+$/, '');
  const lastCr = line.lastIndexOf('\r');
  return lastCr === -1 ? line : line.slice(lastCr + 1);
}

/** Разбор строки на куски с оформлением; ссылки внутри кусков — отдельно. */
export function parseAnsi(raw: string): AnsiSegment[] {
  const line = prepare(raw);
  const segments: AnsiSegment[] = [];
  let state: State = { ...EMPTY };
  let at = 0;

  const push = (text: string) => {
    const clean = text.replace(CONTROL, '');
    if (!clean) return;
    const style = toStyle(state);
    for (const part of splitLinks(clean)) {
      segments.push({ text: part.text, style, href: part.href });
    }
  };

  ESCAPE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = ESCAPE.exec(line)) !== null) {
    if (match.index > at) push(line.slice(at, match.index));
    at = match.index + match[0].length;
    const sequence = match[0];
    if (sequence.startsWith('\u001b[') && sequence.endsWith('m')) {
      const body = sequence.slice(2, -1).replace(/:/g, ';');
      // Пустое тело — это ESC[m, полный сброс: так его понимает терминал.
      const params = body === '' ? [0] : body.split(';').map((p) => Number(p) || 0);
      state = applySgr(state, params);
    }
  }
  if (at < line.length) push(line.slice(at));

  return segments;
}

/**
 * Ссылки внутри текста.
 *
 * В Pterodactyl адреса в консоли кликабельны (xterm-addon-web-links), и это не
 * украшение: LuckPerms, отчёты о падениях и загрузчики модов общаются с
 * администратором именно одноразовыми ссылками. Перепечатывать их с телефона
 * руками — то ещё занятие.
 *
 * Берём только http(s): выдавать за ссылку file:// или голый домен из текста
 * лога значило бы иногда открывать не то, чего человек ждал.
 */
// Разбирать управляющие символы и есть задача этого файла: правило
// предупреждает о них как о случайной опечатке, а здесь они намеренны.
// eslint-disable-next-line no-control-regex
const URL_RE = /https?:\/\/[^\s<>"'`\u0000-\u001f]+/g;

export function splitLinks(text: string): { text: string; href?: string }[] {
  const parts: { text: string; href?: string }[] = [];
  let at = 0;
  URL_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = URL_RE.exec(text)) !== null) {
    const url = trimUrl(match[0]);
    if (match.index > at) parts.push({ text: text.slice(at, match.index) });
    parts.push({ text: url, href: url });
    at = match.index + url.length;
    URL_RE.lastIndex = at;
  }
  if (at < text.length) parts.push({ text: text.slice(at) });
  return parts.length > 0 ? parts : [{ text }];
}

const count = (text: string, char: string) => text.split(char).length - 1;

/**
 * Отрезает от адреса хвост, который на самом деле принадлежит предложению:
 * точку в конце, запятую, закрывающую скобку без открывающей. Без этого
 * «смотри https://example.com/a.» вела бы на несуществующий адрес с точкой.
 */
function trimUrl(url: string): string {
  let result = url;
  for (;;) {
    const last = result[result.length - 1];
    if (!last) break;
    if ('.,;:!?«»"\''.includes(last)) {
      result = result.slice(0, -1);
      continue;
    }
    const open = last === ')' ? '(' : last === ']' ? '[' : last === '}' ? '{' : null;
    if (open && count(result, open) < count(result, last)) {
      result = result.slice(0, -1);
      continue;
    }
    break;
  }
  return result;
}
