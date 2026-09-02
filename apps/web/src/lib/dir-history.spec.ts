import {
  canGoBack,
  canGoForward,
  currentPath,
  goBack,
  goForward,
  initialHistory,
  visit,
} from './dir-history';

describe('история папок', () => {
  it('начинается с одной записи, идти некуда', () => {
    const h = initialHistory('/');
    expect(currentPath(h)).toBe('/');
    expect(canGoBack(h)).toBe(false);
    expect(canGoForward(h)).toBe(false);
    expect(goBack(h)).toBeNull();
    expect(goForward(h)).toBeNull();
  });

  it('ходит назад и вперёд по посещённым папкам', () => {
    let h = initialHistory('/');
    h = visit(h, '/plugins');
    h = visit(h, '/plugins/AurumGuilds');
    expect(currentPath(h)).toBe('/plugins/AurumGuilds');

    h = goBack(h)!;
    expect(currentPath(h)).toBe('/plugins');
    h = goBack(h)!;
    expect(currentPath(h)).toBe('/');
    expect(canGoBack(h)).toBe(false);

    h = goForward(h)!;
    expect(currentPath(h)).toBe('/plugins');
    expect(canGoForward(h)).toBe(true);
  });

  it('переход после «назад» отрезает то, что было впереди', () => {
    let h = initialHistory('/');
    h = visit(h, '/plugins');
    h = visit(h, '/plugins/AurumGuilds');
    h = goBack(h)!;
    h = goBack(h)!;

    h = visit(h, '/world');
    expect(currentPath(h)).toBe('/world');
    expect(canGoForward(h)).toBe(false);
    expect(h.entries).toEqual(['/', '/world']);
  });

  it('повторный вход в ту же папку историю не копит', () => {
    // Вкладка перечитывает папку после загрузки, удаления и переименования.
    // Без этой проверки «назад» ходило бы по одинаковым записям на месте.
    let h = initialHistory('/');
    h = visit(h, '/plugins');
    const same = visit(h, '/plugins');
    expect(same).toBe(h);
    expect(same.entries).toEqual(['/', '/plugins']);
  });

  it('возврат в уже посещённую папку — это новая запись, а не прыжок', () => {
    let h = initialHistory('/');
    h = visit(h, '/plugins');
    h = visit(h, '/');
    expect(h.entries).toEqual(['/', '/plugins', '/']);
    expect(canGoBack(h)).toBe(true);
    expect(currentPath(goBack(h)!)).toBe('/plugins');
  });
});
