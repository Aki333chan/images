import { sanitize } from './audit.interceptor';

describe('санитизация тела запроса для аудита', () => {
  // --- главное: двоичное тело не разворачивается по байтам

  it('от загружаемого файла остаётся только размер', () => {
    // Это регрессия с ценой в полторы минуты простоя всей панели: Buffer —
    // объект, но не массив, и раньше он уходил в ветку Object.entries, где
    // разворачивался по байтам.
    const result = sanitize(Buffer.alloc(15 * 1024 * 1024));
    expect(result).toBe('[двоичные данные, 15.0 МБ]');
  });

  it('маленький файл показывается в килобайтах', () => {
    expect(sanitize(Buffer.alloc(3 * 1024))).toBe('[двоичные данные, 3 КБ]');
  });

  it('разбор двоичного тела укладывается в миллисекунды', () => {
    // Порог с огромным запасом: правильная реализация читает только длину и
    // тратит доли миллисекунды. Прошлая версия на этом же входе работала
    // около пятидесяти секунд.
    const started = Date.now();
    sanitize(Buffer.alloc(15 * 1024 * 1024));
    expect(Date.now() - started).toBeLessThan(100);
  });

  it('другие виды на двоичные данные тоже не разворачиваются', () => {
    expect(sanitize(new Uint8Array(2048))).toBe('[двоичные данные, 2 КБ]');
    expect(sanitize(new ArrayBuffer(2048))).toBe('[двоичные данные, 2 КБ]');
  });

  // --- обычные тела по-прежнему читаемы

  it('обычное тело сохраняется как есть', () => {
    expect(sanitize({ target: 'Steve', reason: 'грубость' })).toEqual({
      target: 'Steve',
      reason: 'грубость',
    });
  });

  it('секреты по-прежнему вырезаются', () => {
    expect(sanitize({ login: 'gm', password: 'тайна', apiKey: 'ptlc_x' })).toEqual({
      login: 'gm',
      password: '[redacted]',
      apiKey: '[redacted]',
    });
  });

  it('вложенность разбирается, но не бесконечно', () => {
    const deep = { a: { b: { c: { d: { e: 'дно' } } } } };
    const out = sanitize(deep) as Record<string, never>;
    expect(out.a).toBeDefined();
  });

  // --- страховка от неожиданно большого тела

  it('длинная строка обрезается с пометкой', () => {
    const out = sanitize('я'.repeat(5000)) as string;
    expect(out.length).toBeLessThan(2100);
    expect(out.endsWith('[обрезано]')).toBe(true);
  });

  it('короткая строка не трогается', () => {
    expect(sanitize('всё в порядке')).toBe('всё в порядке');
  });

  it('объект с сотнями полей урезается, и об этом сказано', () => {
    const wide: Record<string, number> = {};
    for (let i = 0; i < 200; i++) wide[`field${i}`] = i;

    const out = sanitize(wide) as Record<string, unknown>;
    // Молча урезанный журнал хуже урезанного с пометкой: по нему делают
    // выводы о том, что произошло.
    expect(Object.keys(out).length).toBe(51);
    expect(out['…']).toBe('[ещё 150 полей опущено]');
  });

  it('массив по-прежнему ограничен двадцатью элементами', () => {
    const out = sanitize(Array.from({ length: 100 }, (_, i) => i)) as number[];
    expect(out).toHaveLength(20);
  });

  it('null и примитивы проходят насквозь', () => {
    expect(sanitize(null)).toBeNull();
    expect(sanitize(42)).toBe(42);
    expect(sanitize(undefined)).toBeUndefined();
  });
});
