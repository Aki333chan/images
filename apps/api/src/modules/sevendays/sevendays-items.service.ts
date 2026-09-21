import { BadRequestException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { AuditService } from '../../audit/audit.service';
import { validateInventoryPlayerId } from './sevendays-inventory';
import { ItemGrantDto, SavedStackDto } from './dto';
import {
  SEVENDAYS_GRANT_STATUSES,
  type SevenDaysItemCatalogue,
  type SevenDaysItemGrantResult,
} from '@aurum/shared';

export function parseItemCatalogue(value: unknown): SevenDaysItemCatalogue {
  const b = value as { sessionId?: unknown; catalogue?: Record<string, unknown> } | null;
  const c = b?.catalogue;
  if (
    !b ||
    typeof b.sessionId !== 'string' ||
    !/^[a-f0-9-]{36}$/i.test(b.sessionId) ||
    !c ||
    typeof c.ready !== 'boolean' ||
    typeof c.truncated !== 'boolean' ||
    !Array.isArray(c.items) ||
    c.items.length > 100
  )
    throw new BadRequestException('invalid_item_catalogue');
  const ids = new Set<number>();
  const items = c.items.map((raw: unknown) => {
    const p = raw as SevenDaysItemCatalogue['items'][number] | null;
    if (
      !p ||
      !Number.isInteger(p.itemId) ||
      p.itemId < 1 ||
      p.itemId > 2147483647 ||
      ids.has(p.itemId) ||
      typeof p.name !== 'string' ||
      !p.name ||
      p.name.length > 128 ||
      typeof p.hasQuality !== 'boolean' ||
      !Number.isInteger(p.maxCount) ||
      p.maxCount < 1 ||
      p.maxCount > 1000 ||
      (p.hasQuality && p.maxCount !== 1)
    )
      throw new BadRequestException('invalid_item_catalogue');
    ids.add(p.itemId);
    return { itemId: p.itemId, name: p.name, hasQuality: p.hasQuality, maxCount: p.maxCount };
  });
  return { sessionId: b.sessionId, ready: c.ready, truncated: c.truncated, items };
}

@Injectable()
export class SevenDaysItemsService {
  private active = 0;
  constructor(
    private readonly companion: SevenDaysCompanionService,
    private readonly audit: AuditService,
  ) {}
  private async supported(serverId: string) {
    const ping = await this.companion.ping(serverId);
    if (!ping?.compatible || !ping.capabilities?.includes('item-drop'))
      throw new BadRequestException('item_drop_requires_companion_1_0_9');
  }
  async reduceSaved(serverId: string, playerId: string, actorId: string, dto: SavedStackDto) {
    validateInventoryPlayerId(playerId);
    if (
      dto.operation === 'replace'
        ? dto.count < 1 || dto.count > 1000
        : dto.itemId !== undefined || dto.itemName !== undefined || dto.quality !== undefined
    )
      throw new BadRequestException('invalid_stack');
    if (this.active >= 4) throw new ServiceUnavailableException('inventory_busy');
    this.active++;
    try {
      const ping = await this.companion.ping(serverId);
      if (
        !ping?.compatible ||
        !ping.capabilities?.includes(
          dto.operation === 'replace' ? 'inventory-saved-replace' : 'inventory-saved-reduce',
        )
      )
        throw new BadRequestException(
          dto.operation === 'replace'
            ? 'saved_edit_requires_companion_1_0_12'
            : 'saved_edit_requires_companion_1_0_11',
        );
      const metadata = { playerId, ...dto };
      await this.audit.log({
        actorId,
        action: 'sevendays.saved-stack.attempt',
        targetType: 'server',
        targetId: serverId,
        metadata,
      });
      let status = 'unknown';
      try {
        const raw = (await this.companion.savedStack(serverId, playerId, dto)) as {
          requestId?: string;
          status?: string;
        } | null;
        if (
          raw?.requestId === dto.requestId.toLowerCase() &&
          [
            'saved',
            'busy',
            'offline_required',
            'revision_conflict',
            'unsupported',
            'invalid_stack',
            'write_failed',
            'unknown',
          ].includes(raw.status ?? '')
        )
          status = raw!.status!;
      } catch {
        /* No retries: a lost response does not prove that the save was unchanged. */
      }
      try {
        await this.audit.log({
          actorId,
          action: 'sevendays.saved-stack.result',
          targetType: 'server',
          targetId: serverId,
          metadata: { ...metadata, status },
        });
      } catch {
        /* Attempt already recorded. */
      }
      return { requestId: dto.requestId.toLowerCase(), status };
    } finally {
      this.active--;
    }
  }
  async search(serverId: string, query: string) {
    if (typeof query !== 'string') throw new BadRequestException('invalid_item_search');
    query = query.trim();
    // eslint-disable-next-line no-control-regex -- explicitly reject control characters at this input boundary
    if (query.length < 2 || query.length > 64 || /[\x00-\x1f\x7f]/.test(query))
      throw new BadRequestException('invalid_item_search');
    if (this.active >= 4) throw new ServiceUnavailableException('inventory_busy');
    this.active++;
    try {
      await this.supported(serverId);
      return parseItemCatalogue(await this.companion.itemSearch(serverId, query));
    } finally {
      this.active--;
    }
  }
  async give(
    serverId: string,
    playerId: string,
    actorId: string,
    dto: ItemGrantDto,
  ): Promise<SevenDaysItemGrantResult> {
    validateInventoryPlayerId(playerId);
    if (this.active >= 4) throw new ServiceUnavailableException('inventory_busy');
    this.active++;
    try {
      await this.supported(serverId);
      const metadata = { playerId, ...dto };
      // Fail closed before a write if the audit database is unavailable.
      await this.audit.log({
        actorId,
        action: 'sevendays.item-drop.attempt',
        targetType: 'server',
        targetId: serverId,
        metadata,
      });
      let status: SevenDaysItemGrantResult['status'] = 'unknown';
      try {
        const raw = (await this.companion.itemDrop(
          serverId,
          playerId,
          dto,
        )) as Partial<SevenDaysItemGrantResult> | null;
        if (
          raw &&
          raw.requestId === dto.requestId.toLowerCase() &&
          SEVENDAYS_GRANT_STATUSES.includes(raw.status!)
        )
          status = raw.status!;
      } catch {
        /* A timeout is not proof that a mutating operation failed. Never auto-retry. */
      }
      try {
        await this.audit.log({
          actorId,
          action: 'sevendays.item-drop.result',
          targetType: 'server',
          targetId: serverId,
          metadata: { ...metadata, status },
        });
      } catch {
        /* Attempt is already durable. Do not misreport a completed spawn as a failed write. */
      }
      return { requestId: dto.requestId.toLowerCase(), status };
    } finally {
      this.active--;
    }
  }
}
