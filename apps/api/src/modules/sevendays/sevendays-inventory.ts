import { BadRequestException } from '@nestjs/common';
import type { SevenDaysInventory, SevenDaysInventoryItem } from '@aurum/shared';

export function validateInventoryPlayerId(id: string) {
  if (id.length > 160 || !/^[A-Za-z0-9]+_[A-Za-z0-9_-]+$/.test(id))
    throw new BadRequestException('invalid_player_id');
}
export function unavailableInventory(reason: SevenDaysInventory['reason']): SevenDaysInventory {
  return {
    available: false,
    reason,
    source: 'client_snapshot',
    fetchedAt: null,
    items: [],
    truncated: false,
  };
}
const limits = { belt: 32, bag: 256, equipment: 32, cursor: 1 } as const;
export function parseInventory(value: unknown): SevenDaysInventory {
  const invalid = () => new BadRequestException('invalid_inventory_response');
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const body = value as Record<string, unknown>;
  if (body.available === false && (body.reason === 'offline' || body.reason === 'snapshot_pending'))
    return unavailableInventory(body.reason);
  if (
    body.available !== true ||
    body.source !== 'client_snapshot' ||
    !Array.isArray(body.items) ||
    body.items.length > 321 ||
    typeof body.truncated !== 'boolean'
  )
    throw invalid();
  const items: SevenDaysInventoryItem[] = [];
  let slotCounts: SevenDaysInventory['slotCounts'] = null;
  if (body.slotCounts !== undefined) {
    if (!body.slotCounts || typeof body.slotCounts !== 'object' || Array.isArray(body.slotCounts))
      throw invalid();
    const supplied = body.slotCounts as Record<string, number>;
    slotCounts = { belt: 0, bag: 0, equipment: 0, cursor: 0 };
    for (const section of Object.keys(limits) as (keyof typeof limits)[]) {
      const count = supplied[section];
      if (
        typeof count !== 'number' ||
        !Number.isInteger(count) ||
        count < 0 ||
        count > limits[section]
      )
        throw invalid();
      slotCounts[section] = count;
    }
  }
  const slots = new Set<string>();
  for (const row of body.items) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) throw invalid();
    const p = row as SevenDaysInventoryItem;
    if (
      !Object.prototype.hasOwnProperty.call(limits, p.section) ||
      !Number.isInteger(p.slot) ||
      p.slot < 0 ||
      p.slot >= limits[p.section] ||
      (slotCounts !== null && p.slot >= slotCounts[p.section]) ||
      !Number.isInteger(p.itemId) ||
      p.itemId <= 0 ||
      p.itemId > 2147483647 ||
      typeof p.name !== 'string' ||
      !p.name ||
      p.name.length > 128 ||
      !Number.isInteger(p.count) ||
      p.count < 1 ||
      p.count > 2147483647 ||
      !Number.isInteger(p.quality) ||
      p.quality < 0 ||
      p.quality > 65535
    )
      throw invalid();
    const key = `${p.section}/${p.slot}`;
    if (slots.has(key)) throw invalid();
    slots.add(key);
    items.push({
      section: p.section,
      slot: p.slot,
      itemId: p.itemId,
      name: p.name,
      count: p.count,
      quality: p.quality,
    });
  }
  return {
    available: true,
    source: 'client_snapshot',
    fetchedAt: new Date().toISOString(),
    items,
    slotCounts,
    truncated: body.truncated,
  };
}
