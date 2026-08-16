import { Injectable, Logger } from '@nestjs/common';
import type { ServerActivityDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';

/** Начало часа UTC, к которому относится момент времени. */
export function hourBucket(at: Date): Date {
  const bucket = new Date(at);
  bucket.setUTCMinutes(0, 0, 0);
  return bucket;
}

/**
 * История онлайна: сколько игроков было на сервере в каждый час.
 *
 * Хранение устроено так, чтобы таблица не росла бесконечно: на сервер и час
 * приходится ровно одна строка, а повторные замеры внутри часа усредняются
 * на месте (скользящее среднее по числу замеров). При замере раз в 5 минут
 * это 24 строки в сутки на сервер — 8760 строк в год.
 */
@Injectable()
export class ActivityService {
  private readonly logger = new Logger(ActivityService.name);

  constructor(private readonly prisma: PrismaService) {}

  /**
   * Записать замер. Среднее пересчитывается инкрементально:
   *   avg' = avg + (x - avg) / (n + 1)
   * так не нужно хранить отдельные замеры внутри часа.
   */
  async record(serverId: string, online: number, at = new Date()): Promise<void> {
    const bucket = hourBucket(at);
    const existing = await this.prisma.serverActivitySample.findUnique({
      where: { serverId_bucket: { serverId, bucket } },
    });

    if (!existing) {
      await this.prisma.serverActivitySample.create({
        data: { serverId, bucket, avgOnline: online, maxOnline: online, samples: 1 },
      });
      return;
    }

    const samples = existing.samples + 1;
    await this.prisma.serverActivitySample.update({
      where: { serverId_bucket: { serverId, bucket } },
      data: {
        avgOnline: existing.avgOnline + (online - existing.avgOnline) / samples,
        maxOnline: Math.max(existing.maxOnline, online),
        samples,
      },
    });
  }

  /**
   * Замеры за последние `days` суток — плоским списком, во времени UTC.
   *
   * Раскладку по суткам и часам делает клиент: часовой пояс знает только он,
   * а сдвигать уже готовую сетку нельзя — по краям терялись бы часы, и
   * пропадали бы как раз самые свежие данные.
   *
   * Берём с запасом в сутки: у пояса вроде UTC+12 местные сутки начинаются
   * заметно раньше по UTC, и без запаса первая строка была бы неполной.
   */
  async history(serverId: string, days: number): Promise<ServerActivityDto> {
    const safeDays = Math.min(Math.max(Math.trunc(days) || 7, 1), 31);

    const from = hourBucket(new Date());
    from.setUTCHours(0, 0, 0, 0);
    from.setUTCDate(from.getUTCDate() - safeDays);

    const rows = await this.prisma.serverActivitySample.findMany({
      where: { serverId, bucket: { gte: from } },
      orderBy: { bucket: 'asc' },
      select: { bucket: true, maxOnline: true },
    });

    let peak = 0;
    const samples = rows.map((row) => {
      // Показываем пик за час, а не среднее: всплеск в 20 игроков за десять
      // минут интереснее, чем «в среднем трое».
      if (row.maxOnline > peak) peak = row.maxOnline;
      return { bucket: row.bucket.toISOString(), online: row.maxOnline };
    });

    return { days: safeDays, peak, samples };
  }

  /**
   * Удаление замеров старше `keepDays`. Вызывается сборщиком: график
   * показывает недавнее, а хранить историю вечно смысла нет.
   */
  async prune(keepDays = 60): Promise<number> {
    const cutoff = hourBucket(new Date());
    cutoff.setUTCDate(cutoff.getUTCDate() - keepDays);
    const { count } = await this.prisma.serverActivitySample.deleteMany({
      where: { bucket: { lt: cutoff } },
    });
    if (count > 0) this.logger.log(`Удалено старых замеров активности: ${count}`);
    return count;
  }
}
