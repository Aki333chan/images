process.env.NODE_ENV = 'test';

import {
  ASCII_ART_LIMITS,
  hasAsciiArt,
  parseMessageSegments,
  validateAsciiArt,
  wrapAsciiArt,
} from '@aurum/shared';
import { ASCII_ART_CATALOG, findAsciiArt } from './ascii-art.catalog';

/**
 * Разбор сообщения на текст и моноширинные блоки.
 *
 * Главное свойство: внутри блока текст не трогается ВООБЩЕ. Пробелы там —
 * не форматирование, а сам рисунок, и любая «нормализация» его ломает.
 */
describe('parseMessageSegments', () => {
  it('обычное сообщение — один текстовый кусок', () => {
    expect(parseMessageSegments('привет, как дела')).toEqual([
      { kind: 'text', content: 'привет, как дела' },
    ]);
  });

  it('блок в ограждении становится артом', () => {
    const message = ['вот кот:', '```', ' /\\_/\\', '( o.o )', '```'].join('\n');

    expect(parseMessageSegments(message)).toEqual([
      { kind: 'text', content: 'вот кот:' },
      { kind: 'art', content: ' /\\_/\\\n( o.o )' },
    ]);
  });

  it('ведущие пробелы внутри блока сохраняются посимвольно', () => {
    const art = '    *\n   ***\n  *****';
    const segments = parseMessageSegments(`\`\`\`\n${art}\n\`\`\``);

    expect(segments).toHaveLength(1);
    expect(segments[0]!.content).toBe(art);
  });

  it('пустые строки внутри арта не выбрасываются — это часть рисунка', () => {
    const art = 'верх\n\nниз';
    expect(parseMessageSegments(`\`\`\`\n${art}\n\`\`\``)[0]!.content).toBe(art);
  });

  it('текст до и после блока не теряется', () => {
    const message = ['держи', '```', 'арт', '```', 'нравится?'].join('\n');
    const kinds = parseMessageSegments(message).map((s) => s.kind);

    expect(kinds).toEqual(['text', 'art', 'text']);
  });

  it('незакрытое ограждение — арт до конца, а не каша', () => {
    // Человек мог отправить, не дописав. Показать остаток обычным текстом
    // значило бы развалить рисунок именно там, где он уже виден собеседнику.
    const segments = parseMessageSegments('```\n /\\_/\\\n( o.o )');

    expect(segments).toEqual([{ kind: 'art', content: ' /\\_/\\\n( o.o )' }]);
  });

  it('два блока подряд не склеиваются', () => {
    const message = ['```', 'один', '```', '```', 'два', '```'].join('\n');
    expect(parseMessageSegments(message)).toEqual([
      { kind: 'art', content: 'один' },
      { kind: 'art', content: 'два' },
    ]);
  });

  it('hasAsciiArt отличает арт от обычного текста', () => {
    expect(hasAsciiArt('просто текст')).toBe(false);
    expect(hasAsciiArt('```\nарт\n```')).toBe(true);
    // Тройные кавычки в середине строки — не ограждение.
    expect(hasAsciiArt('он написал ``` и ушёл')).toBe(false);
  });
});

describe('wrapAsciiArt', () => {
  it('обрамляет и не обрамляет дважды', () => {
    expect(wrapAsciiArt('кот')).toBe('```\nкот\n```');
    expect(wrapAsciiArt('```\nкот\n```')).toBe('```\nкот\n```');
  });

  it('обрамлённый арт разбирается обратно без потерь', () => {
    const art = ' /\\_/\\\n( o.o )\n > ^ <';
    expect(parseMessageSegments(wrapAsciiArt(art))[0]!.content).toBe(art);
  });
});

describe('validateAsciiArt', () => {
  it('пропускает нормальный арт', () => {
    const result = validateAsciiArt(' /\\_/\\\n( o.o )');
    expect(result).toEqual({ ok: true, art: ' /\\_/\\\n( o.o )' });
  });

  it('разворачивает табуляции в пробелы', () => {
    // Ширина таба у отправителя и получателя разная, и арт разъезжается
    // именно у собеседника — там, где автор этого уже не увидит.
    const result = validateAsciiArt('a\tb');
    expect(result).toEqual({ ok: true, art: 'a    b' });
  });

  it('обезвреживает ограждение внутри арта', () => {
    // Иначе блок закрылся бы раньше времени и остаток рисунка уехал в текст.
    const result = validateAsciiArt('```\nвнутри');
    expect(result.ok && result.art.startsWith("'''")).toBe(true);
  });

  it('отклоняет пустой арт', () => {
    expect(validateAsciiArt('   \n  ').ok).toBe(false);
  });

  it('отклоняет слишком высокий и слишком широкий', () => {
    const tall = validateAsciiArt('x\n'.repeat(ASCII_ART_LIMITS.maxLines + 1));
    expect(tall.ok).toBe(false);
    expect(!tall.ok && tall.reason).toMatch(/высокий/);

    const wide = validateAsciiArt('x'.repeat(ASCII_ART_LIMITS.maxLineLength + 1));
    expect(wide.ok).toBe(false);
    expect(!wide.ok && wide.reason).toMatch(/широкий/);
  });

  it('ровно на пределе — проходит', () => {
    expect(validateAsciiArt('x'.repeat(ASCII_ART_LIMITS.maxLineLength)).ok).toBe(true);
  });

  it('переводы строк Windows не считаются лишними строками', () => {
    const result = validateAsciiArt('a\r\nb');
    expect(result).toEqual({ ok: true, art: 'a\nb' });
  });
});

describe('каталог артов', () => {
  it('находит по слову в любой форме запроса', () => {
    for (const query of ['кот', 'котика', 'нарисуй кота', 'cat']) {
      expect(findAsciiArt(query).some((e) => e.id === 'cat')).toBe(true);
    }
  });

  it('пустой запрос отдаёт весь каталог', () => {
    expect(findAsciiArt('')).toHaveLength(ASCII_ART_CATALOG.length);
  });

  it('на бессмысленный запрос честно ничего не находит', () => {
    expect(findAsciiArt('квазиэлектродинамика')).toEqual([]);
  });

  it('каждый арт из каталога проходит собственную проверку', () => {
    // Каталог правится руками, и слишком широкий арт легко не заметить —
    // пусть об этом скажет тест, а не собеседник.
    for (const entry of ASCII_ART_CATALOG) {
      expect({ id: entry.id, ok: validateAsciiArt(entry.art).ok }).toEqual({
        id: entry.id,
        ok: true,
      });
    }
  });

  it('идентификаторы уникальны', () => {
    const ids = ASCII_ART_CATALOG.map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
