import 'reflect-metadata';
import {
  parseInventory,
  parseSavedPlayers,
  validateInventoryPlayerId,
} from './sevendays-inventory';
import { SevenDaysController } from './sevendays.controller';
import { sevenDaysManifest } from './sevendays.def';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysConfigService } from './sevendays-config.service';

const item = {
  section: 'bag',
  slot: 2,
  itemId: 10,
  name: '<script>moddedItem</script>',
  count: 5,
  quality: 6,
};
const snapshot = { available: true, source: 'client_snapshot', items: [item], truncated: false };
describe('read-only 7DTD inventory', () => {
  it('distinguishes saved data and rejects invalid timestamps and mixed sources', () => {
    const saved = { ...snapshot, source: 'saved_file', savedAt: '2026-09-20T12:00:00Z' };
    expect(parseInventory(saved, 'saved_file')).toMatchObject({
      source: 'saved_file',
      savedAt: saved.savedAt,
      available: true,
    });
    expect(() => parseInventory(saved)).toThrow();
    for (const savedAt of [null, '', 'bad', 42])
      expect(() => parseInventory({ ...saved, savedAt }, 'saved_file')).toThrow();
    for (const reason of ['save_missing', 'save_invalid', 'save_busy', 'save_unavailable']) {
      expect(parseInventory({ available: false, reason }, 'saved_file')).toMatchObject({
        available: false,
        source: 'saved_file',
        reason,
        items: [],
      });
      expect(() => parseInventory({ available: false, reason })).toThrow();
    }
  });
  it('bounds the known-player catalogue and strips extra private fields', () => {
    const list = {
      ready: true,
      players: [{ id: 'Steam_1', name: 'Tester', ip: 'hidden' }],
      hasMore: false,
      truncated: false,
    };
    expect(parseSavedPlayers(list).players).toEqual([{ id: 'Steam_1', name: 'Tester' }]);
    for (const players of [
      Array(51).fill(list.players[0]),
      [list.players[0], list.players[0]],
      [{ id: '../Steam_1', name: 'x' }],
      [{ id: 'Steam_1', name: 'x'.repeat(81) }],
    ])
      expect(() => parseSavedPlayers({ ...list, players })).toThrow();
  });
  it('protects both saved read routes with scoped inventory permission', () => {
    for (const method of [
      SevenDaysController.prototype.savedPlayers,
      SevenDaysController.prototype.savedInventory,
    ]) {
      expect(Reflect.getMetadata('requiredPermission', method)).toEqual([
        'sevendays.inventory.view',
      ]);
      expect(Reflect.getMetadata('serverScopeParam', method)).toBe('serverId');
    }
  });
  it('requires saved-read capability and validates search before contacting the mod', async () => {
    const service = new SevenDaysCompanionService({} as SevenDaysConfigService);
    const ping = jest
      .spyOn(service, 'ping')
      .mockResolvedValue({
        version: '1',
        contract: '1',
        compatible: true,
        capabilities: ['inventory-read'],
        language: null,
      });
    await expect(service.savedPlayers('s', 'x'.repeat(81), 0)).rejects.toThrow();
    await expect(service.savedPlayers('s', '', -1)).rejects.toThrow();
    expect(ping).not.toHaveBeenCalled();
    await expect(service.savedPlayers('s', '', 0)).resolves.toMatchObject({
      ready: false,
      reason: 'mod_update',
    });
    await expect(service.inventory('s', 'Steam_1', true)).resolves.toMatchObject({
      source: 'saved_file',
      reason: 'mod_update',
    });
  });
  it('supports old snapshots without guessing capacity and validates native slot counts', () => {
    expect(parseInventory(snapshot).slotCounts).toBeNull();
    const slotCounts = { belt: 10, bag: 45, equipment: 4, cursor: 1 };
    expect(parseInventory({ ...snapshot, slotCounts }).slotCounts).toEqual(slotCounts);
    for (const counts of [
      null,
      [],
      {},
      { ...slotCounts, belt: -1 },
      { ...slotCounts, bag: 257 },
      { ...slotCounts, bag: 2 },
      { ...slotCounts, cursor: 1.5 },
    ])
      expect(() => parseInventory({ ...snapshot, slotCounts: counts })).toThrow(
        'invalid_inventory_response',
      );
    expect(
      parseInventory({
        ...snapshot,
        items: [],
        slotCounts: { belt: 0, bag: 0, equipment: 0, cursor: 0 },
      }).items,
    ).toEqual([]);
  });
  it('allows platform IDs only', () => {
    validateInventoryPlayerId('Steam_76561190000000000');
    validateInventoryPlayerId('EOS_a123');
    for (const id of [
      'Alice',
      '../Steam_1',
      'Steam_1\n',
      'Steam_1?x=1',
      'Steam_' + 'a'.repeat(160),
    ])
      expect(() => validateInventoryPlayerId(id)).toThrow('invalid_player_id');
  });
  it('keeps only display fields and labels fetch time, not snapshot age', () => {
    const result = parseInventory({ ...snapshot, items: [{ ...item, privateData: 'hidden' }] });
    expect(result.items).toEqual([item]);
    expect(Number.isFinite(Date.parse(result.fetchedAt!))).toBe(true);
    expect(result.source).toBe('client_snapshot');
    expect(parseInventory({ available: false, reason: 'offline', items: [item] }).items).toEqual(
      [],
    );
    expect(
      parseInventory({ available: true, source: 'client_snapshot', items: [], truncated: false })
        .available,
    ).toBe(true);
  });
  it('rejects malformed responses rather than showing a misleading empty inventory', () => {
    for (const value of [
      null,
      {},
      { available: false, reason: 'anything' },
      { ...snapshot, items: Array(322).fill(item) },
      { ...snapshot, items: [item, item] },
      ...[
        { slot: 256 },
        { section: '__proto__' },
        { count: -1 },
        { quality: NaN },
        { name: 'a'.repeat(129) },
        { itemId: 0 },
      ].map((change) => ({ ...snapshot, items: [{ ...item, ...change }] })),
    ])
      expect(() => parseInventory(value)).toThrow('invalid_inventory_response');
  });
  it('requires a server-scoped inventory permission, not just player-list access', () => {
    expect(
      Reflect.getMetadata('requiredPermission', SevenDaysController.prototype.inventory),
    ).toEqual(['sevendays.inventory.view']);
    expect(Reflect.getMetadata('serverScopeParam', SevenDaysController.prototype.inventory)).toBe(
      'serverId',
    );
    expect(
      sevenDaysManifest.permissions.find((p) => p.key === 'sevendays.inventory.view')?.defaultRoles,
    ).toEqual(['ADMIN']);
  });
  it('bounds parallel requests and releases slots even when an old mod is used', async () => {
    const service = new SevenDaysCompanionService({} as SevenDaysConfigService);
    let release!: () => void;
    const waiting = new Promise<void>((r) => {
      release = r;
    });
    jest.spyOn(service, 'ping').mockImplementation(async () => {
      await waiting;
      return { version: '1', contract: '1', compatible: true, capabilities: [], language: null };
    });
    const pending = Array.from({ length: 4 }, () => service.inventory('s', 'Steam_1'));
    await expect(service.inventory('s', 'Steam_1')).rejects.toThrow('inventory_busy');
    release();
    expect((await Promise.all(pending)).every((p) => p.reason === 'mod_update')).toBe(true);
    await expect(service.inventory('s', 'Steam_1')).resolves.toMatchObject({
      reason: 'mod_update',
    });
  });
});
