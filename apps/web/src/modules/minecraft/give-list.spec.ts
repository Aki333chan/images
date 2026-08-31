import { MAX_GIVE_COUNT, MAX_GIVE_ENTRIES, parseGiveList } from './give-list';

describe('разбор списка выдачи', () => {
  it('понимает все три способа написать количество', () => {
    const { items, errors } = parseGiveList('minecraft:stone 64\nstone x16\nstone*2');
    expect(errors).toEqual([]);
    expect(items).toEqual([
      { id: 'minecraft:stone', count: 64 },
      { id: 'stone', count: 16 },
      { id: 'stone', count: 2 },
    ]);
  });

  it('без количества выдаётся один предмет', () => {
    expect(parseGiveList('diamond').items).toEqual([{ id: 'diamond', count: 1 }]);
  });

  it('список можно писать и одной строкой через запятую', () => {
    // Списки часто копируют из чата, а не набирают по строке.
    expect(parseGiveList('stone 2, dirt 3; sand').items).toEqual([
      { id: 'stone', count: 2 },
      { id: 'dirt', count: 3 },
      { id: 'sand', count: 1 },
    ]);
  });

  it('пустые строки не считаются ошибкой', () => {
    const { items, errors } = parseGiveList('\n\nstone\n\n');
    expect(items).toHaveLength(1);
    expect(errors).toEqual([]);
  });

  it('предметы модов проходят: существование проверяет игровой сервер', () => {
    // Перечень материалов зависит от версии и модов — зашитый в панель
    // список устарел бы к следующему обновлению.
    expect(parseGiveList('create:brass_ingot 4').items).toEqual([
      { id: 'create:brass_ingot', count: 4 },
    ]);
  });

  it('мусорная строка названа поимённо, остальные проходят', () => {
    const { items, errors } = parseGiveList('дай мне алмазов\nstone 1');
    expect(items).toEqual([{ id: 'stone', count: 1 }]);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('дай мне алмазов');
  });

  it('количество за пределом отклоняется до отправки', () => {
    const { items, errors } = parseGiveList(`stone ${MAX_GIVE_COUNT + 1}`);
    expect(items).toEqual([]);
    expect(errors[0]).toContain(String(MAX_GIVE_COUNT));

    expect(parseGiveList('stone 0').items).toEqual([]);
  });

  it('слишком длинный список не отправляется частично', () => {
    // Половина списка на сервере и половина в ошибке — худший исход:
    // непонятно, что уже выдано.
    const text = Array.from({ length: MAX_GIVE_ENTRIES + 1 }, () => 'stone').join('\n');
    const { items, errors } = parseGiveList(text);
    expect(items).toEqual([]);
    expect(errors[0]).toContain(String(MAX_GIVE_ENTRIES));
  });
});
