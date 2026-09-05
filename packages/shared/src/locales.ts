/**
 * Языки панели.
 *
 * Общий контракт для веба и API: оба должны одинаково понимать, какой язык
 * выбран, и одинаково сводить «ru-RU», «pl», «en-GB» к одному из трёх.
 * Разойдись они здесь — человек с польским браузером получил бы польский
 * интерфейс и русские тексты ошибок, что выглядит как поломка панели.
 */

export const LOCALES = ['ru', 'en', 'pl'] as const;
export type Locale = (typeof LOCALES)[number];

/**
 * Язык, на который всё сваливается, если ничего не подошло.
 *
 * Русский, а не английский: панель писалась на нём, и его переводы всегда
 * полные — на них можно откатиться в любой момент, не рискуя показать пустой
 * экран из-за незаполненного ключа.
 */
export const DEFAULT_LOCALE: Locale = 'ru';

/**
 * Названия языков — каждое на своём языке.
 *
 * Именно так, а не «Английский/Польский» по-русски: человек, открывший
 * переключатель, чтобы уйти с непонятного ему языка, должен узнать свой в
 * списке, не понимая языка вокруг.
 */
export const LOCALE_LABELS: Record<Locale, string> = {
  ru: 'Русский',
  en: 'English',
  pl: 'Polski',
};

/** Код языка для Intl и атрибута lang. */
export const LOCALE_TAGS: Record<Locale, string> = {
  ru: 'ru-RU',
  en: 'en-GB',
  pl: 'pl-PL',
};

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

/**
 * Первый подходящий язык из списка кандидатов.
 *
 * Кандидаты идут в порядке убывания приоритета и могут быть чем угодно:
 * сохранённым выбором человека, значениями navigator.languages, содержимым
 * заголовка Accept-Language. Каждый разбирается по одним правилам:
 *
 * - регистр не важен: «RU» и «ru» — одно и то же;
 * - регион отбрасывается: «pl-PL», «pl_PL» и «pl» дают польский;
 * - вес q из Accept-Language игнорируется, но не ломает разбор: браузеры
 *   перечисляют языки уже по убыванию предпочтения, и порядок — это и есть
 *   ответ. Сортировать по q значило бы писать разбор RFC 9110 ради случая,
 *   когда браузер прислал бы список не по порядку;
 * - «*» из Accept-Language пропускается: он значит «любой», а не какой-то
 *   конкретный, и принять его за ответ — то же самое, что не спрашивать.
 *
 * Ничего не подошло — DEFAULT_LOCALE.
 */
export function resolveLocale(...candidates: (string | null | undefined)[]): Locale {
  for (const candidate of candidates) {
    if (!candidate) continue;
    for (const part of candidate.split(',')) {
      const tag = part.split(';')[0]?.trim().toLowerCase();
      if (!tag || tag === '*') continue;
      const base = tag.split(/[-_]/)[0];
      if (isLocale(base)) return base;
    }
  }
  return DEFAULT_LOCALE;
}
