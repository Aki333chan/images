export const SEVENDAYS_ZONE_TYPES = [
  'safe',
  'information',
  'sanctuary',
  'bonus',
  'custom',
  'restricted',
  'portal',
  'prison',
  'event',
] as const;
export interface SevenDaysZoneMovement {
  mode: 'none' | 'restricted' | 'portal' | 'prison' | 'event';
  priority: number;
  minLevel: number;
  maxLevel: number;
  players: string[];
  x: number;
  y: number;
  z: number;
  cooldown: number;
  message: string;
  sentences: { player: string; until: number }[];
  dismount: boolean;
  kickOnFailure: boolean;
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
  sentences: [],
  dismount: false,
  kickOnFailure: false,
});
export const SEVENDAYS_ZONE_BONUSES = ['regeneration', 'stamina', 'speed'] as const;
export const SEVENDAYS_ZONE_BONUS_MAX = { regeneration: 10, stamina: 30, speed: 100 } as const;
export type SevenDaysZoneBonuses = Record<(typeof SEVENDAYS_ZONE_BONUSES)[number], number>;
export interface SevenDaysZoneSchedule {
  enabled: boolean;
  start: number;
  end: number;
  offsetMinutes: number;
  /** Bits 0..6 = Monday..Sunday. Overnight intervals belong to their start day. */
  days: number;
  fromMinute: number;
  toMinute: number;
}
export const defaultZoneSchedule = (): SevenDaysZoneSchedule => ({
  enabled: false,
  start: 0,
  end: 0,
  offsetMinutes: 0,
  days: 127,
  fromMinute: 0,
  toMinute: 0,
});
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
  noCreatureBlockDamage: boolean;
  noExplosionBlockDamage: boolean;
  traderProtection: boolean;
  /** Bits: 1 zombie, 2 peaceful animal, 4 hostile animal. */
  blockSpawn: number;
  despawn: number;
  enter: string;
  exit: string;
  bonuses: SevenDaysZoneBonuses;
  commandsEnabled: boolean;
  commandCooldown: number;
  enterCommands: string[];
  exitCommands: string[];
  movement: SevenDaysZoneMovement;
  schedule: SevenDaysZoneSchedule;
}
export interface SevenDaysZones {
  revision: number;
  worldId: string;
  zones: SevenDaysZone[];
  /** Response-only startup snapshot diagnostics, never trusted as configuration. */
  protection?: { pending: boolean; applied: number; error: string };
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
  zone.movement.sentences.length > 0 ||
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
  const hasProtection = !!value && typeof value === 'object' && Object.hasOwn(value, 'protection');
  const root = object(value, [
    'revision',
    'worldId',
    'zones',
    ...(hasProtection ? ['protection'] : []),
  ]);
  let protection: SevenDaysZones['protection'];
  if (hasProtection) {
    const p = object(root.protection, ['pending', 'applied', 'error']);
    if (typeof p.pending !== 'boolean' || !number(p.applied, 0, 100, true) || !text(p.error, 80))
      return invalid();
    protection = { pending: p.pending, applied: p.applied, error: p.error };
  }
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
      'noCreatureBlockDamage',
      'noExplosionBlockDamage',
      'traderProtection',
      'blockSpawn',
      'despawn',
      'enter',
      'exit',
      'bonuses',
      'commandsEnabled',
      'commandCooldown',
      'enterCommands',
      'exitCommands',
      'movement',
      'schedule',
    ]);
    const bonuses = object(z.bonuses, [...SEVENDAYS_ZONE_BONUSES]);
    if (
      SEVENDAYS_ZONE_BONUSES.some((key) => !number(bonuses[key], 0, SEVENDAYS_ZONE_BONUS_MAX[key]))
    )
      return invalid();
    const schedule = object(z.schedule, [
      'enabled',
      'start',
      'end',
      'offsetMinutes',
      'days',
      'fromMinute',
      'toMinute',
    ]);
    if (
      typeof schedule.enabled !== 'boolean' ||
      !number(schedule.start, 0, 253402300799, true) ||
      !number(schedule.end, 0, 253402300799, true) ||
      (schedule.start !== 0 && schedule.end !== 0 && schedule.start >= schedule.end) ||
      !number(schedule.offsetMinutes, -720, 840, true) ||
      schedule.offsetMinutes % 15 !== 0 ||
      !number(schedule.days, 0, 127, true) ||
      !number(schedule.fromMinute, 0, 1439, true) ||
      !number(schedule.toMinute, 0, 1439, true)
    )
      return invalid();
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
      'sentences',
      'dismount',
      'kickOnFailure',
    ]);
    if (
      !['none', 'restricted', 'portal', 'prison', 'event'].includes(movement.mode as string) ||
      typeof movement.dismount !== 'boolean' ||
      typeof movement.kickOnFailure !== 'boolean' ||
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
    if (!Array.isArray(movement.sentences) || movement.sentences.length > 64) return invalid();
    if (movement.mode === 'prison' && schedule.enabled) throw new Error('zones_prison_schedule');
    const sentenced = new Set<string>();
    const sentences = movement.sentences
      .map((value) => {
        const s = object(value, ['player', 'until']);
        if (
          !text(s.player, 121) ||
          !/^[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}$/.test(s.player) ||
          sentenced.has(s.player) ||
          !number(s.until, 0, 253402300799, true)
        )
          return invalid();
        sentenced.add(s.player);
        return { player: s.player, until: s.until };
      })
      .sort((a, b) => (a.player < b.player ? -1 : a.player > b.player ? 1 : 0));
    if (
      !text(z.id, 48) ||
      !/^[a-z0-9][a-z0-9_-]{0,47}$/.test(z.id) ||
      ids.has(z.id) ||
      !text(z.name, 80) ||
      !z.name.trim() ||
      !SEVENDAYS_ZONE_TYPES.includes(z.type as SevenDaysZone['type']) ||
      typeof z.enabled !== 'boolean' ||
      typeof z.noPvp !== 'boolean' ||
      typeof z.noDamage !== 'boolean' ||
      typeof z.noCreatureBlockDamage !== 'boolean' ||
      typeof z.traderProtection !== 'boolean' ||
      typeof z.noExplosionBlockDamage !== 'boolean' ||
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
    if (z.traderProtection) {
      if (schedule.enabled) throw new Error('zones_protect_schedule');
      if (
        ![z.x1, z.z1, z.x2, z.z2].every(Number.isInteger) ||
        (z.x2 as number) - (z.x1 as number) > 32760 ||
        (z.z2 as number) - (z.z1 as number) > 32760
      )
        throw new Error('zones_protect_bounds');
    }
    ids.add(z.id);
    return {
      ...z,
      movement: { ...movement, players: [...movement.players].sort(), sentences },
    } as unknown as SevenDaysZone;
  });
  const prisoners = zones
    .filter((z) => z.enabled && z.movement.mode === 'prison')
    .flatMap((z) => z.movement.sentences.map((s) => s.player));
  if (new Set(prisoners).size !== prisoners.length) return invalid();
  const contains = (z: SevenDaysZone, x: number, y: number) =>
    x >= z.x1 && x <= z.x2 && y >= z.z1 && y <= z.z2;
  for (const zone of zones) {
    const m = zone.movement;
    const containment = m.mode === 'prison' || m.mode === 'event';
    if (zone.enabled && containment && !contains(zone, m.x + 0.5, m.z + 0.5))
      throw new Error('zones_destination_conflict');
    if (
      zone.enabled &&
      m.mode !== 'none' &&
      zones.some(
        (other) =>
          other.enabled &&
          !(containment && other.id === zone.id) &&
          other.movement.mode !== 'none' &&
          m.x + 0.5 >= other.x1 &&
          m.x + 0.5 <= other.x2 &&
          m.z + 0.5 >= other.z1 &&
          m.z + 0.5 <= other.z2,
      )
    )
      throw new Error('zones_destination_conflict');
  }
  return {
    revision: root.revision,
    worldId: root.worldId,
    zones,
    ...(protection ? { protection } : {}),
  };
}
