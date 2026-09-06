import { knownByName, lastSeenText } from './player-name';
import { makeTranslator } from '@aurum/shared';
import ru from '../../i18n/catalogs/ru.json';
import type { MinecraftKnownPlayerDto } from '@aurum/shared';

function player(name: string, extra: Partial<MinecraftKnownPlayerDto> = {}): MinecraftKnownPlayerDto {
  return {
    uuid: `u-${name}`,
    name,
    alias: null,
    op: false,
    online: false,
    registered: null,
    lastSeen: null,
    ...extra,
  };
}

describe('справочник по нику', () => {
  it('ищет без учёта регистра: список онлайна приходит по RCON как есть', () => {
    const map = knownByName([player('Steve', { op: true, alias: 'Стив' })]);

    expect(map.get('steve')?.op).toBe(true);
    expect(map.get('steve')?.alias).toBe('Стив');
  });

  it('незнакомый ник просто не находится — звёздочки не будет, и это нормально', () => {
    expect(knownByName([player('Steve')]).get('alex')).toBeUndefined();
  });
});

describe('когда заходил в последний раз', () => {
  // Переводчик и форматтер даты приходят аргументами: функция чистая и вне
  // React, а язык у панели свой на каждого сотрудника.
  const t = makeTranslator('ru', ru as never, ru as never);
  const asDate = (iso: string) => new Date(iso).toLocaleDateString('ru-RU');
  const seen = (iso: string | null) => lastSeenText(iso, t, asDate);
  const now = new Date('2026-09-03T12:00:00Z').getTime();

  beforeEach(() => jest.useFakeTimers().setSystemTime(now));
  afterEach(() => jest.useRealTimers());

  it('без даты — прочерк, а не «01.01.1970»', () => {
    expect(seen(null)).toBe('—');
  });

  it('битую дату тоже показывает прочерком', () => {
    expect(seen('не дата')).toBe('—');
  });

  it('считает минуты, часы и дни', () => {
    expect(seen(new Date(now - 30_000).toISOString())).toBe('только что');
    expect(seen(new Date(now - 5 * 60_000).toISOString())).toBe('5 мин назад');
    expect(seen(new Date(now - 3 * 3_600_000).toISOString())).toBe('3 ч назад');
    expect(seen(new Date(now - 4 * 86_400_000).toISOString())).toBe('4 дн назад');
  });

  it('дальше месяца — обычная дата: «47 дн назад» уже ни о чём не говорит', () => {
    expect(seen(new Date(now - 60 * 86_400_000).toISOString())).toMatch(/\d{2}\.\d{2}\.\d{4}/);
  });
});
