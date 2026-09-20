import 'reflect-metadata';
import { parseSevenDaysZones, type SevenDaysZones } from '@aurum/shared';
import { SevenDaysMapService } from './sevendays-map.service';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysController } from './sevendays.controller';
import { PERMISSION_KEY, SERVER_SCOPE_PARAM } from '../../rbac/rbac.decorators';

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
      blockSpawn: 0,
      despawn: 0,
      enter: '',
      exit: '',
      bonus: 'none',
      commandsEnabled: false,
      commandCooldown: 30,
      enterCommands: [],
      exitCommands: [],
    },
  ],
});

describe('7DTD zones', () => {
  it('retains independent settings and rejects unknown or malformed rules', () => {
    const data = fixture();
    expect(parseSevenDaysZones(data)).toEqual(data);
    for (const patch of [
      { x1: 100 },
      { noPvp: 'true' },
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
