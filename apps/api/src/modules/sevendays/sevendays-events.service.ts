import { Injectable, Logger } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { PrismaService } from '../../prisma/prisma.service';

/** Виды событий — контракт с модом, поэтому список закрытый. */
export const SEVENDAYS_EVENT_KINDS = ['chat', 'join', 'leave', 'death', 'player-kill'] as const;
export type SevenDaysEventKind = (typeof SEVENDAYS_EVENT_KINDS)[number];

export interface IncomingEvent {
  eventId?: string;
  kind: string;
  playerId: string;
  playerName: string;
  occurredAt: string;
  text?: string | null;
  actorId?: string | null;
  actorName?: string | null;
  x?: number | null;
  y?: number | null;
  z?: number | null;
}

/**
 * Журнал событий игрового сервера.
 *
 * Смысл — разбор задним числом: «кто кого убил на PvE» и «что было в чате
 * перед жалобой» спрашивают уже после случившегося, когда сама игра об этом
 * ничего не помнит.
 */
@Injectable()
export class SevenDaysEventsService {
  private readonly logger = new Logger(SevenDaysEventsService.name);
  private readonly nextPrune = new Map<string, number>();
  private pruneRunning = false;

  /**
   * Сколько дней хранить.
   *
   * Чат живого сервера — это тысячи строк в день, и без срока таблица росла
   * бы вечно. Двух недель хватает на любой разбор: жалобы приходят по
   * горячим следам, а не через месяц.
   */
  static readonly RETENTION_DAYS = 14;

  /** Максимум событий в одной пачке от мода. Больше — почти наверняка ошибка. */
  static readonly MAX_BATCH = 200;

  constructor(private readonly prisma: PrismaService) {}

  /**
   * Принимает пачку от мода.
   *
   * Непонятные записи отбрасываются молча, а не роняют всю пачку: мод может
   * оказаться новее панели и прислать вид события, которого она ещё не знает.
   * Терять из-за этого остальные события было бы хуже.
   */
  async ingest(serverId: string, events: IncomingEvent[]) {
    const now = Date.now();
    const rows = events
      .slice(0, SevenDaysEventsService.MAX_BATCH)
      .map((event) => this.toRow(serverId, event, now))
      .filter((row): row is NonNullable<ReturnType<typeof this.toRow>> => row !== null);

    if (rows.length === 0) return { accepted: 0, duplicates: 0, discarded: events.length };

    // Unique primary key + ON CONFLICT DO NOTHING: concurrent retries and API
    // restarts are safe, without a race-prone in-memory deduplication cache.
    const result = await this.prisma.sevenDaysEvent.createMany({
      data: rows,
      skipDuplicates: true,
    });
    this.schedulePrune(serverId);
    return {
      accepted: result.count,
      duplicates: rows.length - result.count,
      discarded: events.length - rows.length,
    };
  }

  private toRow(serverId: string, event: IncomingEvent, now: number) {
    // Проверки типов здесь, а не в DTO: контроллер принимает пачку как есть,
    // чтобы одна непонятная запись не уносила остальные сорок девять.
    if (!event || typeof event !== 'object') return null;
    if (!SEVENDAYS_EVENT_KINDS.includes(event.kind as SevenDaysEventKind)) return null;
    if (typeof event.playerId !== 'string' || typeof event.playerName !== 'string') return null;
    if (!event.playerId || !event.playerName) return null;
    if (typeof event.occurredAt !== 'string') return null;

    const occurredAt = new Date(event.occurredAt);
    // Мод мог пролежать с очередью, пока панель была недоступна, но
    // неразобранная дата означала бы событие «в 1970 году» в ленте.
    if (Number.isNaN(occurredAt.getTime())) return null;
    // Expired retries must not recreate records whose dedup keys were pruned.
    if (
      occurredAt.getTime() < now - SevenDaysEventsService.RETENTION_DAYS * 86_400_000 ||
      occurredAt.getTime() > now + 5 * 60_000
    )
      return null;
    if (
      event.eventId !== undefined &&
      (typeof event.eventId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
          event.eventId,
        ))
    )
      return null;

    return {
      // Existing TEXT primary key, scoped by the authenticated server (not payload).
      // Legacy mods without eventId retain generated IDs; never guess by chat text.
      ...(event.eventId === undefined
        ? {}
        : {
            id:
              'sdtd_' +
              createHash('sha256')
                .update(serverId)
                .update('\0')
                .update(event.eventId.toLowerCase())
                .digest('hex'),
          }),
      serverId,
      kind: event.kind,
      playerId: event.playerId.slice(0, 128),
      playerName: event.playerName.slice(0, 64),
      text: text(event.text, 500),
      actorId: text(event.actorId, 128),
      actorName: text(event.actorName, 64),
      x: finiteOrNull(event.x),
      y: finiteOrNull(event.y),
      z: finiteOrNull(event.z),
      occurredAt,
    };
  }

  private schedulePrune(serverId: string): void {
    const now = Date.now();
    if (this.pruneRunning || (this.nextPrune.get(serverId) ?? 0) > now) return;
    // At most one indexed delete at a time, once per active server/hour; bounded
    // bookkeeping even when this service manages many short-lived game servers.
    if (this.nextPrune.size >= 256 && !this.nextPrune.has(serverId))
      this.nextPrune.delete(this.nextPrune.keys().next().value!);
    this.nextPrune.set(serverId, now + 3_600_000);
    this.pruneRunning = true;
    void this.prune(serverId).finally(() => {
      this.pruneRunning = false;
    });
  }

  private async prune(serverId: string): Promise<void> {
    const cutoff = new Date(Date.now() - SevenDaysEventsService.RETENTION_DAYS * 86_400_000);
    try {
      await this.prisma.sevenDaysEvent.deleteMany({
        where: { serverId, occurredAt: { lt: cutoff } },
      });
    } catch (e) {
      this.nextPrune.set(serverId, Date.now() + 60_000);
      // Не удалось подрезать — не повод отвергать принятые события.
      this.logger.warn(`Не удалось подрезать журнал событий: ${(e as Error).message}`);
    }
  }

  /** Лента для интерфейса, свежие сверху. */
  async list(serverId: string, options: { kind?: string; limit?: number } = {}) {
    this.schedulePrune(serverId);
    const limit = Math.min(Math.max(options.limit ?? 100, 1), 500);
    const kind = SEVENDAYS_EVENT_KINDS.includes(options.kind as SevenDaysEventKind)
      ? options.kind
      : undefined;

    const rows = await this.prisma.sevenDaysEvent.findMany({
      where: {
        serverId,
        occurredAt: {
          gte: new Date(Date.now() - SevenDaysEventsService.RETENTION_DAYS * 86_400_000),
        },
        ...(kind ? { kind } : {}),
      },
      orderBy: { occurredAt: 'desc' },
      take: limit,
    });

    return rows.map((row) => ({
      id: row.id,
      kind: row.kind,
      playerId: row.playerId,
      playerName: row.playerName,
      text: row.text,
      actorId: row.actorId,
      actorName: row.actorName,
      position:
        row.x !== null && row.y !== null && row.z !== null
          ? { x: row.x, y: row.y, z: row.z }
          : null,
      occurredAt: row.occurredAt.toISOString(),
    }));
  }
}

function finiteOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Строка нужной длины или null. Всё, что не строка, — это null, а не «undefined». */
function text(value: unknown, max: number): string | null {
  return typeof value === 'string' && value.length > 0 ? value.slice(0, max) : null;
}
