import { Injectable, Logger } from '@nestjs/common';
import {
  ALERT_TYPES,
  cpuUsage,
  memoryUsage,
  type AlertSettingsDto,
  type AlertType,
  type ServerMetricsDto,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { ClientApiService } from '../../pterodactyl/client-api.service';
import { PlayerCountRegistry } from './player-count.registry';

const MIB = 1024 * 1024;

/** Одно измерение, приведённое к доле от лимита. */
export interface Reading {
  type: AlertType;
  /** Процент от лимита. null — лимита нет, сравнивать не с чем. */
  percentOfLimit: number | null;
}

/**
 * Снимки нагрузки серверов и вычисление того, пора ли слать алерт.
 *
 * Решение о письме принимается ЗДЕСЬ и отдельно от отправки: логика «порог
 * превышен дольше N минут, и с прошлого письма прошло больше кулдауна» — это
 * то, что легко сломать незаметно, и её надо проверять тестами без почты,
 * без базы и без Pterodactyl. Поэтому чистая часть вынесена в decideAlert().
 */
@Injectable()
export class ServerMetricsService {
  private readonly logger = new Logger(ServerMetricsService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly client: ClientApiService,
    private readonly players: PlayerCountRegistry,
  ) {}

  /**
   * Опрашивает один сервер и сохраняет снимок.
   *
   * Возвращает измерения в долях от лимита — их дальше смотрит проверка
   * алертов. null возвращается, когда сервер не ответил: это обычное дело
   * (выключен, нода моргнула), и снимок в таком случае не обновляется —
   * старые цифры честнее выдуманных нулей.
   */
  async sample(server: {
    id: string;
    pteroIdentifier: string;
    moduleId: string | null;
    memoryLimitMb: number | null;
    cpuLimitPercent: number | null;
  }): Promise<Reading[] | null> {
    const raw = await this.client.getResources(server.pteroIdentifier).catch(() => null);
    if (!raw) return null;

    // Счётчик игроков — best-effort и не влияет на сохранение снимка:
    // нагрузка известна и без него.
    const players = await this.players.count(server.moduleId, server.id);

    const data = {
      state: raw.current_state,
      cpuAbsolute: raw.resources.cpu_absolute,
      memoryBytes: BigInt(Math.round(raw.resources.memory_bytes)),
      playersOnline: players?.online ?? null,
      playersMax: players?.max ?? null,
      sampledAt: new Date(),
    };
    await this.prisma.serverMetricSample.upsert({
      where: { serverId: server.id },
      create: { serverId: server.id, ...data },
      update: data,
    });

    // Выключенный сервер не перегружен — он просто выключен. Считать по нему
    // пороги значило бы слать алерты о нулевой нагрузке или, хуже, оставлять
    // висеть отметку о превышении с прошлого запуска.
    if (raw.current_state !== 'running') return [];

    const cpu = cpuUsage(raw.resources.cpu_absolute, server.cpuLimitPercent);
    const memory = memoryUsage(raw.resources.memory_bytes, (server.memoryLimitMb ?? 0) * MIB);
    return [
      { type: 'cpu', percentOfLimit: cpu.percentOfLimit },
      { type: 'memory', percentOfLimit: memory.percentOfLimit },
    ];
  }

  /** Снимки по перечисленным серверам — для списка серверов. */
  async listFor(
    servers: {
      id: string;
      memoryLimitMb: number | null;
      cpuLimitPercent: number | null;
    }[],
  ): Promise<ServerMetricsDto[]> {
    const rows = await this.prisma.serverMetricSample.findMany({
      where: { serverId: { in: servers.map((s) => s.id) } },
    });
    const byId = new Map(rows.map((r) => [r.serverId, r]));

    return servers.map((server) => {
      const row = byId.get(server.id);
      return {
        serverId: server.id,
        state: row?.state ?? null,
        cpuAbsolutePercent: row?.cpuAbsolute ?? null,
        cpuLimitPercent: server.cpuLimitPercent ?? 0,
        memoryBytes: row?.memoryBytes === null || row?.memoryBytes === undefined
          ? null
          : Number(row.memoryBytes),
        memoryLimitBytes: (server.memoryLimitMb ?? 0) * MIB,
        playersOnline: row?.playersOnline ?? null,
        playersMax: row?.playersMax ?? null,
        sampledAt: row?.sampledAt.toISOString() ?? null,
      };
    });
  }

  /**
   * Обновляет состояние алерта по одному измерению и говорит, слать ли письмо.
   *
   * Состояние (с какого момента держится превышение, когда в последний раз
   * писали) лежит в базе: крон может рестартовать в любой момент, а «держится
   * пять минут» обязано пережить перезапуск панели — иначе на нестабильной
   * машине алерт не уйдёт никогда.
   */
  async evaluate(
    serverId: string,
    reading: Reading,
    settings: AlertSettingsDto,
    now = new Date(),
  ): Promise<{ notify: boolean; percent: number }> {
    const threshold = thresholdFor(reading.type, settings);
    const state = await this.prisma.serverAlertState.findUnique({
      where: { serverId_type: { serverId, type: reading.type } },
    });

    const decision = decideAlert({
      percentOfLimit: reading.percentOfLimit,
      threshold,
      enabled: settings.enabled,
      sustainedMinutes: settings.sustainedMinutes,
      cooldownMinutes: settings.cooldownMinutes,
      breachingSince: state?.breachingSince ?? null,
      lastNotifiedAt: state?.lastNotifiedAt ?? null,
      now,
    });

    await this.prisma.serverAlertState.upsert({
      where: { serverId_type: { serverId, type: reading.type } },
      create: {
        serverId,
        type: reading.type,
        breachingSince: decision.breachingSince,
        lastNotifiedAt: decision.notify ? now : null,
        lastValue: reading.percentOfLimit,
      },
      update: {
        breachingSince: decision.breachingSince,
        ...(decision.notify ? { lastNotifiedAt: now } : {}),
        lastValue: reading.percentOfLimit,
      },
    });

    return { notify: decision.notify, percent: reading.percentOfLimit ?? 0 };
  }

  /** Сбрасывает отметки превышения — сервер выключен или недоступен. */
  async clearBreaches(serverId: string): Promise<void> {
    await this.prisma.serverAlertState.updateMany({
      where: { serverId, type: { in: [...ALERT_TYPES] } },
      data: { breachingSince: null },
    });
  }
}

function thresholdFor(type: AlertType, settings: AlertSettingsDto): number | null {
  return type === 'cpu' ? settings.cpuThresholdPercent : settings.memoryThresholdPercent;
}

/**
 * Решение об алерте — чистая функция.
 *
 * Вынесена отдельно и без зависимостей намеренно: это самая тонкая часть всей
 * фичи, и проверять её надо на голых числах, а не через базу и почту.
 *
 * Правила по порядку:
 *   1. Алерты выключены, порог не задан или сравнивать не с чем (нет лимита) —
 *      никакого превышения не существует, отметку сбрасываем.
 *   2. Ниже порога — отметку сбрасываем. Именно СБРАСЫВАЕМ: превышение должно
 *      держаться НЕПРЕРЫВНО, иначе «пять минут» набирались бы из отдельных
 *      секундных всплесков за целый день.
 *   3. Выше порога впервые — только запоминаем момент, письма ещё нет.
 *   4. Выше порога дольше sustainedMinutes — письмо, если кулдаун прошёл.
 */
export function decideAlert(input: {
  percentOfLimit: number | null;
  threshold: number | null;
  enabled: boolean;
  sustainedMinutes: number;
  cooldownMinutes: number;
  breachingSince: Date | null;
  lastNotifiedAt: Date | null;
  now: Date;
}): { notify: boolean; breachingSince: Date | null } {
  const { percentOfLimit, threshold, now } = input;

  if (!input.enabled || threshold === null || percentOfLimit === null) {
    return { notify: false, breachingSince: null };
  }
  if (percentOfLimit < threshold) {
    return { notify: false, breachingSince: null };
  }

  const since = input.breachingSince ?? now;
  const heldMs = now.getTime() - since.getTime();
  if (heldMs < input.sustainedMinutes * 60_000) {
    return { notify: false, breachingSince: since };
  }

  if (input.lastNotifiedAt) {
    const sinceLast = now.getTime() - input.lastNotifiedAt.getTime();
    if (sinceLast < input.cooldownMinutes * 60_000) {
      return { notify: false, breachingSince: since };
    }
  }

  return { notify: true, breachingSince: since };
}
