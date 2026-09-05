import { makeTranslator, type Catalog } from '@aurum/shared';

const RU: Catalog = {
  greeting: 'Привет, {name}!',
  players: { one: '{count} игрок', few: '{count} игрока', many: '{count} игроков', other: '{count} игрока' },
  onlyRu: 'Только по-русски',
};

const EN: Catalog = {
  greeting: 'Hello, {name}!',
  players: { one: '{count} player', other: '{count} players' },
};

const PL: Catalog = {
  greeting: 'Cześć, {name}!',
  players: { one: '{count} gracz', few: '{count} gracze', many: '{count} graczy', other: '{count} gracza' },
};

// makeTranslator живёт в packages/shared: одна реализация на веб и API.
describe('перевод строки', () => {
  it('подставляет значения', () => {
    expect(makeTranslator('ru', RU, RU)('greeting', { name: 'Стив' })).toBe('Привет, Стив!');
    expect(makeTranslator('en', EN, RU)('greeting', { name: 'Steve' })).toBe('Hello, Steve!');
  });

  it('плейсхолдер без значения остаётся видимым, а не пустым', () => {
    // Пустая строка на его месте выглядела бы законченной фразой, и
    // незаполненный перевод дожил бы до продакшена.
    expect(makeTranslator('ru', RU, RU)('greeting')).toBe('Привет, {name}!');
  });

  it('ключа нет нигде — показывается сам ключ', () => {
    expect(makeTranslator('ru', RU, RU)('нет.такого')).toBe('нет.такого');
  });

  it('нет перевода — берётся русский, а не пустота', () => {
    expect(makeTranslator('en', EN, RU)('onlyRu')).toBe('Только по-русски');
  });
});

describe('множественные числа', () => {
  const ru = makeTranslator('ru', RU, RU);
  const en = makeTranslator('en', EN, RU);
  const pl = makeTranslator('pl', PL, RU);

  it('русский: три формы, включая ловушку 11-14', () => {
    expect(ru('players', { count: 1 })).toBe('1 игрок');
    expect(ru('players', { count: 3 })).toBe('3 игрока');
    expect(ru('players', { count: 5 })).toBe('5 игроков');
    expect(ru('players', { count: 11 })).toBe('11 игроков');
    expect(ru('players', { count: 21 })).toBe('21 игрок');
  });

  it('английский: две формы', () => {
    expect(en('players', { count: 1 })).toBe('1 player');
    expect(en('players', { count: 2 })).toBe('2 players');
    expect(en('players', { count: 21 })).toBe('21 players');
  });

  it('польский: границы форм не такие, как в русском', () => {
    // Ровно то, ради чего формы считает Intl, а не наше правило: в польском
    // 22 попадает в few («22 gracze»), а 25 — в many («25 graczy»). В русском
    // 22 тоже few, а вот 12 — many в обоих языках, но не по одной причине.
    expect(pl('players', { count: 1 })).toBe('1 gracz');
    expect(pl('players', { count: 2 })).toBe('2 gracze');
    expect(pl('players', { count: 5 })).toBe('5 graczy');
    expect(pl('players', { count: 12 })).toBe('12 graczy');
    expect(pl('players', { count: 22 })).toBe('22 gracze');
    expect(pl('players', { count: 25 })).toBe('25 graczy');
  });

  it('формы есть, а числа не передали — берётся other и ничего не падает', () => {
    expect(ru('players')).toBe('{count} игрока');
  });
});
