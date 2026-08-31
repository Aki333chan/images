/**
 * Значок предмета для сетки инвентаря.
 *
 * ПОЧЕМУ НЕ НАСТОЯЩИЕ ТЕКСТУРЫ. Их было три способа достать, и все три хуже:
 *
 *   через companion — плагину пришлось бы вынимать текстуры из jar игры и
 *   отдавать их панели. Это работа на игровом сервере, на котором прямо
 *   сейчас играют, ради картинки в админке. Сотни PNG на каждое открытие
 *   карточки — это трафик через туннель и нагрузка там, где её быть не должно;
 *
 *   с внешнего CDN — панель стоит за своей CSP, и новый внешний домен в ней
 *   означает правку конфига живого сервера. Ровно по этой причине и иконки
 *   Phosphor лежат в исходниках, а не приезжают пакетом;
 *
 *   положить атлас текстур в панель — это чужие ассеты Mojang в репозитории.
 *
 * Поэтому значок рисуется здесь: цвет берётся от материала, глиф — от вида
 * предмета. Ни одного запроса, ни байта на игровом сервере, и работает для
 * модовых предметов, о которых панель не знает ничего, кроме идентификатора.
 *
 * Это не попытка подделать вид игры. Задача скромнее: чтобы алмазный меч и
 * стопка булыжника различались с одного взгляда, а не читались по буквам.
 */

export interface ItemIcon {
  /** Ключ глифа; сам контур — в ITEM_GLYPHS компонента. */
  glyph: ItemGlyph;
  /** Цвет глифа и рамки. */
  color: string;
  /** Подложка плитки. */
  background: string;
}

export type ItemGlyph =
  | 'sword'
  | 'tool'
  | 'bow'
  | 'armor'
  | 'block'
  | 'food'
  | 'potion'
  | 'ingot'
  | 'gem'
  | 'seed'
  | 'egg'
  | 'book'
  | 'fire'
  | 'chest'
  | 'item';

/** Палитра: у каждого материала свой цвет, узнаваемый по самой игре. */
const MATERIAL_COLORS: [RegExp, string][] = [
  [/netherite/, '#7a6a63'],
  [/diamond/, '#4aedd9'],
  [/^gold|golden_|gold_/, '#fcd34d'],
  [/emerald/, '#34d399'],
  [/lapis/, '#3b82f6'],
  [/amethyst/, '#a78bfa'],
  [/redstone/, '#ef4444'],
  [/copper/, '#e07b53'],
  [/iron|chainmail|anvil/, '#cbd5e1'],
  [/quartz/, '#f1f5f9'],
  [/coal|obsidian|basalt|blackstone/, '#4b5563'],
  [/leather|dirt|clay|terracotta/, '#b08968'],
  [/wood|log|plank|oak|spruce|birch|jungle|acacia|cherry|bamboo|crimson|warped/, '#a1743c'],
  [/stone|cobble|gravel|andesite|diorite|granite|deepslate/, '#94a3b8'],
  [/wool|concrete|glass|carpet/, '#e2e8f0'],
  [/water|ice|prismarine|aqua|turtle/, '#38bdf8'],
  [/lava|magma|blaze|fire|torch|nether_brick/, '#fb923c'],
  [/grass|leaves|moss|sapling|vine|kelp|melon|cactus/, '#4ade80'],
  [/potion|dragon|ender|shulker|chorus/, '#c084fc'],
  [/wheat|hay|bread|straw|sand/, '#facc15'],
];

/**
 * Вид предмета. Порядок важен: правила проверяются сверху вниз, и более
 * узкие обязаны идти раньше — «golden_apple» это еда, а не золотой слиток,
 * «enchanted_book» книга, а не зачарование.
 */
const GLYPH_RULES: [RegExp, ItemGlyph][] = [
  [/sword|trident|blade/, 'sword'],
  [/pickaxe|axe|shovel|hoe|shears|flint_and_steel|brush/, 'tool'],
  [/bow$|crossbow|arrow|quiver/, 'bow'],
  [/helmet|chestplate|leggings|boots|shield|elytra|turtle_scute/, 'armor'],
  [/potion|bottle|bucket|honey|milk/, 'potion'],
  [/apple|bread|beef|porkchop|chicken|mutton|rabbit|cod|salmon|carrot|potato|beetroot|melon|berries|stew|soup|cake|cookie|pie|dried_kelp/, 'food'],
  [/seeds|sapling|wheat$|sugar_cane|bamboo$|kelp$|flower|tulip|rose|poppy|dandelion/, 'seed'],
  [/egg|spawn/, 'egg'],
  [/book|map|paper|banner_pattern|recovery_compass|compass|clock/, 'book'],
  [/ingot|nugget|scrap|rod|stick|bone$|string|leather$|feather|gunpowder|blaze_powder/, 'ingot'],
  [/diamond$|emerald$|amethyst|quartz$|lapis_lazuli|pearl|shard|prismarine_crystals|nether_star|heart_of/, 'gem'],
  [/torch|campfire|magma|blaze|fire_charge|candle/, 'fire'],
  [/chest|barrel|shulker_box|bundle|hopper|furnace|dispenser|dropper/, 'chest'],
  [
    /block|planks|log$|stone|cobble|dirt|sand|gravel|wool|concrete|glass|brick|terracotta|ore$|deepslate|netherrack|obsidian|slab|stairs|fence|wall$|door$|trapdoor/,
    'block',
  ],
];

const DEFAULT_COLOR = '#cbd5e1';

/**
 * Значок по идентификатору предмета.
 *
 * Пространство имён отбрасывается: `minecraft:diamond_sword` и
 * `create:diamond_sword` для нас одно и то же, а держать правила на каждый
 * мод бессмысленно — их и не перечислить.
 */
export function itemIcon(id: string): ItemIcon {
  const key = id.toLowerCase().replace(/^[a-z0-9_.-]+:/, '');

  let color = DEFAULT_COLOR;
  for (const [pattern, value] of MATERIAL_COLORS) {
    if (pattern.test(key)) {
      color = value;
      break;
    }
  }

  let glyph: ItemGlyph = 'item';
  for (const [pattern, value] of GLYPH_RULES) {
    if (pattern.test(key)) {
      glyph = value;
      break;
    }
  }

  return { glyph, color, background: `${color}1f` };
}

/**
 * Короткая подпись под значком.
 *
 * Значок говорит «это меч», подпись — «какой именно»; вместе они читаются
 * быстрее, чем полный идентификатор в две строки мелким шрифтом. Материал из
 * начала имени убирается: он уже передан цветом.
 */
export function itemShortLabel(id: string): string {
  const key = id.toLowerCase().replace(/^[a-z0-9_.-]+:/, '');
  const parts = key.split('_');
  const last = parts.length > 1 ? parts[parts.length - 1] : key;
  return (last ?? key).replace(/^\w/, (c) => c.toUpperCase());
}
