import { parseAnsi, splitLinks } from './ansi';

/**
 * Разбор ANSI и поиск ссылок в консоли.
 *
 * Escape-последовательность собирается через String.fromCharCode(27), а не
 * пишется в тексте: символ ESC невидим, и в тесте, где его «видно» только по
 * ширине отступа, ошибку не заметить.
 */
const ESC = String.fromCharCode(27);
const sgr = (...codes: (number | string)[]) => `${ESC}[${codes.join(';')}m`;

const text = (raw: string) =>
  parseAnsi(raw)
    .map((s) => s.text)
    .join('');

describe('цвет в консоли', () => {
  it('escape-последовательности не попадают в текст', () => {
    // Ровно то, что было видно на экране: «⯐[37m⯐[1m…» вместо цвета.
    const raw = `[14:37:37 INFO]: ${sgr(37)}${sgr(1)}${sgr(96)}L${sgr(36)}P${sgr(0)}] готово`;
    expect(text(raw)).toBe('[14:37:37 INFO]: LP] готово');
    expect(text(raw)).not.toContain(ESC);
    expect(text(raw)).not.toContain('[96m');
  });

  it('цвет применяется к тому куску, перед которым стоит', () => {
    const parts = parseAnsi(`обычный${sgr(32)}зелёный${sgr(0)}снова обычный`);
    expect(parts.map((p) => [p.text, p.style.color])).toEqual([
      ['обычный', undefined],
      ['зелёный', '#9ECE58'],
      ['снова обычный', undefined],
    ]);
  });

  it('жирный берёт яркую версию цвета — как в терминале', () => {
    // Иначе «тот же зелёный» в панели и в Pterodactyl выглядел бы по-разному:
    // xterm рисует жирный базовый цвет ярким.
    expect(parseAnsi(`${sgr(32)}тускло`)[0]!.style.color).toBe('#9ECE58');
    expect(parseAnsi(`${sgr(1, 32)}ярко`)[0]!.style.color).toBe('#C3E88D');
  });

  it('яркий код цвета от жирности не зависит', () => {
    // 96 — уже яркий голубой, и делать его «ещё ярче» нечем.
    expect(parseAnsi(`${sgr(1, 96)}LP`)[0]!.style.color).toBe('#89DDFF');
  });

  it('сброс снимает и цвет, и жирность', () => {
    const parts = parseAnsi(`${sgr(1, 31)}ошибка${sgr(0)}дальше`);
    expect(parts[1]!.style).toEqual({});
  });

  it('оформление не протекает на следующую строку', () => {
    // Строки разбираются независимо: незакрытый цвет в одной строке не должен
    // красить весь остаток журнала.
    expect(parseAnsi('обычная строка')[0]!.style).toEqual({});
  });

  it('фон, подчёркивание и курсив различаются', () => {
    const style = parseAnsi(`${sgr(4, 3, 41)}важно`)[0]!.style;
    expect(style).toEqual({ background: '#E54B4B', italic: true, underline: true });
  });

  it('22 снимает жирность, не трогая цвет', () => {
    const parts = parseAnsi(`${sgr(1, 31)}жирный${sgr(22)}обычный`);
    expect(parts[1]!.style).toEqual({ color: '#E54B4B' });
  });

  it('расширенные цвета: 256 и truecolor', () => {
    // 196 и 48 — из кубической части палитры xterm (#FF0000 и #00FF87),
    // 232 — из серой. Проверяем все три ветки: цифры в них считаются
    // по-разному, и ошибка в одной из них на глаз не видна.
    expect(parseAnsi(`${sgr(38, 5, 196)}x`)[0]!.style.color).toBe('rgb(255,0,0)');
    expect(parseAnsi(`${sgr(38, 5, 48)}x`)[0]!.style.color).toBe('rgb(0,255,135)');
    expect(parseAnsi(`${sgr(38, 5, 232)}x`)[0]!.style.color).toBe('rgb(8,8,8)');
    expect(parseAnsi(`${sgr(38, 2, 12, 34, 56)}x`)[0]!.style.color).toBe('rgb(12,34,56)');
  });

  it('незнакомый код не ломает строку и не показывается', () => {
    expect(text(`${sgr(53)}текст`)).toBe('текст');
  });

  it('последовательности не про цвет просто выбрасываются', () => {
    // ESC[2K (очистить строку) и ESC[?25l (спрятать курсор) в списке строк
    // делать нечего, но и в тексте им не место.
    expect(text(`${ESC}[2K${ESC}[?25lзагрузка`)).toBe('загрузка');
  });

  it('остальные управляющие символы не рисуются квадратиками', () => {
    expect(text(`строка${String.fromCharCode(7)}с звонком`)).toBe('строкас звонком');
  });

  it('прогресс-бар показывает последнее состояние, а не все сразу', () => {
    // Через \r игра перезатирает одну и ту же строку; в списке строк
    // перезатирать нечего, и склеенные состояния выглядели бы кашей.
    expect(text('12%\r57%\r100% готово')).toBe('100% готово');
  });

  it('перевод строки в конце не превращается в пустой кусок', () => {
    expect(parseAnsi('строка\r\n').map((p) => p.text)).toEqual(['строка']);
  });

  it('пустая строка не даёт ни одного куска', () => {
    expect(parseAnsi('')).toEqual([]);
    expect(parseAnsi(sgr(0))).toEqual([]);
  });
});

describe('ссылки в консоли', () => {
  it('адрес выделяется отдельным куском', () => {
    const parts = splitLinks('Открой https://luckperms.net/editor/LMWxwakK13 в браузере');
    expect(parts).toEqual([
      { text: 'Открой ' },
      {
        text: 'https://luckperms.net/editor/LMWxwakK13',
        href: 'https://luckperms.net/editor/LMWxwakK13',
      },
      { text: ' в браузере' },
    ]);
  });

  it('точка в конце предложения в адрес не попадает', () => {
    const [link] = splitLinks('см. https://example.com/a.');
    expect(splitLinks('см. https://example.com/a.')[1]!.href).toBe('https://example.com/a');
    expect(link!.text).toBe('см. ');
  });

  it('скобка остаётся, если она открыта внутри адреса', () => {
    const parts = splitLinks('вики https://ru.wikipedia.org/wiki/Ада_(язык) дальше');
    expect(parts[1]!.href).toBe('https://ru.wikipedia.org/wiki/Ада_(язык)');
  });

  it('лишняя закрывающая скобка отрезается', () => {
    expect(splitLinks('(см. https://example.com/a)')[1]!.href).toBe('https://example.com/a');
  });

  it('несколько адресов в строке', () => {
    const parts = splitLinks('a https://one.test b https://two.test c');
    expect(parts.filter((p) => p.href).map((p) => p.href)).toEqual([
      'https://one.test',
      'https://two.test',
    ]);
  });

  it('не-http ссылками не считаются', () => {
    // file:// и голый домен из текста лога открывать не туда — хуже, чем не
    // открывать вовсе.
    expect(splitLinks('file:///etc/passwd и example.com').some((p) => p.href)).toBe(false);
  });

  it('цветной адрес остаётся и цветным, и кликабельным', () => {
    // Именно так его печатает LuckPerms: ссылка внутри голубого куска.
    const parts = parseAnsi(`${sgr(36)}https://luckperms.net/editor/AbC${sgr(0)}`);
    expect(parts[0]!.href).toBe('https://luckperms.net/editor/AbC');
    expect(parts[0]!.style.color).toBe('#2DDAFD');
  });
});
