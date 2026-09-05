process.env.NODE_ENV = 'test';

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { LOCALES, type Locale } from '@aurum/shared';

/**
 * Словари не расходятся.
 *
 * Ключ, забытый в одном языке, ломается тихо: makeTranslator подставит
 * русскую фразу, и польский интерфейс покажет её посреди своего текста. На
 * экране это выглядит не как «перевода нет», а как «панель сломалась».
 *
 * Проверяются оба набора словарей — панели и API, — потому что расходятся
 * они по одной и той же причине: правку внесли в один файл из трёх.
 */
const SETS: { name: string; dir: string }[] = [
  { name: 'API', dir: join(__dirname, 'catalogs') },
  { name: 'панель', dir: join(__dirname, '../../../web/src/i18n/catalogs') },
];

function load(dir: string, locale: Locale): Record<string, unknown> {
  return JSON.parse(readFileSync(join(dir, `${locale}.json`), 'utf8')) as Record<string, unknown>;
}

describe.each(SETS)('словари ($name)', ({ dir }) => {
  const ru = load(dir, 'ru');

  it.each(LOCALES.filter((l) => l !== 'ru'))('%s знает все ключи русского', (locale) => {
    const other = load(dir, locale);
    expect(Object.keys(ru).filter((key) => !(key in other))).toEqual([]);
  });

  it.each(LOCALES.filter((l) => l !== 'ru'))('в %s нет ключей сверх русского', (locale) => {
    // Лишний ключ — обычно опечатка в имени: перевод есть, но берётся он
    // никогда, потому что код просит другое имя.
    const other = load(dir, locale);
    expect(Object.keys(other).filter((key) => !(key in ru))).toEqual([]);
  });

  it.each(LOCALES)('в %s нет пустых строк', (locale) => {
    const catalog = load(dir, locale);
    const empty = Object.entries(catalog)
      .filter(([, value]) => typeof value === 'string' && value.trim() === '')
      .map(([key]) => key);
    expect(empty).toEqual([]);
  });

  it.each(LOCALES.filter((l) => l !== 'ru'))(
    'подстановки в %s те же, что в русском',
    (locale) => {
      // Потерянный при переводе {player} — это фраза без имени игрока, а
      // лишний {plyer} останется на экране фигурными скобками.
      const other = load(dir, locale);
      const names = (value: unknown): string[] =>
        typeof value === 'string'
          ? [...value.matchAll(/\{(\w+)\}/g)].map((m) => m[1] ?? '').sort()
          : [];
      const mismatched = Object.keys(ru).filter(
        (key) => key in other && names(ru[key]).join() !== names(other[key]).join(),
      );
      expect(mismatched).toEqual([]);
    },
  );
});

/**
 * Ключи, которые бэкенд рассылает наружу, кому-то из словарей известны.
 *
 * Ошибки и причины недоступности бэкенд отдаёт ключами: текст ошибки
 * собирает фильтр по словарю API, текст причины (поле reason в DTO) — браузер
 * по словарю панели. Опечатка в имени ключа не падает ни там, ни там — она
 * просто доезжает до человека служебной строкой вида «mc.err.playerUnknwon».
 * Поэтому проверяем не работу перевода, а само существование ключа.
 *
 * Список префиксов явный: он же и опись того, какие пространства имён у нас
 * есть. Ловить «любую строку с точкой» нельзя — под это подошли бы и версии
 * игры, и mime-типы, и пути.
 */
const KEY_PREFIXES = [
  'mc',
  'market',
  'set',
  'role',
  'errors',
  'mail',
  'alert',
  'size',
  'res',
  // С точкой после err: «ai.chat» и «users.manage» — это права, а
  // «ai.model» и «users.requireGmApprovalForAdminCreatedAccounts» — поля
  // настроек. Ни то, ни другое в словарь не входит и входить не должно.
  'auth\\.err',
  'users\\.err',
  'ai\\.err',
];

describe('ключи, которые отдаёт бэкенд', () => {
  const api = load(join(__dirname, 'catalogs'), 'ru');
  const web = load(join(__dirname, '../../../web/src/i18n/catalogs'), 'ru');
  const pattern = new RegExp(`'((?:${KEY_PREFIXES.join('|')})\\.[a-z][\\w.]*)'`, 'gi');

  it('каждый ключ из исходников есть в словаре API или панели', () => {
    const used = new Set<string>();
    for (const file of walk(join(__dirname, '..'))) {
      if (!file.endsWith('.ts') || file.endsWith('.spec.ts')) continue;
      for (const m of readFileSync(file, 'utf8').matchAll(pattern)) used.add(m[1] as string);
    }
    // Ключи в исходниках вообще есть: пустое множество означало бы, что
    // регулярка перестала совпадать, а тест — проходить впустую.
    expect(used.size).toBeGreaterThan(50);
    expect([...used].filter((key) => !(key in api) && !(key in web)).sort()).toEqual([]);
  });
});

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}
