process.env.NODE_ENV = 'test';

import { BadRequestException } from '@nestjs/common';
import type { Locale } from '@aurum/shared';
import { I18nService } from '../../i18n/i18n.service';
import { validateCron, validateCronField } from './cron-fields';
import { SCHEDULE_PRESETS } from '@aurum/shared';

/**
 * Поля cron.
 *
 * Расписание с опечаткой не «не срабатывает», а срабатывает не тогда:
 * ночной бэкап, запускающийся ежеминутно, кладёт сервер. Поэтому «почти
 * правильные» выражения отвергаются наравне с явно неправильными.
 */
describe('validateCronField', () => {
  it('принимает формы, которые действительно пишут', () => {
    expect(validateCronField('minute', '*')).toBe('*');
    expect(validateCronField('minute', '0')).toBe('0');
    expect(validateCronField('minute', '0,30')).toBe('0,30');
    expect(validateCronField('hour', '1-5')).toBe('1-5');
    expect(validateCronField('hour', '*/6')).toBe('*/6');
    expect(validateCronField('minute', '0-30/5')).toBe('0-30/5');
  });

  it('проверяет границы каждого поля', () => {
    expect(validateCronField('minute', '59')).toBe('59');
    expect(() => validateCronField('minute', '60')).toThrow(BadRequestException);
    expect(validateCronField('hour', '23')).toBe('23');
    expect(() => validateCronField('hour', '24')).toThrow(BadRequestException);
    expect(validateCronField('dayOfMonth', '31')).toBe('31');
    expect(() => validateCronField('dayOfMonth', '0')).toThrow(BadRequestException);
    expect(validateCronField('month', '12')).toBe('12');
    expect(() => validateCronField('month', '13')).toThrow(BadRequestException);
  });

  // 0 и 7 — оба воскресенье, так принято в cron.
  it('день недели допускает и 0, и 7', () => {
    expect(validateCronField('dayOfWeek', '0')).toBe('0');
    expect(validateCronField('dayOfWeek', '7')).toBe('7');
    expect(() => validateCronField('dayOfWeek', '8')).toThrow(BadRequestException);
  });

  it('мусор отвергается', () => {
    for (const bad of ['', '   ', 'abc', '5x', '*/', '*/0', '1-2-3', '-5', '5-', '**']) {
      expect(() => validateCronField('minute', bad)).toThrow(BadRequestException);
    }
  });

  it('в тексте ошибки названо поле, а не абстрактное «неверно»', () => {
    // Название поля едет ключом и переводится на краю — «пустое поле» без
    // указания, какое именно, заставляет человека проверять все пять.
    const i18n = new I18nService();
    const say = (field: 'dayOfWeek' | 'hour', value: string, locale: Locale) => {
      try {
        validateCronField(field, value);
      } catch (e) {
        const body = (e as BadRequestException).getResponse() as {
          message: string;
          i18nKeys: { field: string };
        };
        return i18n.t(locale, body.message, { field: i18n.t(locale, body.i18nKeys.field) });
      }
      throw new Error('ожидалась ошибка');
    };

    expect(say('dayOfWeek', '9', 'ru')).toMatch(/дня недели/);
    expect(say('hour', '99', 'ru')).toMatch(/часа/);
    // И на других языках тоже: подставленное имя поля — тоже строка словаря,
    // а не кусок, случайно оставшийся русским посреди переведённой фразы.
    expect(say('dayOfWeek', '9', 'en')).toMatch(/day of week/);
    expect(say('hour', '99', 'pl')).toMatch(/godziny/);
  });
});

describe('validateCron', () => {
  it('все пресеты интерфейса проходят проверку', () => {
    // Пресет, который не проходит собственную проверку, — это ошибка,
    // которую иначе нашёл бы пользователь.
    for (const preset of SCHEDULE_PRESETS) {
      expect(() => validateCron(preset.cron)).not.toThrow();
    }
  });

  it('одно битое поле роняет весь набор', () => {
    expect(() =>
      validateCron({ minute: '0', hour: '99', dayOfMonth: '*', month: '*', dayOfWeek: '*' }),
    ).toThrow(BadRequestException);
  });
});
