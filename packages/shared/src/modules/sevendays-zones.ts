export const SEVENDAYS_ZONE_TYPES = [
  'safe',
  'information',
  'sanctuary',
  'bonus',
  'custom',
] as const;
export const SEVENDAYS_ZONE_BONUSES = ['none', 'regeneration', 'stamina', 'speed'] as const;
export interface SevenDaysZone {
  id: string;
  name: string;
  type: (typeof SEVENDAYS_ZONE_TYPES)[number];
  enabled: boolean;
  x1: number;
  z1: number;
  x2: number;
  z2: number;
  noPvp: boolean;
  noDamage: boolean;
  /** Bits: 1 zombie, 2 peaceful animal, 4 hostile animal. */
  blockSpawn: number;
  despawn: number;
  enter: string;
  exit: string;
  bonus: (typeof SEVENDAYS_ZONE_BONUSES)[number];
}
export interface SevenDaysZones {
  revision: number;
  worldId: string;
  zones: SevenDaysZone[];
}

/** Same strict boundary in API, browser and companion. No silent unknown rule fallback. */
export function parseSevenDaysZones(value: unknown): SevenDaysZones {
  const invalid = (): never => {
    throw new Error('invalid_zones');
  };
  const object = (v: unknown, keys: string[]): Record<string, unknown> => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return invalid();
    const row = v as Record<string, unknown>;
    if (Object.keys(row).length !== keys.length || keys.some((k) => !Object.hasOwn(row, k)))
      return invalid();
    return row;
  };
  const text = (v: unknown, max: number): v is string =>
    // eslint-disable-next-line no-control-regex -- Reject control characters in names and messages.
    typeof v === 'string' && v.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(v);
  const number = (v: unknown, min: number, max: number, integer = false): v is number =>
    typeof v === 'number' &&
    Number.isFinite(v) &&
    v >= min &&
    v <= max &&
    (!integer || Number.isInteger(v));
  const root = object(value, ['revision', 'worldId', 'zones']);
  if (
    !text(root.worldId, 32) ||
    !/^[a-f0-9]{32}$/.test(root.worldId) ||
    !number(root.revision, 0, 9007199254740990, true) ||
    !Array.isArray(root.zones) ||
    root.zones.length > 100
  )
    return invalid();
  const ids = new Set<string>();
  const zones = root.zones.map((value) => {
    const z = object(value, [
      'id',
      'name',
      'type',
      'enabled',
      'x1',
      'z1',
      'x2',
      'z2',
      'noPvp',
      'noDamage',
      'blockSpawn',
      'despawn',
      'enter',
      'exit',
      'bonus',
    ]);
    if (
      !text(z.id, 48) ||
      !/^[a-z0-9][a-z0-9_-]{0,47}$/.test(z.id) ||
      ids.has(z.id) ||
      !text(z.name, 80) ||
      !z.name.trim() ||
      !SEVENDAYS_ZONE_TYPES.includes(z.type as SevenDaysZone['type']) ||
      !SEVENDAYS_ZONE_BONUSES.includes(z.bonus as SevenDaysZone['bonus']) ||
      typeof z.enabled !== 'boolean' ||
      typeof z.noPvp !== 'boolean' ||
      typeof z.noDamage !== 'boolean' ||
      !number(z.x1, -500000, 500000) ||
      !number(z.z1, -500000, 500000) ||
      !number(z.x2, -500000, 500000) ||
      !number(z.z2, -500000, 500000) ||
      z.x1 >= z.x2 ||
      z.z1 >= z.z2 ||
      !number(z.blockSpawn, 0, 7, true) ||
      !number(z.despawn, 0, 7, true) ||
      !text(z.enter, 240) ||
      !text(z.exit, 240)
    )
      return invalid();
    ids.add(z.id);
    return { ...z } as unknown as SevenDaysZone;
  });
  return { revision: root.revision, worldId: root.worldId, zones };
}
