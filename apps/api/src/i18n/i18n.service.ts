import { Injectable } from '@nestjs/common';
import {
  DEFAULT_LOCALE,
  LOCALES,
  makeTranslator,
  resolveLocale,
  type Catalog,
  type Locale,
  type Values,
} from '@aurum/shared';
import ru from './catalogs/ru.json';
import en from './catalogs/en.json';
import pl from './catalogs/pl.json';

const CATALOGS: Record<Locale, Catalog> = {
  ru: ru as Catalog,
  en: en as Catalog,
  pl: pl as Catalog,
};

/**
 * Переводы на стороне API.
 *
 * Нужны, потому что тексты ошибок панель показывает ровно так, как их
 * прислал сервер: `api()` кладёт `body.message` в сообщение исключения, а
 * экран — на страницу. Возвращать коды и переводить их в браузере было бы
 * чище, но это переписывание всех мест, где что-то бросается, и потеря
 * читаемости логов.
 *
 * Переводчики собираются один раз на язык: Intl.PluralRules и
 * Intl.NumberFormat стоят дорого при создании и дёшевы при использовании.
 */
@Injectable()
export class I18nService {
  private readonly translators = new Map<Locale, ReturnType<typeof makeTranslator>>(
    LOCALES.map((locale) => [
      locale,
      makeTranslator(locale, CATALOGS[locale], CATALOGS[DEFAULT_LOCALE]),
    ]),
  );

  /** Язык запроса по заголовку Accept-Language. */
  localeOf(acceptLanguage: string | undefined): Locale {
    return resolveLocale(acceptLanguage);
  }

  t(locale: Locale, key: string, values?: Values): string {
    return (this.translators.get(locale) ?? this.translators.get(DEFAULT_LOCALE)!)(key, values);
  }

  /**
   * Есть ли такой ключ в русском словаре.
   *
   * По нему фильтр ошибок отличает ключ от готового текста. Пока переведена
   * не вся панель, в исключениях лежат и ключи, и обычные русские фразы;
   * фраза, не найденная в словаре, должна дойти до человека как есть, а не
   * превратиться в «Игрок не найден» — то есть в саму себя, но выглядящую
   * как невыполненный перевод.
   */
  known(key: string): boolean {
    return key in CATALOGS[DEFAULT_LOCALE];
  }
}
