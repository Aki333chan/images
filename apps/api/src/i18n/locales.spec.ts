import { DEFAULT_LOCALE, isLocale, resolveLocale } from '@aurum/shared';

// Сам resolveLocale живёт в packages/shared: его одинаково зовут и веб, и
// API. Тест — здесь, тем же приёмом, что у console-noise: у shared своего
// прогона тестов нет, проверяет его потребитель.
describe('выбор языка', () => {
  it('точный код проходит как есть', () => {
    expect(resolveLocale('pl')).toBe('pl');
    expect(resolveLocale('en')).toBe('en');
    expect(resolveLocale('ru')).toBe('ru');
  });

  it('регион отбрасывается, регистр не важен', () => {
    expect(resolveLocale('pl-PL')).toBe('pl');
    expect(resolveLocale('EN-GB')).toBe('en');
    expect(resolveLocale('ru_RU')).toBe('ru');
  });

  it('разбирает Accept-Language со списком и весами', () => {
    expect(resolveLocale('pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7')).toBe('pl');
  });

  it('пропускает языки, которых у нас нет, и берёт первый знакомый', () => {
    expect(resolveLocale('de-DE,uk;q=0.9,en;q=0.8')).toBe('en');
  });

  it('«*» из Accept-Language — это не ответ', () => {
    // «любой язык» и «какой-то конкретный» — разные вещи; принять «*» за
    // ответ значило бы не спрашивать вовсе.
    expect(resolveLocale('*')).toBe(DEFAULT_LOCALE);
    expect(resolveLocale('*,pl;q=0.5')).toBe('pl');
  });

  it('кандидаты перебираются по порядку: выбор человека важнее браузера', () => {
    expect(resolveLocale('en', 'pl-PL,pl')).toBe('en');
    // Сохранённого выбора нет — тогда браузер.
    expect(resolveLocale(null, 'pl-PL,pl')).toBe('pl');
    expect(resolveLocale(undefined, '', 'pl')).toBe('pl');
  });

  it('ничего не подошло — русский', () => {
    expect(resolveLocale()).toBe(DEFAULT_LOCALE);
    expect(resolveLocale('de', 'fr-FR')).toBe(DEFAULT_LOCALE);
    expect(resolveLocale('')).toBe(DEFAULT_LOCALE);
  });

  it('мусор не ломает разбор', () => {
    expect(resolveLocale(';;;', ',,,', '  ')).toBe(DEFAULT_LOCALE);
    expect(isLocale('ru')).toBe(true);
    expect(isLocale('de')).toBe(false);
    expect(isLocale(42)).toBe(false);
  });
});
