import 'reflect-metadata';
import {
  parseSevenDaysZones,
  defaultZoneMovement,
  defaultZoneSchedule,
  type SevenDaysZones,
} from '@aurum/shared';
import { SevenDaysMapService } from './sevendays-map.service';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysController } from './sevendays.controller';
import { PERMISSION_KEY, SERVER_SCOPE_PARAM } from '../../rbac/rbac.decorators';

describe('zone bonus strengths', () => {
  it.each(['zones-v6', 'zones-v7'])(
    'gates both reads and writes on the new schema: %s',
    async (capability) => {
      const call = jest.fn().mockResolvedValue(fixture());
      const service = Object.assign(Object.create(SevenDaysCompanionService.prototype), {
        ping: jest.fn().mockResolvedValue({ compatible: true, capabilities: [capability] }),
        call,
      }) as SevenDaysCompanionService;
      for (const payload of [undefined, fixture()]) {
        if (capability === 'zones-v7')
          await expect(service.zoneRequest('s', payload)).resolves.toEqual(fixture());
        else
          await expect(service.zoneRequest('s', payload)).rejects.toThrow(
            'zones_mod_update_required',
          );
      }
      expect(call).toHaveBeenCalledTimes(capability === 'zones-v7' ? 2 : 0);
    },
  );
  it('roundtrips simultaneous effects and fractional strength', () => {
    const value = fixture();
    value.zones[0]!.bonuses = { regeneration: 1.5, stamina: 6, speed: 15 };
    expect(parseSevenDaysZones(value)).toEqual(value);
  });
  it.each([
    {},
    { regeneration: -1, stamina: 0, speed: 0 },
    { regeneration: 11, stamina: 0, speed: 0 },
    { regeneration: 0, stamina: 31, speed: 0 },
    { regeneration: 0, stamina: 0, speed: 101 },
    { regeneration: 0, stamina: 0, speed: NaN },
    { regeneration: 0, stamina: 0, speed: Infinity },
    { regeneration: 0, stamina: 0, speed: '15' },
  ])('rejects malformed strengths %p', (bonuses) => {
    const value = fixture();
    Object.assign(value.zones[0]!, { bonuses });
    expect(() => parseSevenDaysZones(value)).toThrow('invalid_zones');
  });
  it('rejects the old key rather than silently ignoring it', () => {
    const value = fixture();
    Object.assign(value.zones[0]!, { bonus: 'speed' });
    expect(() => parseSevenDaysZones(value)).toThrow('invalid_zones');
  });
});

const fixture = (): SevenDaysZones => ({
  revision: 0,
  worldId: 'a'.repeat(32),
  zones: [
    {
      id: 'spawn',
      name: 'Spawn',
      type: 'safe',
      enabled: true,
      x1: -10,
      z1: -20,
      x2: 10,
      z2: 20,
      noPvp: true,
      noDamage: false,
      noCreatureBlockDamage: false,
      noExplosionBlockDamage: false,
      blockSpawn: 0,
      despawn: 0,
      enter: '',
      exit: '',
      bonuses: { regeneration: 0, stamina: 0, speed: 0 },
      commandsEnabled: false,
      commandCooldown: 30,
      enterCommands: [],
      exitCommands: [],
      movement: defaultZoneMovement(),
      schedule: defaultZoneSchedule(),
    },
  ],
});

describe('7DTD zones', () => {
  it('roundtrips independent block protection flags and rejects old wire format', () => {
    const data = fixture();
    data.zones[0]!.noCreatureBlockDamage = true;
    data.zones[0]!.noExplosionBlockDamage = true;
    expect(parseSevenDaysZones(data)).toEqual(data);
    const old = JSON.parse(JSON.stringify(data));
    delete old.zones[0].noExplosionBlockDamage;
    expect(() => parseSevenDaysZones(old)).toThrow('invalid_zones');
  });
  it('validates schedules and protects timed script activation with command authority', async () => {
    const data = fixture();
    data.zones[0]!.schedule = {
      ...defaultZoneSchedule(),
      enabled: true,
      days: 1,
      fromMinute: 1320,
      toMinute: 120,
      offsetMinutes: 120,
    };
    expect(parseSevenDaysZones(data)).toEqual(data);
    for (const patch of [
      { days: 128 },
      { days: -1 },
      { offsetMinutes: 13 },
      { offsetMinutes: 900 },
      { start: 100, end: 99 },
      { start: -1 },
      { toMinute: 1440 },
      { enabled: 'true' },
      { unknown: 1 },
    ]) {
      const next = structuredClone(data);
      Object.assign(next.zones[0]!.schedule, patch);
      expect(() => parseSevenDaysZones(next)).toThrow();
    }
    const prison = structuredClone(data);
    prison.zones[0]!.movement.mode = 'prison';
    expect(() => parseSevenDaysZones(prison)).toThrow('zones_prison_schedule');
    const remote = { zoneRequest: jest.fn().mockResolvedValue(data) };
    const service = new SevenDaysMapService(remote as unknown as SevenDaysCompanionService);
    await service.zones('s', data); // Normal zone schedule needs manage, not command authority.
    data.zones[0]!.enterCommands = ['buffplayer {player} buffExample'];
    const next = structuredClone(data);
    next.zones[0]!.schedule.enabled = false;
    await expect(service.zones('s', next)).rejects.toThrow('zones_commands_permission_required');
  });
  it('validates prison assignments, internal destinations and protects releases', async () => {
    const current = fixture();
    current.zones[0]!.type = 'prison';
    current.zones[0]!.movement.mode = 'prison';
    current.zones[0]!.movement.sentences = [{ player: 'Steam_1', until: 2000000000 }];
    expect(parseSevenDaysZones(current)).toEqual(current);
    for (const patch of [
      { x: 100 },
      { sentences: [{ player: 'Alice', until: 0 }] },
      { sentences: [{ player: 'Steam_1', until: -1 }] },
      { kickOnFailure: 'true' },
      {
        sentences: [
          { player: 'Steam_1', until: 0 },
          { player: 'Steam_1', until: 1 },
        ],
      },
    ]) {
      const next = structuredClone(current);
      Object.assign(next.zones[0]!.movement, patch);
      expect(() => parseSevenDaysZones(next)).toThrow();
    }
    const remote = { zoneRequest: jest.fn().mockResolvedValue(current) };
    const service = new SevenDaysMapService(remote as unknown as SevenDaysCompanionService);
    const released = structuredClone(current);
    released.zones[0]!.movement.sentences = [];
    await expect(service.zones('s', released)).rejects.toThrow(
      'zones_commands_permission_required',
    );
    await service.zones('s', released, true);
    expect(remote.zoneRequest).toHaveBeenLastCalledWith('s', released);
    const duplicate = structuredClone(current.zones[0]!);
    duplicate.id = 'another';
    expect(() =>
      parseSevenDaysZones({ ...current, zones: [...current.zones, duplicate] }),
    ).toThrow();
    const ev = fixture();
    ev.zones[0]!.type = 'event';
    ev.zones[0]!.movement.mode = 'event';
    expect(parseSevenDaysZones(ev)).toEqual(ev);
  });
  it('validates movement rules, identities, destination conflicts and protects automatic teleports', async () => {
    const current = fixture();
    current.zones[0]!.movement = {
      ...defaultZoneMovement(),
      mode: 'restricted',
      minLevel: 10,
      x: 100,
      players: ['Steam_2', 'Steam_1'],
    };
    const normalized = parseSevenDaysZones(current);
    expect(normalized.zones[0]!.movement.players).toEqual(['Steam_1', 'Steam_2']);
    for (const patch of [
      { y: -1 },
      { y: 252 },
      { players: ['Alice'] },
      { players: ['Steam_1', 'Steam_1'] },
      { maxLevel: 5 },
      { x: 0 },
      { cooldown: 0 },
      { mode: 'jail' },
    ]) {
      const next = structuredClone(current);
      Object.assign(next.zones[0]!.movement, patch);
      expect(() => parseSevenDaysZones(next)).toThrow();
    }
    const old = structuredClone(current) as unknown as { zones: Record<string, unknown>[] };
    delete old.zones[0]!.movement;
    expect(() => parseSevenDaysZones(old)).toThrow();
    const remote = { zoneRequest: jest.fn().mockResolvedValue(normalized) };
    const service = new SevenDaysMapService(remote as unknown as SevenDaysCompanionService);
    await expect(service.zones('s', fixture())).rejects.toThrow(
      'zones_commands_permission_required',
    );
    await expect(service.zones('s', { ...normalized, zones: [] })).rejects.toThrow(
      'zones_commands_permission_required',
    );
    await service.zones('s', current);
    expect(remote.zoneRequest).toHaveBeenLastCalledWith('s', normalized);
  });
  it('retains independent settings and rejects unknown or malformed rules', () => {
    const data = fixture();
    expect(parseSevenDaysZones(data)).toEqual(data);
    for (const patch of [
      { x1: 100 },
      { noPvp: 'true' },
      { noCreatureBlockDamage: 'true' },
      { noExplosionBlockDamage: 1 },
      { type: 'unknown' },
      { blockSpawn: 8 },
      { despawn: 0.5 },
      { name: '\n' },
      { x2: NaN },
      { undeclared: true },
    ])
      expect(() =>
        parseSevenDaysZones({ ...data, zones: [{ ...data.zones[0], ...patch }] }),
      ).toThrow();
    expect(() => parseSevenDaysZones({ ...data, zones: [data.zones[0], data.zones[0]] })).toThrow();
    expect(() => parseSevenDaysZones({ ...data, worldId: '' })).toThrow();
    expect(() => parseSevenDaysZones({ ...data, zones: JSON.stringify(data.zones) })).toThrow();
  });
  it('validates before a write, forwards revision/world binding, and does not retry unknown outcomes', async () => {
    const remote = { zoneRequest: jest.fn().mockResolvedValue(fixture()) };
    const service = new SevenDaysMapService(remote as unknown as SevenDaysCompanionService);
    await expect(service.zones('s', { revision: 0 })).rejects.toThrow('invalid_zones');
    expect(remote.zoneRequest).not.toHaveBeenCalled();
    await service.zones('s', fixture(), true);
    expect(remote.zoneRequest).toHaveBeenCalledWith('s', fixture());
    remote.zoneRequest.mockRejectedValue(new Error('lost reply'));
    await expect(service.zones('s', fixture(), true)).rejects.toThrow('lost reply');
    expect(remote.zoneRequest).toHaveBeenCalledTimes(2);
  });
  it('separates map read and server configuration authority', () => {
    expect(Reflect.getMetadata(PERMISSION_KEY, SevenDaysController.prototype.zones)).toEqual([
      'sevendays.map.view',
    ]);
    expect(Reflect.getMetadata(PERMISSION_KEY, SevenDaysController.prototype.saveZones)).toEqual([
      'sevendays.zones.manage',
    ]);
    expect(Reflect.getMetadata(SERVER_SCOPE_PARAM, SevenDaysController.prototype.saveZones)).toBe(
      'serverId',
    );
  });
  it('protects scripted zone bounds, disabling and deletion without command authority', async () => {
    const current = fixture();
    current.zones[0]!.enterCommands = ['buffplayer {player} buffExample'];
    const remote = { zoneRequest: jest.fn().mockResolvedValue(current) };
    const service = new SevenDaysMapService(remote as unknown as SevenDaysCompanionService);
    for (const next of [
      { ...current, zones: [] },
      { ...current, zones: [{ ...current.zones[0], x1: -11 }] },
      { ...current, zones: [{ ...current.zones[0], enabled: false }] },
      fixture(),
    ])
      await expect(service.zones('s', next)).rejects.toThrow('zones_commands_permission_required');
    expect(remote.zoneRequest.mock.calls.every((c) => c.length === 1)).toBe(true);
    await service.zones('s', current);
    expect(remote.zoneRequest).toHaveBeenLastCalledWith('s', current);
  });
  it('rejects console injection and malformed command targets', () => {
    for (const command of [
      'shutdown',
      'buffplayer Alice buffExample',
      'buffplayer {player} buffExample;shutdown',
      'buffplayer {player} aurumZoneProtection',
      'teleportplayer {player} 0 -2 0',
    ]) {
      const next = fixture();
      next.zones[0]!.enterCommands = [command];
      expect(() => parseSevenDaysZones(next)).toThrow('invalid_zones');
    }
  });
});
