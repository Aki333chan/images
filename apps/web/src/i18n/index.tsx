import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  DEFAULT_LOCALE,
  LOCALE_TAGS,
  isLocale,
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
 * Где хранится выбранный язык до входа в панель.
 *
 * localStorage, а не только запись в БД: язык нужен на экране входа, когда
 * никакого пользователя ещё нет. После входа выбор из профиля перекрывает
 * сохранённый здесь — человек, сменивший язык на рабочем компьютере, должен
 * увидеть его же и на домашнем.
 */
const STORAGE_KEY = 'aurum.locale';

function stored(): Locale | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return isLocale(value) ? value : null;
  } catch {
    // Приватное окно или запрет на хранение — не повод падать.
    return null;
  }
}

function remember(locale: Locale | null) {
  try {
    if (locale) localStorage.setItem(STORAGE_KEY, locale);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* см. stored() */
  }
}

/** Языки браузера, от самого желанного к менее. */
function fromBrowser(): string {
  return typeof navigator === 'undefined' ? '' : (navigator.languages ?? [navigator.language]).join(',');
}

/** Форматтер собирается один раз на язык: Intl дорог при создании. */
function makeDateFormatter(locale: Locale, options: Intl.DateTimeFormatOptions) {
  const format = new Intl.DateTimeFormat(LOCALE_TAGS[locale], options);
  return (value: string | number | Date | null | undefined): string => {
    if (value === null || value === undefined || value === '') return '—';
    const date = value instanceof Date ? value : new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : format.format(date);
  };
}

interface I18nValue {
  locale: Locale;
  t: (key: string, values?: Values) => string;
  /**
   * Дата и время на языке панели.
   *
   * Здесь, а не toLocaleString('ru-RU') по месту: зашитая локаль означала бы
   * польский интерфейс с русским порядком дня и месяца. Формат при этом
   * задаёт не только язык, но и страна — 05.09.2026 в России, 05/09/2026 в
   * Польше, 05/09/2026 в Британии, — и угадывать это самим незачем.
   *
   * null и нечитаемая дата дают прочерк: «Invalid Date» на экране выглядит
   * поломкой панели, хотя это просто пустое поле.
   */
  formatDate: (value: string | number | Date | null | undefined) => string;
  formatDateTime: (value: string | number | Date | null | undefined) => string;
  /**
   * Явный выбор человека. null — «как в браузере»: сохранённый выбор
   * забывается, и язык снова определяется системой.
   */
  setLocale: (locale: Locale | null) => void;
  /** Выбран ли язык вручную. false — панель следует за системой. */
  manual: boolean;
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({
  children,
  /**
   * Язык из профиля сотрудника. undefined — профиль ещё не загружен,
   * null — человек его не выбирал.
   */
  userLocale,
  onAdopt,
}: {
  children: ReactNode;
  userLocale?: Locale | null;
  /**
   * Зовётся, когда выбор сделан до входа, а в профиле его нет, — чтобы
   * панель сохранила язык у пользователя. Должен быть стабильной ссылкой:
   * он в зависимостях эффекта.
   */
  onAdopt?: (locale: Locale) => void;
}) {
  const [manualLocale, setManualLocale] = useState<Locale | null>(stored);

  // Профиль приезжает позже первого рендера: пока его нет, работает
  // сохранённый выбор и язык браузера, а как только пришёл — он главнее.
  const effective = useMemo<Locale>(
    () => resolveLocale(userLocale ?? manualLocale, fromBrowser()),
    [userLocale, manualLocale],
  );

  const setLocale = useCallback((next: Locale | null) => {
    setManualLocale(next);
    remember(next);
  }, []);

  /*
   * Согласование выбора между браузером и профилем.
   *
   * Выбрать язык можно до входа — на экране логина, где никакого профиля ещё
   * нет и сохранять некуда. Без этого шага такой выбор остался бы только в
   * этом браузере: человек, переключивший панель на польский дома, на работе
   * снова получил бы системный язык.
   *
   * Кто главнее: последнее осознанное действие. В профиле уже есть выбор —
   * он и главный, память браузера подтягивается к нему (иначе они молча
   * разъезжались бы на общем компьютере). В профиле пусто, а локально выбор
   * есть — значит его сделали до входа, и он переносится в профиль.
   *
   * «Как в системе» не переносится никуда: это отсутствие выбора, и
   * записывать его поверх уже сохранённого языка нельзя.
   */
  useEffect(() => {
    if (userLocale === undefined) return;
    if (userLocale !== null) {
      if (userLocale !== manualLocale) {
        setManualLocale(userLocale);
        remember(userLocale);
      }
      return;
    }
    if (manualLocale !== null) onAdopt?.(manualLocale);
  }, [userLocale, manualLocale, onAdopt]);

  // Атрибут lang нужен не для красоты: по нему браузер выбирает правила
  // переноса слов и словарь проверки орфографии в полях ввода.
  useEffect(() => {
    document.documentElement.lang = LOCALE_TAGS[effective];
  }, [effective]);

  const value = useMemo<I18nValue>(
    () => ({
      locale: effective,
      t: makeTranslator(effective, CATALOGS[effective], CATALOGS[DEFAULT_LOCALE]),
      formatDate: makeDateFormatter(effective, { dateStyle: 'short' }),
      formatDateTime: makeDateFormatter(effective, { dateStyle: 'short', timeStyle: 'short' }),
      setLocale,
      manual: (userLocale ?? manualLocale) !== null && (userLocale ?? manualLocale) !== undefined,
    }),
    [effective, setLocale, userLocale, manualLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n вызван вне I18nProvider');
  return value;
}

/** Короткая форма для самого частого случая — когда нужен только перевод. */
export function useT() {
  return useI18n().t;
}

/** Дата и время на языке панели. */
export function useDates() {
  const { formatDate, formatDateTime } = useI18n();
  return { formatDate, formatDateTime };
}

/**
 * Язык, который панель считает выбранным прямо сейчас, — для кода вне React.
 *
 * Нужен ровно одному месту: клиенту API, который проставляет заголовок
 * Accept-Language. Тянуть туда контекст нельзя — api() зовут и из обработчиков,
 * и из эффектов, и из мест без компонента вокруг.
 */
export function currentLocale(): Locale {
  return resolveLocale(stored(), fromBrowser());
}
