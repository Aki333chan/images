import { InjectQueue, Processor, WorkerHost } from '@nestjs/bullmq';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Queue } from 'bullmq';
import {
  ALERT_TYPE_KEYS,
  DEFAULT_LOCALE,
  cpuUsage,
  formatBytesUsage,
  formatCpu,
  memoryUsage,
  type AlertSettingsDto,
} from '@aurum/shared';
import { env } from '../../config/env';
import { AuditService } from '../../audit/audit.service';
import { MailService } from '../../mail/mail.service';
import { PrismaService } from '../../prisma/prisma.service';
import { SettingsService } from '../../settings/settings.service';
import { I18nService } from '../../i18n/i18n.service';
import { alertMail } from './alert-mail';
import { ServerMetricsService, type Reading } from './server-metrics.service';

export const METRICS_QUEUE = 'server-metrics';

/**
 * Как часто опрашиваем Pterodactyl.
 *
 * Полминуты — компромисс: карточки в списке не должны показывать позавчерашние
 * цифры, но и долбить Pterodactyl по десяткам серверов каждые пять секунд
 * незачем. Порог «держится 5 минут» при таком шаге проверяется десятком
 * замеров — этого с запасом.
 */
const SAMPLE_EVERY_MS = 30_000;

@Injectable()
export class ServerMetricsScheduler implements OnModuleInit {
  constructor(@InjectQueue(METRICS_QUEUE) private readonly queue: Queue) {}

  async onModuleInit() {
    await this.queue.upsertJobScheduler('server-metrics-sample', { every: SAMPLE_EVERY_MS });
  }
}

/**
 * Сбор нагрузки по всем серверам и рассылка алертов о перегрузке.
 *
 * ПОЧЕМУ ОДИН КРОН, А НЕ ДВА. Алерту нужны ровно те же цифры, что и списку
 * серверов. Разделив, мы опрашивали бы Pterodactyl дважды и получали бы два
 * немного разных ответа: в списке одно, в письме другое — и поди объясни,
 * почему письмо про 95%, когда на экране 88%.
 */
@Processor(METRICS_QUEUE)
export class ServerMetricsProcessor extends WorkerHost {
  private readonly logger = new Logger(ServerMetricsProcessor.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly metrics: ServerMetricsService,
    private readonly settings: SettingsService,
    private readonly mail: MailService,
    private readonly audit: AuditService,
    private readonly i18n: I18nService,
  ) {
    super();
  }

  async process(): Promise<void> {
    const servers = await this.prisma.server.findMany({
      where: { status: 'active' },
      select: {
        id: true,
        name: true,
        pteroIdentifier: true,
        moduleId: true,
        memoryLimitMb: true,
        cpuLimitPercent: true,
      },
    });
    if (servers.length === 0) return;

    const alertSettings = await this.settings.getAlertSettings();

    for (const server of servers) {
      try {
        const readings = await this.metrics.sample(server);
        if (readings === null) {
          // Сервер не ответил. Отметку превышения сбрасываем: «недоступен» —
          // это другая проблема, и держать по ней счётчик перегрузки нельзя,
          // иначе после возвращения сервера письмо уйдёт мгновенно за то
          // время, пока он лежал.
          await this.metrics.clearBreaches(server.id);
          continue;
        }
        if (!alertSettings.enabled) continue;
        for (const reading of readings) {
          await this.check(server, reading, alertSettings);
        }
      } catch (e) {
        this.logger.warn(`Замер «${server.name}» не удался: ${(e as Error).message}`);
      }
    }
  }

  private async check(
    server: {
      id: string;
      name: string;
      memoryLimitMb: number | null;
      cpuLimitPercent: number | null;
    },
    reading: Reading,
    settings: AlertSettingsDto,
  ): Promise<void> {
    const now = new Date();
    const before = await this.prisma.serverAlertState.findUnique({
      where: { serverId_type: { serverId: server.id, type: reading.type } },
    });
    const { notify, percent } = await this.metrics.evaluate(server.id, reading, settings, now);
    if (!notify) return;

    const heldMinutes = before?.breachingSince
      ? Math.round((now.getTime() - before.breachingSince.getTime()) / 60_000)
      : settings.sustainedMinutes;

    const recipients = await this.recipients(server.id);
    if (recipients.length === 0) {
      this.logger.warn(
        `Перегрузка «${server.name}» (${reading.type}), но получателей нет: ` +
          'ни у кого нет доступа к этому серверу',
      );
      return;
    }

    const sample = await this.prisma.serverMetricSample.findUnique({
      where: { serverId: server.id },
    });
    const absolute =
      reading.type === 'cpu'
        ? formatCpu(cpuUsage(sample?.cpuAbsolute ?? 0, server.cpuLimitPercent))
        : formatBytesUsage(
            Number(sample?.memoryBytes ?? 0),
            memoryUsage(0, (server.memoryLimitMb ?? 0) * 1024 * 1024).limitBytes,
          );

    // Пока — язык панели по умолчанию: письма по языку получателя это
    // отдельный шаг, там же и остальной текст шаблона.
    const label = this.i18n.t(DEFAULT_LOCALE, ALERT_TYPE_KEYS[reading.type]);

    const message = alertMail({
      serverName: server.name,
      type: reading.type,
      label,
      percentOfLimit: percent,
      thresholdPercent:
        (reading.type === 'cpu' ? settings.cpuThresholdPercent : settings.memoryThresholdPercent) ??
        0,
      heldMinutes,
      absolute,
      panelUrl: env.PANEL_URL,
      serverId: server.id,
      cooldownMinutes: settings.cooldownMinutes,
    });

    let sent = 0;
    for (const email of recipients) {
      const result = await this.mail.sendTo(email, message, 'алерт о перегрузке');
      if (result.sent) sent++;
    }

    // В аудит — факт и адресаты по количеству. Алерт это то, о чём потом
    // спрашивают «а почему мне не пришло», и запись должна на это отвечать.
    await this.audit.log({
      actorId: null,
      action: 'server.alert.sent',
      targetType: 'server',
      targetId: server.id,
      metadata: {
        type: reading.type,
        percentOfLimit: Math.round(percent),
        heldMinutes,
        recipients: recipients.length,
        sent,
      },
    });
    this.logger.log(
      `Алерт «${label}» по серверу «${server.name}»: ` +
        `${Math.round(percent)}%, отправлено ${sent} из ${recipients.length}`,
    );
  }

  /**
   * Кому уходит письмо: тем, у кого есть доступ ИМЕННО К ЭТОМУ серверу.
   *
   * ГМ (OWNER) попадает сюда автоматически и отдельной строкой не прописан —
   * у него доступ ко всем серверам по определению роли, ровно та же логика,
   * что и в PermissionsService.allowedServerIds. Прописывать его отдельно
   * значило бы завести второе место, где решается, кто что видит.
   *
   * Заблокированные и неактивные сотрудники исключены: письмо человеку,
   * которого только что отключили от панели, — это утечка.
   */
  private async recipients(serverId: string): Promise<string[]> {
    const users = await this.prisma.user.findMany({
      where: {
        isActive: true,
        status: 'active',
        OR: [{ role: 'OWNER' }, { serverAccess: { some: { serverId } } }],
      },
      select: { email: true },
      orderBy: { email: 'asc' },
    });
    return users.map((u) => u.email);
  }
}
