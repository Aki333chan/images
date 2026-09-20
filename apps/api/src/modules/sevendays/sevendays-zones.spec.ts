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
    await service.zones('s', fixture());
    expect(remote.zoneRequest).toHaveBeenCalledWith('s', fixture());
    remote.zoneRequest.mockRejectedValue(new Error('lost reply'));
    await expect(service.zones('s', fixture())).rejects.toThrow('lost reply');
    expect(remote.zoneRequest).toHaveBeenCalledTimes(2);
  });
  it('separates map read and server configuration authority', () => {
    expect(Reflect.getMetadata(PERMISSION_KEY, SevenDaysController.prototype.zones)).toEqual([
      'sevendays.map.view',
    ]);
    expect(Reflect.getMetadata(PERMISSION_KEY, SevenDaysController.prototype.saveZones)).toEqual([
      'sevendays.configure',
    ]);
    expect(Reflect.getMetadata(SERVER_SCOPE_PARAM, SevenDaysController.prototype.saveZones)).toBe(
      'serverId',
    );
  });
});
