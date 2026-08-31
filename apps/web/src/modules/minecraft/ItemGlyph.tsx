import type { ItemGlyph as ItemGlyphKind } from './item-icon';

/**
 * Контуры значков предметов.
 *
 * Сетка 24×24, сплошная заливка currentColor — как у иконок интерфейса в
 * components/icons.tsx. Рисуем сами по той же причине, по которой там лежит
 * Phosphor: панель за собственной CSP, и новый внешний домен ради картинок
 * означает правку конфига живого сервера.
 *
 * Нарочно грубые: это не текстуры Minecraft и не попытка их изобразить.
 * Задача — чтобы меч, слиток и блок различались с одного взгляда.
 */
const PATHS: Record<ItemGlyphKind, string> = {
  sword: 'M12 1l2 3v10h-4V4zM7 14h10v2H7zM11 16h2v5h-2zM10 21h4v2h-4z',
  tool: 'M3 8c4-5 14-5 18 0l-2 2c-4-4-10-4-14 0zM11 8h2v14h-2z',
  bow: 'M7 2c6 3 6 17 0 20l1.6-.9c4.6-3.4 4.6-14.8 0-18.2zM6.4 3h1v18h-1zM4 11.4h12v1.2H4zM15 9l5 3-5 3z',
  armor: 'M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z',
  block: 'M12 2l9 5-9 5-9-5zM3 8.5l8 4.5v9l-8-4.5zM21 8.5l-8 4.5v9l8-4.5z',
  food: 'M11.5 1h1v4h-1zM7 5c2-1 4 0 5 2 1-2 3-3 5-2 3 1.5 3 7 1 11-1.5 3-3.5 4.5-6 4.5S7.5 19 6 16c-2-4-2-9.5 1-11z',
  potion: 'M10 1h4v3h-4zM12 4c4 0 6 3.5 6 8s-2.7 10-6 10-6-5.5-6-10 2-8 6-8z',
  ingot: 'M7 7h10l4 10H3z',
  gem: 'M12 2l8 8-8 12L4 10z',
  seed: 'M11 10h2v12h-2zM11 13C7.5 13 4.5 10.5 4.5 7c3.5 0 6.5 2.5 6.5 6zM13 15c0-4 3-7 6.5-7 0 4-3 7-6.5 7z',
  egg: 'M12 2c4 0 7 6 7 11a7 7 0 0 1-14 0c0-5 3-11 7-11z',
  book: 'M6 2h9l4 4v16H6z',
  fire: 'M12 2c1 4-3 5-3 9a3 3 0 0 0 6 0c0-1-.5-2-1-3 2 1 4 3 4 6a6 6 0 0 1-12 0c0-5 4-8 6-12z',
  chest: 'M3 5h18v5H3zM3 11h18v8H3zM11 8h2v5h-2z',
  item: 'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z',
};

export function ItemGlyph({ glyph, size = 18 }: { glyph: ItemGlyphKind; size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="currentColor"
      // Значок дублирует подпись под ним и заголовок ячейки — скринридеру
      // он ничего не добавляет.
      aria-hidden="true"
      focusable="false"
      dangerouslySetInnerHTML={{ __html: `<path d="${PATHS[glyph]}"/>` }}
    />
  );
}
