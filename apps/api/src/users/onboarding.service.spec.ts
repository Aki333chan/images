process.env.NODE_ENV = 'test';

import { isValidStaffNickname, normalizeNickname } from './onboarding.service';

/**
 * Ник СОТРУДНИКА панели — не ник игрока Minecraft. Правила разные, и
 * проверять их надо отдельно, иначе легко скопировать чужие ограничения.
 */
describe('ник сотрудника', () => {
  it.each(['Ann', 'Большой Босс', 'admin_2', 'ГМ-1', 'x9'])('принимает «%s»', (value) => {
    expect(isValidStaffNickname(value)).toBe(true);
  });

  it.each([
    ['a', 'короче двух символов'],
    ['', 'пустой'],
    [' Ann', 'начинается с пробела'],
    ['_ann', 'начинается с подчёркивания'],
    ['ann@mail', 'спецсимвол'],
    ['a'.repeat(32), 'длиннее 31 символа'],
  ])('отклоняет «%s» (%s)', (value) => {
    expect(isValidStaffNickname(value)).toBe(false);
  });

  it('кириллица допустима — это ник для людей, а не для игрового сервера', () => {
    expect(isValidStaffNickname('Модератор Вася')).toBe(true);
  });

  it('ровно 31 символ проходит, 32 — нет', () => {
    expect(isValidStaffNickname('a'.repeat(31))).toBe(true);
    expect(isValidStaffNickname('a'.repeat(32))).toBe(false);
  });
});

describe('normalizeNickname', () => {
  it('уравнивает регистр и лишние пробелы', () => {
    expect(normalizeNickname('Big  Boss ')).toBe(normalizeNickname('big boss'));
  });

  it('различает действительно разные ники', () => {
    expect(normalizeNickname('Ann')).not.toBe(normalizeNickname('Anna'));
  });

  it('работает с кириллицей', () => {
    expect(normalizeNickname('ГМ')).toBe(normalizeNickname('гм'));
  });
});
