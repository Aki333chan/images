import type { MinecraftInventoryItemDto, MinecraftInventoryResponse } from '@aurum/shared';

/** Подсказка при наведении: имя, количество, зачарования и описание. */
function describeItem(item: MinecraftInventoryItemDto): string {
  const lines = [`${item.displayName ?? item.id} ×${item.count}`];
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
}: {
  items: MinecraftInventoryResponse['items'];
  size: number;
  cols: number;
  /** Номер слота первой ячейки: основной инвентарь начинается с 9, а не с 0. */
  slotOffset?: number;
}) {
  const bySlot = new Map((items ?? []).map((i) => [i.slot, i]));
  return (
    <div className="grid gap-1" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {Array.from({ length: size }, (_, cell) => {
        const slot = cell + slotOffset;
        const item = bySlot.get(slot);
        const enchantCount = item ? Object.keys(item.enchantments ?? {}).length : 0;
        return (
          <div
            key={slot}
            title={item ? describeItem(item) : `Слот ${slot}`}
            className={`relative flex aspect-square items-center justify-center rounded border text-[10px] ${
              item
                ? enchantCount > 0
                  ? 'border-fuchsia-400/50 bg-fuchsia-500/10'
                  : 'border-primary/40 bg-primary/10'
                : 'border-border bg-black/30'
            }`}
          >
            {item && (
              <>
                <span className="truncate px-1 text-center leading-tight">
                  {(item.displayName ?? item.id).replace(/^minecraft:/, '')}
                </span>
                {item.count > 1 && (
                  <span className="absolute bottom-0 right-1 font-bold">{item.count}</span>
                )}
                {/* Зачарованные предметы помечаем — как блеск в самой игре. */}
                {enchantCount > 0 && (
                  <span className="absolute left-0.5 top-0.5 text-fuchsia-300">✦</span>
                )}
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}
