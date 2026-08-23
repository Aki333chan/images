import { BadRequestException } from '@nestjs/common';

/**
 * Проверка полей cron перед отправкой в Pterodactyl.
 *
 * ЗАЧЕМ. Панель принимает cron пятью отдельными полями и складывает их в
 * своё расписание. Мусор в поле она отвергнет, но сообщение будет её —
 * английское и про Laravel-правило. Здесь отказ по-русски и до похода.
 *
 * Важнее другое: расписание с опечаткой не «не сработает», а сработает не
 * тогда — например, каждую минуту вместо раза в сутки. Ночной бэкап,
 * запускающийся ежеминутно, кладёт сервер.
 */

/** Границы допустимых чисел в каждом поле. */
const RANGES: Record<CronField, { min: number; max: number }> = {
  minute: { min: 0, max: 59 },
  hour: { min: 0, max: 23 },
  dayOfMonth: { min: 1, max: 31 },
  month: { min: 1, max: 12 },
  // 0 и 7 — оба воскресенье, так принято в cron.
  dayOfWeek: { min: 0, max: 7 },
};

export type CronField = 'minute' | 'hour' | 'dayOfMonth' | 'month' | 'dayOfWeek';

export interface CronParts {
  minute: string;
  hour: string;
  dayOfMonth: string;
  month: string;
  dayOfWeek: string;
}

const LABELS: Record<CronField, string> = {
  minute: 'минуты',
  hour: 'часа',
  dayOfMonth: 'дня месяца',
  month: 'месяца',
  dayOfWeek: 'дня недели',
};

/**
 * Разбирает одно поле cron.
 *
 * Поддерживаются формы, которые действительно пишут: `*`, `5`, `1,15`,
 * `1-5`, `*​/6`, `1-20/2`. Всё остальное отвергается — «почти правильное»
 * выражение опаснее явно неправильного.
 */
export function validateCronField(field: CronField, raw: string): string {
  const value = (raw ?? '').trim();
  if (value === '') throw new BadRequestException(`Пустое поле ${LABELS[field]}`);
  if (value.length > 64) throw new BadRequestException(`Слишком длинное поле ${LABELS[field]}`);

  for (const part of value.split(',')) {
    if (!isValidPart(field, part.trim())) {
      throw new BadRequestException(`Некорректное значение ${LABELS[field]}: «${part.trim()}»`);
    }
  }
  return value;
}

function isValidPart(field: CronField, part: string): boolean {
  if (part === '') return false;

  // Шаг: «*/6» или «1-20/2».
  const slash = part.split('/');
  if (slash.length > 2) return false;
  if (slash.length === 2) {
    const step = Number(slash[1]);
    if (!Number.isInteger(step) || step < 1) return false;
    return isValidPart(field, slash[0]!);
  }

  const base = slash[0]!;
  if (base === '*') return true;

  // Диапазон: «1-5».
  const dash = base.split('-');
  if (dash.length > 2) return false;
  return dash.every((n) => isInRange(field, n));
}

function isInRange(field: CronField, raw: string): boolean {
  if (!/^\d+$/.test(raw)) return false;
  const value = Number(raw);
  const { min, max } = RANGES[field];
  return value >= min && value <= max;
}

/** Проверяет все пять полей разом. */
export function validateCron(parts: CronParts): CronParts {
  return {
    minute: validateCronField('minute', parts.minute),
    hour: validateCronField('hour', parts.hour),
    dayOfMonth: validateCronField('dayOfMonth', parts.dayOfMonth),
    month: validateCronField('month', parts.month),
    dayOfWeek: validateCronField('dayOfWeek', parts.dayOfWeek),
  };
}
