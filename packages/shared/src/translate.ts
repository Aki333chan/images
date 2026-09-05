import { LOCALE_TAGS, type Locale } from './locales';

/**
 * Перевод строки по ключу — без внешней библиотеки.
 *
 * Своя реализация вместо i18next/react-intl по той же причине, что и свой
 * генератор JSON в плагине: нужного здесь — подстановка значений, множественные
 * числа и запасной язык, — а это полсотни строк. Библиотека принесла бы с собой
 * форматы сообщений, пространства имён, детекторы языка и загрузчики, из
 * которых мы не использовали бы ничего.
 *
 * Множественные числа считает Intl.PluralRules, а не наше правило. У русского
 * их три (1 файл / 2 файла / 5 файлов, и отдельно 11-14), у польского — тоже
 * три, но границы другие, у английского — два. Писать это руками значит
 * однажды ошибиться в польском, которого мы не знаем; браузер знает CLDR.
 */

/** Плоский словарь: ключ -> строка или набор форм множественного числа. */
export type Catalog = Record<string, string | PluralForms>;

/**
 * Формы множественного числа. Имена — те же, что отдаёт Intl.PluralRules:
 * one/few/many/other. Заполнять нужно только те, что есть у языка.
 */
export interface PluralForms {
  zero?: string;
  one?: string;
  two?: string;
  few?: string;
  many?: string;
  other: string;
}

export type Values = Record<string, string | number>;

function isPlural(entry: string | PluralForms): entry is PluralForms {
  return typeof entry !== 'string';
}

/**
 * Подставляет значения вида {name}.
 *
 * Плейсхолдер, которому не передали значения, остаётся как есть — видимым
 * «{count}» в интерфейсе. Это намеренно: пустая строка на его месте выглядела
 * бы законченной фразой, и незаполненный перевод дожил бы до продакшена.
 */
function interpolate(text: string, values: Values | undefined, format: (n: number) => string): string {
  if (!values) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => {
    const value = values[name];
    if (value === undefined) return whole;
    return typeof value === 'number' ? format(value) : value;
  });
}

/**
 * Переводчик для одного языка.
 *
 * `fallback` — словарь запасного языка (русского). В него уходит ключ,
 * которого нет в основном: непереведённая строка должна остаться читаемой
 * фразой, а не превратиться в «settings.language.title» на экране.
 */
export function makeTranslator(locale: Locale, catalog: Catalog, fallback: Catalog) {
  const tag = LOCALE_TAGS[locale];
  const plurals = new Intl.PluralRules(tag);
  const numbers = new Intl.NumberFormat(tag);
  const formatNumber = (value: number) => numbers.format(value);

  return function t(key: string, values?: Values): string {
    const entry = catalog[key] ?? fallback[key];
    if (entry === undefined) {
      // Ключа нет нигде — показываем его сам. Заметно при первом же взгляде
      // на экран и однозначно указывает, что именно забыли добавить.
      return key;
    }

    if (!isPlural(entry)) return interpolate(entry, values, formatNumber);

    const count = values?.count;
    if (typeof count !== 'number') {
      // Формы есть, а числа не передали — берём «other» и не падаем.
      return interpolate(entry.other, values, formatNumber);
    }
    const form = plurals.select(count);
    const text = entry[form] ?? entry.other;
    return interpolate(text, values, formatNumber);
  };
}
