/* eslint-disable @typescript-eslint/no-explicit-any -- narrow in-memory service doubles */
import 'reflect-metadata';
import { randomUUID } from 'node:crypto';
import { SevenDaysToolsService, validateTools } from './sevendays-tools.service';
import { SevenDaysController } from './sevendays.controller';
import { sevenDaysManifest } from './sevendays.def';

function fixture() {
  const point = { id: randomUUID(), name: 'Home', x: 10, y: -1, z: -20 };
  const kit = {
    id: randomUUID(),
    name: 'Starter',
    items: [
      { name: 'resourceRock', count: 5, quality: 0 },
      { name: 'gunPistol', count: 1, quality: 6 },
    ],
  };
  const data = { revision: 1, points: [point], kits: [kit] };
  const runs = new Map<string, any>();
  const db: any = {
    sevenDaysTools: {
      findUnique: jest.fn(async () => ({ revision: data.revision, data })),
      updateMany: jest.fn(async () => ({ count: 1 })),
      create: jest.fn(),
    },
    sevenDaysToolRun: {
      create: jest.fn(async ({ data: d }) => {
        if (runs.has(d.requestId)) throw { code: 'P2002' };
        runs.set(d.requestId, { ...d, result: null });
        return d;
      }),
      findUniqueOrThrow: jest.fn(async ({ where }) => runs.get(where.serverId_requestId.requestId)),
      update: jest.fn(async ({ where, data: d }) =>
        Object.assign(runs.get(where.serverId_requestId.requestId), d),
      ),
    },
  };
  const audit: any = { log: jest.fn(async () => {}) };
  const console: any = { run: jest.fn(async () => '') };
  const items: any = {
    search: jest.fn(async () => ({
      sessionId: randomUUID(),
      ready: true,
      items: [
        { name: 'resourceRock', itemId: 1, maxCount: 1000, hasQuality: false },
        { name: 'gunPistol', itemId: 2, maxCount: 1, hasQuality: true },
      ],
    })),
    give: jest.fn(async () => ({ status: 'spawned' })),
  };
  return {
    service: new SevenDaysToolsService(db, audit, items, console),
    data,
    point,
    kit,
    runs,
    db,
    audit,
    console,
    items,
  };
}
describe('7DTD administration tools', () => {
  afterEach(() => jest.useRealTimers());
  it('bounds all stored input, strips undeclared fields and rejects duplicate IDs and invalid coordinates', () => {
    const { data } = fixture();
    expect(validateTools({ ...data, secret: 'discard' })).toEqual(data);
    for (const broken of [
      { ...data, points: [{ ...data.points[0], x: NaN }] },
      { ...data, points: [data.points[0], data.points[0]] },
      { ...data, kits: [{ ...data.kits[0], items: Array(17).fill(data.kits[0]!.items[0]) }] },
      { ...data, points: [{ ...data.points[0], name: 'Home\nshutdown' }] },
    ])
      expect(() => validateTools(broken)).toThrow('invalid_tools');
  });
  it('uses optimistic revision checks and never overwrites a newer config', async () => {
    const { service, data, db } = fixture();
    db.sevenDaysTools.updateMany.mockResolvedValueOnce({ count: 0 });
    await expect(service.save('s', 'admin', data)).rejects.toThrow('tools_revision_conflict');
    expect(db.sevenDaysTools.updateMany.mock.calls[0][0].where).toEqual({
      serverId: 's',
      revision: 1,
    });
  });
  it('sends native ground coordinates once, persists replay protection and rejects mismatched requests', async () => {
    const { service, console } = fixture();
    const body = { requestId: randomUUID(), confirmed: true, position: { x: 10, y: -1, z: 20 } };
    expect(await service.teleport('s', 'a', 'Steam_1', body)).toEqual({ status: 'sent' });
    expect(await service.teleport('s', 'a', 'Steam_1', body)).toEqual({ status: 'sent' });
    expect(console.run).toHaveBeenCalledTimes(1);
    expect(console.run).toHaveBeenCalledWith('s', 'teleportplayer "Steam_1" 10 -1 20');
    await expect(service.teleport('s', 'a', 'Steam_2', body)).rejects.toThrow(
      'request_id_conflict',
    );
    await expect(service.teleport('s', 'a', 'Steam_1\nshutdown', body)).rejects.toThrow();
  });
  it('fails closed before game writes if audit fails and never retries an unknown transport result', async () => {
    const { service, audit, console } = fixture();
    const body = { requestId: randomUUID(), confirmed: true, targetId: 'Steam_2' };
    audit.log.mockRejectedValueOnce(Error('db down'));
    await expect(service.teleport('s', 'a', 'Steam_1', body)).rejects.toThrow('db down');
    expect((await service.teleport('s', 'a', 'Steam_1', body)).status).toBe('unknown');
    expect(console.run).not.toHaveBeenCalled();
    const next = { ...body, requestId: randomUUID() };
    console.run.mockRejectedValueOnce(Error('lost reply'));
    expect((await service.teleport('s', 'a', 'Steam_1', next)).status).toBe('unknown');
    await service.teleport('s', 'a', 'Steam_1', next);
    expect(console.run).toHaveBeenCalledTimes(1);
  });
  it('checks every kit entry before spawning and respects partial results without replay', async () => {
    jest.useFakeTimers();
    const { service, items, kit } = fixture();
    const body = { kitId: kit.id, confirmed: true, requestId: randomUUID() };
    items.give
      .mockResolvedValueOnce({ status: 'spawned' })
      .mockResolvedValueOnce({ status: 'offline' });
    const promise = service.giveKit('s', 'a', 'Steam_1', body);
    await jest.runAllTimersAsync();
    const result = await promise;
    expect(result.status).toBe('partial');
    expect(result.items).toHaveLength(2);
    expect(items.give).toHaveBeenCalledTimes(2);
    await service.giveKit('s', 'a', 'Steam_1', body);
    expect(items.give).toHaveBeenCalledTimes(2);
    items.search.mockResolvedValue({ ready: true, items: [] });
    await expect(
      service.giveKit('s', 'a', 'Steam_1', { ...body, requestId: randomUUID() }),
    ).rejects.toThrow('kit_catalogue_changed');
    expect(items.give).toHaveBeenCalledTimes(2);
  });
  it('checks server scope and separate ADMIN-only write permissions', () => {
    for (const [name, permission] of [
      ['saveTools', 'sevendays.tools.manage'],
      ['teleport', 'sevendays.teleport'],
      ['giveKit', 'sevendays.inventory.give'],
    ] as const) {
      expect(
        Reflect.getMetadata('requiredPermission', SevenDaysController.prototype[name]),
      ).toEqual([permission]);
      expect(Reflect.getMetadata('serverScopeParam', SevenDaysController.prototype[name])).toBe(
        'serverId',
      );
      expect(sevenDaysManifest.permissions.find((p) => p.key === permission)?.defaultRoles).toEqual(
        ['ADMIN'],
      );
    }
  });
});
