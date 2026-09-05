/**
 * Потребление ресурсов сервера: нормализация и пороги.
 *
 * ГЛАВНОЕ, ЧТО ЗДЕСЬ ИСПРАВЛЕНО. У Pterodactyl лимит CPU задаётся в процентах
 * ОТ ОДНОГО ЯДРА: 100 — одно ядро, 200 — два, 0 — без ограничения. Текущее
 * потребление (`cpu_absolute`) приходит в тех же единицах — Wings умножает
 * долю на число ядер, поэтому сервер, занявший два ядра целиком, показывает
 * ~200.
 *
 * Значит «85%» само по себе ничего не значит: на сервере с лимитом 400 это
 * пятая часть выделенного, а на сервере с лимитом 100 — почти потолок.
 * Панель раньше сравнивала это число с 90 и 70, как будто потолок всегда 100,
 * и красила в красный совершенно здоровый сервер, а перегруженный — в зелёный.
 *
 * Сверено по исходникам: `StatsTransformer` (`resources.cpu_absolute`) и
 * `ServerTransformer` (`limits.cpu`) панели Pterodactyl, плюс
 * `calculateDockerAbsoluteCpu` в Wings — там видно домножение на число ядер.
 */

/** Тон индикатора. `unknown` — сравнивать не с чем, красить нельзя. */
export type ResourceTone = 'normal' | 'warn' | 'bad' | 'unknown';

/** Загрузка CPU, приведённая к выделенному серверу лимиту. */
export interface CpuUsage {
  /** Сырое значение Pterodactyl: 200 = два ядра целиком. */
  absolutePercent: number;
  /** Лимит в тех же единицах. 0 — без ограничения. */
  limitPercent: number;
  /**
   * Доля от лимита в процентах. null — лимита нет, и доли не существует:
   * делить на ноль нельзя, а придумывать знаменатель — врать.
   */
  percentOfLimit: number | null;
  /** true — лимит не задан (limits.cpu === 0). */
  unlimited: boolean;
}

export function cpuUsage(absolutePercent: number, limitPercent: number | null): CpuUsage {
  const limit = Number.isFinite(limitPercent as number) ? Math.max(0, limitPercent ?? 0) : 0;
  const absolute = Number.isFinite(absolutePercent) ? Math.max(0, absolutePercent) : 0;
  return {
    absolutePercent: absolute,
    limitPercent: limit,
    percentOfLimit: limit > 0 ? (absolute / limit) * 100 : null,
    unlimited: limit === 0,
  };
}

/**
 * Загрузка памяти в долях от лимита. Устроена так же, как CPU, и по той же
 * причине: 0 в лимите у Pterodactyl означает «без ограничения».
 */
export function memoryUsage(
  usedBytes: number,
  limitBytes: number | null,
): { usedBytes: number; limitBytes: number; percentOfLimit: number | null; unlimited: boolean } {
  const limit = Math.max(0, limitBytes ?? 0);
  const used = Math.max(0, usedBytes ?? 0);
  return {
    usedBytes: used,
    limitBytes: limit,
    percentOfLimit: limit > 0 ? (used / limit) * 100 : null,
    unlimited: limit === 0,
  };
}

/** Порог «жёлтого» и «красного» в процентах ОТ ЛИМИТА, а не от абстрактных 100. */
export const RESOURCE_WARN_PERCENT = 75;
export const RESOURCE_BAD_PERCENT = 90;

/**
 * Тон индикатора по доле от лимита.
 *
 * null на входе — лимита нет. Тогда тон `unknown`: перегрузку показывать не
 * от чего, потолка не существует. Красить такой сервер в красный по сырому
 * числу было бы ровно той ошибкой, которую этот файл и чинит.
 */
export function resourceTone(percentOfLimit: number | null): ResourceTone {
  if (percentOfLimit === null || !Number.isFinite(percentOfLimit)) return 'unknown';
  if (percentOfLimit >= RESOURCE_BAD_PERCENT) return 'bad';
  if (percentOfLimit >= RESOURCE_WARN_PERCENT) return 'warn';
  return 'normal';
}

/**
 * Переводчик, который эти функции принимают аргументом.
 *
 * Они живут в shared и зовутся из двух мест с разными языками: из панели —
 * на языке того, кто смотрит, и из рассыльщика писем — на языке того, кому
 * письмо. Хука здесь взять неоткуда, поэтому переводчик приходит снаружи.
 *
 * По умолчанию — русский. Так вызовы, до которых перевод ещё не дошёл,
 * продолжают работать ровно как раньше, а не начинают показывать ключи.
 */
type Translate = (key: string, values?: Record<string, string>) => string;

const RU_STRINGS: Record<string, string> = {
  'size.b': 'Б',
  'size.kb': 'КБ',
  'size.mb': 'МБ',
  'size.gb': 'ГБ',
  'res.noLimitParens': '(без лимита)',
  'res.ofPercent': '{value} из {limit}%',
};

const RU: Translate = (key, values) => {
  const text = RU_STRINGS[key] ?? key;
  return values ? text.replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole) : text;
};

/**
 * Подпись загрузки CPU для человека.
 *
 * Рядом с индикатором показываем и абсолютные цифры: «107% из 200%» сразу
 * отвечает на вопрос «а сколько это в ядрах», которого нормализованная доля
 * не отвечает вовсе.
 */
export function formatCpu(usage: CpuUsage, t: Translate = RU): string {
  const absolute = `${round1(usage.absolutePercent)}%`;
  return usage.unlimited
    ? `${absolute} ${t('res.noLimitParens')}`
    : t('res.ofPercent', { value: absolute, limit: String(usage.limitPercent) });
}

/** «3.2 ГБ / 4 ГБ». Без лимита — только использованное. */
export function formatBytesUsage(usedBytes: number, limitBytes: number, t: Translate = RU): string {
  return limitBytes > 0
    ? `${formatBytes(usedBytes, t)} / ${formatBytes(limitBytes, t)}`
    : formatBytes(usedBytes, t);
}

export function formatBytes(bytes: number, t: Translate = RU): string {
  const gb = 1024 ** 3;
  const mb = 1024 ** 2;
  if (bytes >= gb) return `${round1(bytes / gb)} ${t('size.gb')}`;
  if (bytes >= mb) return `${Math.round(bytes / mb)} ${t('size.mb')}`;
  return `${Math.round(bytes / 1024)} ${t('size.kb')}`;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}
