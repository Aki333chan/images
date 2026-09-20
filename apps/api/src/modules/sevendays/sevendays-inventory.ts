import { BadRequestException } from '@nestjs/common';
import type {
  SevenDaysInventory,
  SevenDaysInventoryItem,
  SevenDaysSavedPlayers,
} from '@aurum/shared';

export function validateInventoryPlayerId(id: string) {
  if (id.length > 160 || !/^[A-Za-z0-9]+_[A-Za-z0-9_-]+$/.test(id))
    throw new BadRequestException('invalid_player_id');
}
export function unavailableInventory(
  reason: SevenDaysInventory['reason'],
  source: SevenDaysInventory['source'] = 'client_snapshot',
): SevenDaysInventory {
  return {
    available: false,
    reason,
    source,
    fetchedAt: null,
    items: [],
    truncated: false,
  };
}
const limits = { belt: 32, bag: 256, equipment: 32, cursor: 1 } as const;
export function parseInventory(
  value: unknown,
  source: SevenDaysInventory['source'] = 'client_snapshot',
): SevenDaysInventory {
  const invalid = () => new BadRequestException('invalid_inventory_response');
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const body = value as Record<string, unknown>;
  const reasons =
    source === 'saved_file'
      ? ['save_missing', 'save_invalid', 'save_busy', 'save_unavailable']
      : ['offline', 'snapshot_pending'];
  if (body.available === false && reasons.includes(body.reason as string))
    return unavailableInventory(body.reason as SevenDaysInventory['reason'], source);
  if (
    source === 'saved_file' &&
    (typeof body.savedAt !== 'string' ||
      body.savedAt.length > 40 ||
      !Number.isFinite(Date.parse(body.savedAt)))
  )
    throw invalid();
  if (
    body.available !== true ||
    body.source !== source ||
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
    source,
    ...(source === 'saved_file' ? { savedAt: body.savedAt as string } : {}),
    fetchedAt: new Date().toISOString(),
    items,
    slotCounts,
    truncated: body.truncated,
  };
}

export function parseSavedPlayers(value: unknown): SevenDaysSavedPlayers {
  const invalid = () => new BadRequestException('invalid_saved_players_response');
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const p = value as SevenDaysSavedPlayers;
  if (
    typeof p.ready !== 'boolean' ||
    typeof p.hasMore !== 'boolean' ||
    typeof p.truncated !== 'boolean' ||
    !Array.isArray(p.players) ||
    p.players.length > 50
  )
    throw invalid();
  const ids = new Set<string>();
  const players = p.players.map((row) => {
    if (
      !row ||
      typeof row.id !== 'string' ||
      typeof row.name !== 'string' ||
      row.name.length > 80 ||
      ids.has(row.id)
    )
      throw invalid();
    validateInventoryPlayerId(row.id);
    ids.add(row.id);
    return { id: row.id, name: row.name };
  });
  if (!p.ready && (players.length || p.hasMore)) throw invalid();
  return { ready: p.ready, players, hasMore: p.hasMore, truncated: p.truncated };
}
