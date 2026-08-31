import type { MinecraftInventoryItemDto, MinecraftInventoryResponse } from '@aurum/shared';
import { ItemGlyph } from './ItemGlyph';
import { itemIcon, itemShortLabel } from './item-icon';

/**
 * Где лежит слот. Основной инвентарь, броня и вторая рука приходят разными
 * массивами и нумеруются каждый со своего нуля, поэтому один номер слота их
 * не различает — а удалять выбранное надо адресно.
 */
export type InventoryArea = 'main' | 'armor' | 'offhand';

/** Ключ выбранной ячейки. Он же разбирается обратно при отправке запроса. */
export function slotKey(area: InventoryArea, slot: number): string {
  return `${area}:${slot}`;
}

/** Подсказка при наведении: имя, количество, зачарования и описание. */
function describeItem(item: MinecraftInventoryItemDto): string {
  const lines = [`${item.displayName ?? item.id} ×${item.count}`];
  if (item.displayName) lines.push(item.id);
  const enchantments = Object.entries(item.enchantments ?? {});
  for (const [key, level] of enchantments) {
    lines.push(`${key.replace(/^minecraft:/, '')} ${level}`);
  }
  for (const line of item.lore ?? []) lines.push(line);
  return lines.join('\n');
}

export function InventoryGrid({
  items,
  size,
  cols,
  slotOffset = 0,
  area,
  selected,
  onToggle,
}: {
  items: MinecraftInventoryResponse['items'];
  size: number;
  cols: number;
  /** Номер слота первой ячейки: основной инвентарь начинается с 9, а не с 0. */
  slotOffset?: number;
  area: InventoryArea;
  /** Ключи выбранных ячеек. undefined — режим просмотра, выбирать нельзя. */
  selected?: Set<string>;
  onToggle?: (key: string) => void;
}) {
  const bySlot = new Map((items ?? []).map((i) => [i.slot, i]));
  const selectable = !!onToggle;

  return (
    <div className="grid gap-1" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {Array.from({ length: size }, (_, cell) => {
        const slot = cell + slotOffset;
        const item = bySlot.get(slot);
        const key = slotKey(area, slot);
        const picked = selected?.has(key) ?? false;
        const enchantCount = item ? Object.keys(item.enchantments ?? {}).length : 0;
        const icon = item ? itemIcon(item.id) : null;

        // Пустую ячейку выбирать не даём: стирать в ней нечего, а щелчок по
        // ней выглядел бы как выбор — и в счётчике «выбрано» появлялись бы
        // слоты, которых человек не видит.
        const clickable = selectable && !!item;

        return (
          <div
            key={key}
            role={clickable ? 'checkbox' : undefined}
            aria-checked={clickable ? picked : undefined}
            aria-label={clickable ? `Слот ${slot}: ${item?.displayName ?? item?.id}` : undefined}
            tabIndex={clickable ? 0 : undefined}
            onClick={clickable ? () => onToggle?.(key) : undefined}
            onKeyDown={
              clickable
                ? (e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onToggle?.(key);
                    }
                  }
                : undefined
            }
            title={item ? describeItem(item) : `Слот ${slot}`}
            style={
              item && icon
                ? { color: icon.color, backgroundColor: icon.background, borderColor: `${icon.color}66` }
                : undefined
            }
            className={`relative flex aspect-square flex-col items-center justify-center gap-0.5 overflow-hidden rounded border text-[9px] transition-shadow ${
              item ? '' : 'border-border bg-black/30'
            } ${clickable ? 'cursor-pointer' : ''} ${
              picked ? 'ring-2 ring-red-400 ring-offset-1 ring-offset-black/40' : ''
            }`}
          >
            {item && icon && (
              <>
                <ItemGlyph glyph={icon.glyph} />
                {/* Подпись под значком: значок отвечает «это меч», подпись —
                    «какой именно». Материал в ней не повторяется, он уже
                    передан цветом. */}
                <span
                  // Место под число справа освобождается только когда оно
                  // там есть: иначе подпись зря теряет треть ширины на
                  // одиночных предметах, которых в инвентаре большинство.
                  className={`w-full truncate text-center leading-none text-neutral-300 ${
                    item.count > 1 ? 'pl-0.5 pr-2.5' : 'px-0.5'
                  }`}
                >
                  {item.displayName ?? itemShortLabel(item.id)}
                </span>
                {item.count > 1 && (
                  <span className="absolute bottom-0 right-0 rounded-tl bg-black/55 px-0.5 text-[10px] font-bold leading-tight text-neutral-100">
                    {item.count}
                  </span>
                )}
                {/* Зачарованные предметы помечаем — как блеск в самой игре. */}
                {enchantCount > 0 && (
                  <span className="absolute left-0.5 top-0.5 text-[10px] text-fuchsia-300">✦</span>
                )}
                {picked && (
                  <span className="absolute right-0.5 top-0.5 text-[10px] font-bold text-red-300">
                    ✕
                  </span>
                )}
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}
