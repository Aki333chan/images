/** Раскладка замеров онлайна по местным суткам и часам — для тепловой карты. */

export interface HeatmapRow {
  /** Местная дата, YYYY-MM-DD. */
  date: string;
  /** 24 значения по местным часам; null — замеров не было. */
  hours: (number | null)[];
}

/**
 * Раскладка замеров по местным суткам и часам.
 *
 * Замер приходит с меткой начала часа в UTC. Куда он попадёт в сетке,
 * определяет только браузер: `getFullYear`/`getHours` у Date отдают местное
 * время с уже учтённым смещением и переходами на летнее время. Поэтому
 * сдвигать индексы руками не нужно — и нельзя: на границах суток такой
 * сдвиг срезает самые свежие часы.
 */
export function buildRows(
  samples: { bucket: string; online: number }[],
  days: number,
): HeatmapRow[] {
  const pad = (n: number) => String(n).padStart(2, '0');
  const localDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  // Пустая сетка за последние `days` местных суток, последняя — сегодня.
  const rows = new Map<string, (number | null)[]>();
  const today = new Date();
  for (let i = days - 1; i >= 0; i--) {
    const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
    rows.set(localDate(day), Array<number | null>(24).fill(null));
  }

  for (const sample of samples) {
    const at = new Date(sample.bucket);
    if (Number.isNaN(at.getTime())) continue;
    const hours = rows.get(localDate(at));
    // Замеры из запаса (сутки сверху) за пределы сетки не попадают.
    if (!hours) continue;
    hours[at.getHours()] = sample.online;
  }

  return [...rows.entries()].map(([date, hours]) => ({ date, hours }));
}
