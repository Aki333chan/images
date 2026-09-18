import 'reflect-metadata';
import { parseMapSnapshot, SevenDaysMapService } from './sevendays-map.service';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysController } from './sevendays.controller';

const info = { available: true, blockSize: 128, maxZoom: 4 };
const markers = {
  ready: true,
  players: [{ id: 'p', name: 'Player', x: -12, z: 23 }],
  claims: [{ ownerId: 'p', owner: 'Player', x: -15, z: 30, size: 41 }],
};
function png(size = 128) {
  const bytes = Buffer.alloc(33);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(bytes);
  bytes.write('IHDR', 12);
  bytes.writeUInt32BE(size, 16);
  bytes.writeUInt32BE(size, 20);
  return bytes.toString('base64');
}
function setup() {
  const companion = {
    ping: jest.fn().mockResolvedValue({ compatible: true, capabilities: ['map-read'] }),
    mapRequest: jest.fn(async (_id: string, path: string): Promise<unknown> =>
      path === '/map/info' ? info : path === '/map/markers' ? markers : { png: png() },
    ),
  };
  return {
    companion,
    service: new SevenDaysMapService(companion as unknown as SevenDaysCompanionService),
  };
}
describe('7DTD read-only map', () => {
  it('keeps markers when native terrain has not been rendered', () => {
    expect(parseMapSnapshot({}, markers)).toMatchObject({
      available: true,
      reason: 'native_map_missing',
      info: null,
      players: markers.players,
      claims: markers.claims,
    });
    expect(parseMapSnapshot(info, { ready: false })).toMatchObject({
      available: false,
      players: [],
      claims: [],
    });
  });
  it('rejects malformed coordinates and bounds lists', () => {
    expect(
      parseMapSnapshot(info, {
        ...markers,
        players: [{ id: 'x', name: 'x', x: NaN, z: 0 }],
        claims: [{ ...markers.claims[0], size: -1 }],
      }),
    ).toMatchObject({ players: [], claims: [] });
    const result = parseMapSnapshot(info, {
      ...markers,
      players: Array(300).fill(markers.players[0]),
      claims: Array(2100).fill(markers.claims[0]),
    });
    expect(result.players).toHaveLength(256);
    expect(result.claims).toHaveLength(2048);
    expect(result.truncated).toBe(true);
    expect(parseMapSnapshot({ ...info, blockSize: 129 }, markers).info).toBeNull();
  });
  it('requires a map-capable compatible companion', async () => {
    const { companion, service } = setup();
    companion.ping
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce({ compatible: true, capabilities: [] })
      .mockResolvedValueOnce({ compatible: false, capabilities: ['map-read'] });
    expect((await service.snapshot('a')).reason).toBe('mod_unavailable');
    expect((await service.snapshot('b')).reason).toBe('mod_update');
    expect((await service.snapshot('c')).reason).toBe('mod_update');
    expect(companion.mapRequest).not.toHaveBeenCalled();
  });
  it('coalesces snapshots, caches tiles, and isolates servers', async () => {
    const { companion, service } = setup();
    await Promise.all([service.snapshot('a'), service.snapshot('a')]);
    expect(companion.ping).toHaveBeenCalledTimes(1);
    expect(await service.tile('a', 4, -1, -2)).toEqual({ png: png() });
    await service.tile('a', 4, -1, -2);
    expect(companion.mapRequest.mock.calls.filter((c) => c[1].includes('/tile/'))).toHaveLength(1);
    await service.tile('b', 4, -1, -2);
    expect(companion.ping).toHaveBeenCalledTimes(2);
  });
  it.each([
    [9, 0, 0],
    [4, 65537, 0],
    [4, 0, -65537],
    [1.1, 0, 0],
  ])('rejects invalid tile coordinates %s/%s/%s', async (z, x, y) => {
    const { service, companion } = setup();
    await expect(service.tile('a', z, x, y)).rejects.toThrow('invalid_tile');
    expect(companion.ping).not.toHaveBeenCalled();
  });
  it.each(['not-an-image', png(512), 'A'.repeat(350000)])(
    'rejects malformed or oversized PNG payloads',
    async (value) => {
      const { service, companion } = setup();
      await service.snapshot('a');
      companion.mapRequest.mockResolvedValue({ png: value });
      expect(await service.tile('a', 4, 0, 0)).toEqual({ png: null });
    },
  );
  it('bounds concurrent tile misses to two and releases slots on failure', async () => {
    const { service, companion } = setup();
    await service.snapshot('a');
    let release!: () => void;
    const wait = new Promise<void>((r) => {
      release = r;
    });
    companion.mapRequest.mockImplementation(async () => {
      await wait;
      throw new Error('offline');
    });
    const first = service.tile('a', 4, 1, 1),
      second = service.tile('a', 4, 2, 2);
    const outcomes = Promise.allSettled([first, second]);
    await expect(service.tile('a', 4, 3, 3)).rejects.toThrow('map_busy');
    release();
    await outcomes;
    companion.mapRequest.mockResolvedValue({ png: null });
    await expect(service.tile('a', 4, 3, 3)).resolves.toEqual({ png: null });
  });
  it.each(['mapSnapshot', 'mapTile'] as const)(
    'protects %s with map permission and server scope',
    (name) => {
      const handler = SevenDaysController.prototype[name];
      expect(Reflect.getMetadata('requiredPermission', handler)).toEqual(['sevendays.map.view']);
      expect(Reflect.getMetadata('serverScopeParam', handler)).toBe('serverId');
    },
  );
});
