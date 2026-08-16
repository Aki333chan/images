import { buildRows } from './heatmap';

/**
 * Тесты гоняются в поясе, заданном переменной TZ (см. скрипт test:web).
 * Смысл именно в неUTC-поясе: раскладка по суткам и часам должна совпадать
 * с тем, что видит человек у себя, а не с UTC.
 */
const offsetHours = -new Date('2026-08-15T12:00:00Z').getTimezoneOffset() / 60;

describe('buildRows', () => {
  it('строит запрошенное число суток по 24 часа', () => {
    const rows = buildRows([], 7);
    expect(rows).toHaveLength(7);
    for (const row of rows) expect(row.hours).toHaveLength(24);
  });

  it('без замеров все ячейки — null, а не нули', () => {
    const rows = buildRows([], 3);
    expect(rows.flatMap((r) => r.hours).every((h) => h === null)).toBe(true);
  });

  it('различает «никого» и «нет данных»', () => {
    const at = new Date();
    at.setMinutes(0, 0, 0);
    const rows = buildRows([{ bucket: at.toISOString(), online: 0 }], 1);

    const values = rows[0]!.hours;
    expect(values.filter((h) => h === 0)).toHaveLength(1);
    expect(values.filter((h) => h === null)).toHaveLength(23);
  });

  it('кладёт замер в местный час, а не в час UTC', () => {
    // Полдень UTC в поясе со смещением N — это (12 + N) местного времени.
    //
    // Math.floor здесь не для красоты: есть пояса с получасовым смещением
    // (Аделаида — UTC+9:30), где часовой слот UTC приходится на середину
    // местного часа: 12:00 UTC = 21:30 местного. Замер относим к часу 21 —
    // тому, в котором он начался. Размазывать одно значение по двум ячейкам
    // было бы хуже: график перестал бы соответствовать числу игроков.
    const rows = buildRows([{ bucket: '2026-08-15T12:00:00.000Z', online: 5 }], 400);
    const withValue = rows.flatMap((r) => r.hours.map((h, hour) => ({ h, hour })));
    const hit = withValue.find((c) => c.h === 5);

    const expectedHour = ((Math.floor(12 + offsetHours) % 24) + 24) % 24;
    expect(hit?.hour).toBe(expectedHour);
  });

  it('самый свежий час не теряется — именно он интереснее всего', () => {
    // Регрессия: прежняя реализация сдвигала готовую сетку индексами и
    // срезала последние часы в поясах восточнее UTC.
    const now = new Date();
    const bucket = new Date(now);
    bucket.setMinutes(0, 0, 0);

    const rows = buildRows([{ bucket: bucket.toISOString(), online: 9 }], 7);

    const last = rows[rows.length - 1]!;
    expect(last.hours[now.getHours()]).toBe(9);
  });

  it('замеры за пределами окна отбрасываются, а не сдвигают сетку', () => {
    const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
    const rows = buildRows([{ bucket: old.toISOString(), online: 4 }], 3);

    expect(rows).toHaveLength(3);
    expect(rows.flatMap((r) => r.hours).every((h) => h === null)).toBe(true);
  });

  it('переживает мусор в метке времени', () => {
    const rows = buildRows([{ bucket: 'не-дата', online: 3 }], 2);
    expect(rows.flatMap((r) => r.hours).every((h) => h === null)).toBe(true);
  });

  it('последняя строка — сегодняшние сутки', () => {
    const rows = buildRows([], 5);
    const today = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    expect(rows[rows.length - 1]!.date).toBe(
      `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`,
    );
  });
});
