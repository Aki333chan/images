export const SEVENDAYS_ZONE_TYPES = [
  'safe',
  'information',
  'sanctuary',
  'bonus',
  'custom',
  'restricted',
  'portal',
] as const;
export interface SevenDaysZoneMovement {
  mode: 'none' | 'restricted' | 'portal';
  priority: number;
  minLevel: number;
  maxLevel: number;
  players: string[];
  x: number;
  y: number;
  z: number;
  cooldown: number;
  message: string;
}
export const defaultZoneMovement = (): SevenDaysZoneMovement => ({
  mode: 'none',
  priority: 0,
  minLevel: 0,
  maxLevel: 0,
  players: [],
  x: 0,
  y: 65,
  z: 0,
  cooldown: 10,
  message: '',
});
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
  commandsEnabled: boolean;
  commandCooldown: number;
  enterCommands: string[];
  exitCommands: string[];
  movement: SevenDaysZoneMovement;
}
export interface SevenDaysZones {
  revision: number;
  worldId: string;
  zones: SevenDaysZone[];
}

export function validZoneCommand(command: string): boolean {
  if (command.length > 160) return false;
  if (/^(buffplayer|debuffplayer) \{player\} [A-Za-z][A-Za-z0-9_]{0,79}$/.test(command))
    return !command.split(' ')[2]!.toLowerCase().startsWith('aurumzone');
  const m = /^teleportplayer \{player\} (-?[0-9]{1,6}) (-?[0-9]{1,6}) (-?[0-9]{1,6})$/.exec(
    command,
  );
  return (
    !!m &&
    Math.abs(Number(m[1])) <= 500000 &&
    Number(m[2]) >= -1 &&
    Number(m[2]) <= 2048 &&
    Math.abs(Number(m[3])) <= 500000
  );
}

export const hasZoneCommands = (zone: SevenDaysZone): boolean =>
  zone.commandsEnabled ||
  zone.enterCommands.length > 0 ||
  zone.exitCommands.length > 0 ||
  zone.movement.mode !== 'none';

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
      'commandsEnabled',
      'commandCooldown',
      'enterCommands',
      'exitCommands',
      'movement',
    ]);
    const movement = object(z.movement, [
      'mode',
      'priority',
      'minLevel',
      'maxLevel',
      'players',
      'x',
      'y',
      'z',
      'cooldown',
      'message',
    ]);
    if (
      !['none', 'restricted', 'portal'].includes(movement.mode as string) ||
      !number(movement.priority, -1000, 1000, true) ||
      !number(movement.minLevel, 0, 10000, true) ||
      !number(movement.maxLevel, 0, 10000, true) ||
      (movement.maxLevel !== 0 && movement.minLevel > movement.maxLevel) ||
      !number(movement.x, -500000, 500000, true) ||
      !number(movement.z, -500000, 500000, true) ||
      !number(movement.y, 2, 251, true) ||
      !number(movement.cooldown, 10, 86400, true) ||
      !text(movement.message, 240) ||
      !Array.isArray(movement.players) ||
      movement.players.length > 64 ||
      !movement.players.every(
        (id) => text(id, 121) && /^[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}$/.test(id),
      ) ||
      new Set(movement.players).size !== movement.players.length
    )
      return invalid();
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
      !text(z.exit, 240) ||
      typeof z.commandsEnabled !== 'boolean' ||
      !number(z.commandCooldown, 10, 86400, true) ||
      ![z.enterCommands, z.exitCommands].every(
        (list) =>
          Array.isArray(list) &&
          list.length <= 4 &&
          list.every((c) => text(c, 160) && validZoneCommand(c)) &&
          list.filter((c) => c.startsWith('teleportplayer ')).length <= 1,
      )
    )
      return invalid();
    ids.add(z.id);
    return {
      ...z,
      movement: { ...movement, players: [...movement.players].sort() },
    } as unknown as SevenDaysZone;
  });
  for (const zone of zones) {
    const m = zone.movement;
    if (
      zone.enabled &&
      m.mode !== 'none' &&
      zones.some(
        (other) =>
          other.enabled &&
          other.movement.mode !== 'none' &&
          m.x + 0.5 >= other.x1 &&
          m.x + 0.5 <= other.x2 &&
          m.z + 0.5 >= other.z1 &&
          m.z + 0.5 <= other.z2,
      )
    )
      throw new Error('zones_destination_conflict');
  }
  return { revision: root.revision, worldId: root.worldId, zones };
}
