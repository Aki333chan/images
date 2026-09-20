import {
  BadRequestException,
  ConflictException,
  Injectable,
  ServiceUnavailableException,
} from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import { Prisma } from '@prisma/client';
import type {
  SevenDaysTools,
  SevenDaysToolResult,
  SevenDaysPoint,
  SevenDaysKitItem,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { AuditService } from '../../audit/audit.service';
import { SevenDaysItemsService } from './sevendays-items.service';
import { SevenDaysConsoleService, arg } from './sevendays-console.service';
import { validateInventoryPlayerId } from './sevendays-inventory';

const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
const integer = (v: unknown, min: number, max: number): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max;
const text = (v: unknown, max: number): v is string =>
  // eslint-disable-next-line no-control-regex -- reject control bytes at the input boundary
  typeof v === 'string' && v.trim().length > 0 && v.length <= max && !/[\x00-\x1f\x7f]/.test(v);
const object = (v: unknown): Record<string, unknown> =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
function invalid(): never {
  throw new BadRequestException('invalid_tools');
}
export function validatePoint(value: unknown): asserts value is SevenDaysPoint {
  const p = object(value);
  if (!integer(p.x, -100000, 100000) || !integer(p.z, -100000, 100000) || !integer(p.y, -1, 255))
    invalid();
}
export function validateTools(value: unknown): SevenDaysTools {
  const b = object(value);
  if (!integer(b.revision, 0, 2147483646)) invalid();
  for (const key of ['points', 'kits']) {
    const rows = b[key];
    if (!Array.isArray(rows) || rows.length > 100) invalid();
    const ids = new Set();
    for (const raw of rows) {
      const p = object(raw);
      if (!uuid(p.id) || ids.has(p.id) || !text(p.name, 64)) invalid();
      ids.add(p.id);
      if (key === 'points') validatePoint(p);
      if (key === 'kits') {
        if (!Array.isArray(p.items) || p.items.length < 1 || p.items.length > 16) invalid();
        for (const rawItem of p.items) {
          const i = object(rawItem);
          if (
            !text(i.name, 128) ||
            i.name.length < 2 ||
            !integer(i.count, 1, 1000) ||
            !integer(i.quality, 0, 6)
          )
            invalid();
        }
      }
    }
  }
  // Closed serialization keeps undeclared input out of stored JSON and its size bounded.
  const d = value as SevenDaysTools;
  return {
    revision: d.revision,
    points: d.points.map(({ id, name, x, y, z }) => ({ id, name: name.trim(), x, y, z })),
    kits: d.kits.map(({ id, name, items }) => ({
      id,
      name: name.trim(),
      items: items.map(({ name, count, quality }) => ({ name, count, quality })),
    })),
  };
}

@Injectable()
export class SevenDaysToolsService {
  private active = 0;
  constructor(
    private readonly db: PrismaService,
    private readonly audit: AuditService,
    private readonly items: SevenDaysItemsService,
    private readonly console: SevenDaysConsoleService,
  ) {}

  async read(serverId: string): Promise<SevenDaysTools> {
    const row = await this.db.sevenDaysTools.findUnique({ where: { serverId } });
    return row
      ? validateTools({ ...object(row.data), revision: row.revision })
      : { revision: 0, points: [], kits: [] };
  }
  async save(serverId: string, actorId: string, input: unknown) {
    const { revision, ...data } = validateTools(input);
    const json = data as unknown as Prisma.InputJsonValue;
    await this.audit.log({
      actorId,
      action: 'sevendays.tools.save',
      targetType: 'server',
      targetId: serverId,
      metadata: { revision, data },
    });
    if (revision === 0) {
      try {
        await this.db.sevenDaysTools.create({ data: { serverId, data: json } });
      } catch (e) {
        if ((e as { code?: string }).code === 'P2002')
          throw new ConflictException('tools_revision_conflict');
        throw e;
      }
    } else {
      const changed = await this.db.sevenDaysTools.updateMany({
        where: { serverId, revision },
        data: { data: json, revision: { increment: 1 } },
      });
      if (!changed.count) throw new ConflictException('tools_revision_conflict');
    }
    return { ...data, revision: revision + 1 };
  }

  private async execute(
    serverId: string,
    actorId: string,
    rawRequestId: unknown,
    payload: unknown,
    work: () => Promise<SevenDaysToolResult>,
  ) {
    if (!uuid(rawRequestId)) throw new BadRequestException('invalid_request_id');
    const requestId = rawRequestId.toLowerCase();
    const fingerprint = createHash('sha256')
      .update(JSON.stringify({ actorId, payload }))
      .digest('hex');
    if (this.active >= 4) throw new ServiceUnavailableException('tools_busy');
    this.active++;
    try {
      try {
        await this.db.sevenDaysToolRun.create({ data: { serverId, requestId, fingerprint } });
      } catch (e) {
        if ((e as { code?: string }).code !== 'P2002') throw e;
        const existing = await this.db.sevenDaysToolRun.findUniqueOrThrow({
          where: { serverId_requestId: { serverId, requestId } },
        });
        if (existing.fingerprint !== fingerprint)
          throw new ConflictException('request_id_conflict');
        return (existing.result as unknown as SevenDaysToolResult) ?? { status: 'unknown' };
      }
      await this.audit.log({
        actorId,
        action: 'sevendays.tools.attempt',
        targetType: 'server',
        targetId: serverId,
        metadata: { requestId, payload },
      });
      let result: SevenDaysToolResult;
      try {
        result = await work();
      } catch {
        result = { status: 'unknown' };
      }
      // The durable initial claim prevents replay even if recording a result fails.
      try {
        await this.db.sevenDaysToolRun.update({
          where: { serverId_requestId: { serverId, requestId } },
          data: { result: result as unknown as Prisma.InputJsonValue },
        });
      } catch {
        /* No retry of game writes. */
      }
      return result;
    } finally {
      this.active--;
    }
  }
  async teleport(serverId: string, actorId: string, playerId: string, input: unknown) {
    validateInventoryPlayerId(playerId);
    const b = object(input);
    if (b.confirmed !== true) invalid();
    let destination: string;
    if (typeof b.targetId === 'string') {
      validateInventoryPlayerId(b.targetId);
      if (b.targetId === playerId || b.pointId !== undefined || b.position !== undefined) invalid();
      destination = arg(b.targetId);
    } else {
      if ((b.pointId === undefined) === (b.position === undefined)) invalid();
      const p =
        b.pointId !== undefined
          ? (await this.read(serverId)).points.find((p) => p.id === b.pointId)
          : b.position;
      validatePoint(p);
      destination = `${p.x} ${p.y} ${p.z}`;
    }
    const command = `teleportplayer ${arg(playerId)} ${destination}`;
    return this.execute(serverId, actorId, b.requestId, { command }, async () => {
      const output = await this.console.run(serverId, command);
      if (/not found|not a valid|wrong number|error|exception/i.test(output))
        return { status: 'unknown' };
      // Native command sends a client packet; never claim arrival was verified.
      return { status: 'sent' };
    });
  }
  async giveKit(serverId: string, actorId: string, playerId: string, input: unknown) {
    validateInventoryPlayerId(playerId);
    const b = object(input);
    if (!uuid(b.kitId) || b.confirmed !== true) invalid();
    const kit = (await this.read(serverId)).kits.find((k) => k.id === b.kitId);
    if (!kit) throw new BadRequestException('kit_not_found');
    // All entries must still exist in this server's current catalogue before the first spawn.
    const grants: (SevenDaysKitItem & { itemId: number; sessionId: string })[] = [];
    for (const line of kit.items) {
      const catalogue = await this.items.search(serverId, line.name.slice(0, 64));
      const item = catalogue.items.find((i) => i.name === line.name);
      if (
        !catalogue.ready ||
        !item ||
        line.count > item.maxCount ||
        (item.hasQuality ? line.quality < 1 : line.quality !== 0)
      )
        throw new BadRequestException('kit_catalogue_changed');
      grants.push({ ...line, itemId: item.itemId, sessionId: catalogue.sessionId });
    }
    return this.execute(serverId, actorId, b.requestId, { playerId, kit }, async () => {
      const results: NonNullable<SevenDaysToolResult['items']> = [];
      for (const line of grants) {
        // Respect the mod's global one-second grant gate; never busy-wait or retry a spawn.
        await new Promise((resolve) => setTimeout(resolve, 1100));
        const result = await this.items
          .give(serverId, playerId, actorId, {
            ...line,
            itemName: line.name,
            requestId: randomUUID(),
            reason: `Kit: ${kit.name}`,
            confirmed: true,
          })
          .catch(() => ({ status: 'unknown' }));
        results.push({ name: line.name, status: result.status });
        if (result.status !== 'spawned') return { status: 'partial', items: results };
      }
      return { status: 'complete', items: results };
    });
  }
}
