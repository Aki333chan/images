import 'reflect-metadata';
import { validate } from 'class-validator';
import { ItemGrantDto, SavedStackDto } from './dto';
import { parseItemCatalogue, SevenDaysItemsService } from './sevendays-items.service';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { AuditService } from '../../audit/audit.service';
import { SevenDaysController } from './sevendays.controller';
import { sevenDaysManifest } from './sevendays.def';

const sessionId = 'ac67354a-2548-4dc5-8f23-a43a90b55d9d';
const requestId = 'a76f984d-e869-4793-b09f-cda9cc17e42c';
const grant = {
  sessionId,
  requestId,
  itemId: 1,
  itemName: 'test',
  count: 5,
  quality: 0,
  reason: 'test grant',
  confirmed: true,
};
const catalogue = {
  sessionId,
  catalogue: {
    ready: true,
    truncated: false,
    items: [{ itemId: 1, name: 'test', hasQuality: false, maxCount: 1000 }],
  },
};
function fixture() {
  const companion = {
    ping: jest.fn().mockResolvedValue({ compatible: true, capabilities: ['item-drop'] }),
    itemSearch: jest.fn().mockResolvedValue(catalogue),
    itemDrop: jest.fn().mockResolvedValue({ status: 'spawned', requestId }),
    savedStack: jest.fn().mockResolvedValue({ status: 'saved', requestId }),
  };
  const audit = { log: jest.fn().mockResolvedValue(undefined) };
  const service = new SevenDaysItemsService(
    companion as unknown as SevenDaysCompanionService,
    audit as unknown as AuditService,
  );
  return { service, companion, audit };
}
describe('7DTD item grants', () => {
  it('audits saved edits before writing, requires capability and never retries unknown results', async () => {
    const { service, companion, audit } = fixture();
    const dto = { requestId, revision: 'a'.repeat(64), section: 'bag' as const, slot: 0, count: 0, confirmed: true };
    await expect(service.reduceSaved('s', 'Steam_1', 'admin', dto)).rejects.toThrow('saved_edit_requires');
    expect(companion.savedStack).not.toHaveBeenCalled();
    companion.ping.mockResolvedValue({ compatible: true, capabilities: ['inventory-saved-reduce'] });
    audit.log.mockRejectedValueOnce(new Error('db down'));
    await expect(service.reduceSaved('s', 'Steam_1', 'admin', dto)).rejects.toThrow('db down');
    expect(companion.savedStack).not.toHaveBeenCalled();
    await expect(service.reduceSaved('s', 'Steam_1', 'admin', dto)).resolves.toMatchObject({ status: 'saved' });
    expect(audit.log).toHaveBeenCalledWith(expect.objectContaining({ action: 'sevendays.saved-stack.attempt' }));
    companion.savedStack.mockRejectedValueOnce(new Error('reply lost'));
    await expect(service.reduceSaved('s', 'Steam_1', 'admin', dto)).resolves.toMatchObject({ status: 'unknown' });
    expect(companion.savedStack).toHaveBeenCalledTimes(2);
    expect(Reflect.getMetadata('requiredPermission', SevenDaysController.prototype.savedStack)).toEqual(['sevendays.inventory.edit']);
    expect(Reflect.getMetadata('serverScopeParam', SevenDaysController.prototype.savedStack)).toBe('serverId');
    expect(await validate(Object.assign(new SavedStackDto(), dto))).toHaveLength(0);
    for (const bad of [{ confirmed: false }, { section: 'equipment' }, { slot: -1 }, { count: -1 }, { count: 1.5 }, { revision: 'bad' }, { requestId: 'bad' }])
      expect((await validate(Object.assign(new SavedStackDto(), dto, bad))).length).toBeGreaterThan(0);
  });
  it('requires distinct server-scoped admin permission on search and write', () => {
    for (const route of [
      SevenDaysController.prototype.itemSearch,
      SevenDaysController.prototype.itemDrop,
    ]) {
      expect(Reflect.getMetadata('requiredPermission', route)).toEqual([
        'sevendays.inventory.give',
      ]);
      expect(Reflect.getMetadata('serverScopeParam', route)).toBe('serverId');
    }
    expect(
      sevenDaysManifest.permissions.find((p) => p.key === 'sevendays.inventory.give')?.defaultRoles,
    ).toEqual(['ADMIN']);
  });
  it('validates confirmation, integers, bounds and request IDs', async () => {
    expect(await validate(Object.assign(new ItemGrantDto(), grant))).toHaveLength(0);
    for (const change of [
      { count: 0 },
      { count: 1001 },
      { count: 1.2 },
      { quality: 7 },
      { itemId: -1 },
      { confirmed: false },
      { requestId: 'no' },
      { reason: '  ' },
      { sessionId: 'none' },
    ])
      expect(
        (await validate(Object.assign(new ItemGrantDto(), grant, change))).length,
      ).toBeGreaterThan(0);
  });
  it('rejects broken catalogues and unsafe targets', async () => {
    expect(parseItemCatalogue(catalogue).items).toHaveLength(1);
    for (const broken of [
      null,
      {},
      { ...catalogue, sessionId: 'bad' },
      {
        ...catalogue,
        catalogue: { ...catalogue.catalogue, items: Array(101).fill(catalogue.catalogue.items[0]) },
      },
      {
        ...catalogue,
        catalogue: {
          ...catalogue.catalogue,
          items: [{ ...catalogue.catalogue.items[0], maxCount: 1001 }],
        },
      },
    ])
      expect(() => parseItemCatalogue(broken)).toThrow();
    const { service, companion } = fixture();
    await expect(service.give('s', 'Alice', 'a', grant)).rejects.toThrow('invalid_player_id');
    await expect(service.search('s', 'x')).rejects.toThrow('invalid_item_search');
    await expect(service.search('s', ['ab'] as unknown as string)).rejects.toThrow(
      'invalid_item_search',
    );
    expect(companion.itemDrop).not.toHaveBeenCalled();
  });
  it('audits before mutation, keeps exact replay ID and audits results', async () => {
    const { service, companion, audit } = fixture();
    await expect(service.give('s', 'Steam_1', 'actor', grant)).resolves.toEqual({
      status: 'spawned',
      requestId,
    });
    expect(audit.log.mock.invocationCallOrder[0]).toBeLessThan(
      companion.itemDrop.mock.invocationCallOrder[0]!,
    );
    expect(companion.itemDrop).toHaveBeenCalledWith('s', 'Steam_1', grant);
    expect(audit.log).toHaveBeenLastCalledWith(
      expect.objectContaining({
        actorId: 'actor',
        metadata: expect.objectContaining({ status: 'spawned', requestId }),
      }),
    );
  });
  it('refuses writes without durable attempt audit or capable companion', async () => {
    const { service, companion, audit } = fixture();
    audit.log.mockRejectedValueOnce(new Error('db offline'));
    await expect(service.give('s', 'Steam_1', 'a', grant)).rejects.toThrow('db offline');
    expect(companion.itemDrop).not.toHaveBeenCalled();
    companion.ping.mockResolvedValue({ compatible: true, capabilities: [] });
    await expect(service.give('s', 'Steam_1', 'a', grant)).rejects.toThrow('item_drop_requires');
    expect(companion.itemDrop).not.toHaveBeenCalled();
  });
  it('never retries ambiguous writes and does not misreport missing result audit', async () => {
    const { service, companion, audit } = fixture();
    companion.itemDrop.mockRejectedValueOnce(new Error('timeout'));
    await expect(service.give('s', 'Steam_1', 'a', grant)).resolves.toEqual({
      status: 'unknown',
      requestId,
    });
    expect(companion.itemDrop).toHaveBeenCalledTimes(1);
    audit.log
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('lost audit connection'));
    await expect(service.give('s', 'Steam_1', 'a', grant)).resolves.toEqual({
      status: 'spawned',
      requestId,
    });
  });
  it('bounds concurrent searches and releases capacity', async () => {
    const { service, companion } = fixture();
    let release!: () => void;
    const wait = new Promise<void>((r) => {
      release = r;
    });
    companion.itemSearch.mockImplementation(async () => {
      await wait;
      return catalogue;
    });
    const jobs = Array.from({ length: 4 }, () => service.search('s', 'ammo'));
    await expect(service.search('s', 'ammo')).rejects.toThrow('inventory_busy');
    release();
    await Promise.all(jobs);
    await expect(service.search('s', 'ammo')).resolves.toMatchObject({ ready: true });
  });
});
