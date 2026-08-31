import { itemIcon, itemShortLabel } from './item-icon';

describe('значки предметов', () => {
  it('вид предмета важнее материала в имени', () => {
    // «golden_apple» — еда, а не золотой слиток; «diamond_sword» — меч, а не
    // самоцвет. Ради этого правила и упорядочены сверху вниз.
    expect(itemIcon('minecraft:golden_apple').glyph).toBe('food');
    expect(itemIcon('minecraft:diamond_sword').glyph).toBe('sword');
    expect(itemIcon('minecraft:diamond').glyph).toBe('gem');
    expect(itemIcon('minecraft:diamond_pickaxe').glyph).toBe('tool');
    expect(itemIcon('minecraft:diamond_helmet').glyph).toBe('armor');
  });

  it('материал задаёт цвет независимо от вида', () => {
    const sword = itemIcon('minecraft:diamond_sword');
    const helmet = itemIcon('minecraft:diamond_helmet');
    expect(sword.color).toBe(helmet.color);
    expect(sword.glyph).not.toBe(helmet.glyph);
  });

  it('пространство имён не влияет: предметы модов тоже получают значок', () => {
    expect(itemIcon('create:diamond_sword')).toEqual(itemIcon('minecraft:diamond_sword'));
  });

  it('незнакомый предмет получает значок по умолчанию, а не пустоту', () => {
    const icon = itemIcon('somemod:whatchamacallit');
    expect(icon.glyph).toBe('item');
    expect(icon.color).toMatch(/^#/);
    expect(icon.background).toMatch(/^#/);
  });

  it('регистр не имеет значения', () => {
    expect(itemIcon('MINECRAFT:Diamond_Sword').glyph).toBe('sword');
  });

  it('подпись оставляет то, чего не сказал цвет', () => {
    expect(itemShortLabel('minecraft:diamond_sword')).toBe('Sword');
    expect(itemShortLabel('minecraft:cobblestone')).toBe('Cobblestone');
  });
});
