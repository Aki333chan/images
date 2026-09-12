import { useEffect, useState } from 'react';
import {
  cpuUsage,
  formatCpu,
  memoryUsage,
  resourceTone,
  type MinecraftPerformanceDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { GAME_POLL_MS, useServerRuntime } from '../lib/server-runtime';
import { Card } from './ui';
import { useI18n } from '../i18n';

function formatBytes(bytes: number, unit: (key: string) => string): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return `0 ${unit('size.b')}`;
  const units = [unit('size.b'), unit('size.kb'), unit('size.mb'), unit('size.gb'), unit('size.tb')];
  const power = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / 1024 ** power;
  return `${value >= 100 || power === 0 ? Math.round(value) : value.toFixed(1)} ${units[power]}`;
}

/** Аптайм: 3 д 4 ч, 12 м — точность до секунд тут не нужна. */
export function formatUptime(ms: number, t: (key: string, values?: Record<string, number>) => string): string {
  if (!Number.isFinite(ms) || ms <= 0) return '—';
  const totalMinutes = Math.floor(ms / 60000);
  const days = Math.floor(totalMinutes / (60 * 24));
  const hours = Math.floor((totalMinutes % (60 * 24)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) return t('stats.uptime.days', { days, hours });
  if (hours > 0) return t('stats.uptime.hours', { hours, minutes });
  return t('stats.uptime.minutes', { minutes });
}

function Metric({
  label,
  value,
  hint,
  tone = 'normal',
}: {
  label: string;
  value: string;
  hint?: string;
  /**
   * 'unknown' — сравнивать не с чем (у сервера нет лимита), и красить
   * нечем: показываем обычным цветом, как любое информационное число.
   */
  tone?: 'normal' | 'warn' | 'bad' | 'unknown';
}) {
  const color =
    tone === 'bad' ? 'text-red-400' : tone === 'warn' ? 'text-amber-400' : 'text-neutral-100';
  return (
    <div className="min-w-[92px]">
      <div className="text-[11px] uppercase tracking-wide text-muted">{label}</div>
      <div className={`text-sm font-semibold ${color}`}>{value}</div>
      {hint && <div className="text-[11px] text-muted">{hint}</div>}
    </div>
  );
}

/**
 * Полоса метрик над вкладками: ресурсы из Pterodactyl плюс, если сервер
 * под модулем Minecraft, TPS и время тика по RCON.
 *
 * Обновляется раз в 10 секунд. Чаще нет смысла: Pterodactyl сам снимает
 * показания с интервалом, а RCON-команда — это лишний поход на игровой
 * сервер, который в это время занят игроками.
 *
 * Ресурсы берутся из общего опроса (см. lib/server-runtime): то же состояние
 * нужно шапке страницы и списку плагинов, и спрашивать его трижды незачем.
 *
 * Экономика здесь больше не смешивается с ресурсами хоста: её нативный
 * snapshot и доска богатства находятся в модульной вкладке AurumCore.
 */
export function ServerStats({
  serverId,
  moduleId,
  canSeePerformance,
}: {
  serverId: string;
  moduleId: string | null;
  canSeePerformance: boolean;
}) {
  const { t } = useI18n();
  const { resources, failed } = useServerRuntime(serverId);
  const [performance, setPerformance] = useState<MinecraftPerformanceDto | null>(null);

  const wantsPerformance = moduleId === 'minecraft' && canSeePerformance;

  useEffect(() => {
    if (!wantsPerformance) {
      setPerformance(null);
      return;
    }
    let stopped = false;

    async function tick() {
      // На скрытой вкладке не ходим: RCON-команда — это поход на живой
      // сервер, и делать его для страницы, на которую никто не смотрит,
      // незачем.
      if (document.hidden) return;
      try {
        const perf = await api<MinecraftPerformanceDto>(
          `/api/modules/minecraft/servers/${serverId}/performance`,
        );
        if (!stopped) setPerformance(perf);
      } catch {
        // RCON может быть не настроен — это не повод гасить всю полосу.
        if (!stopped) setPerformance(null);
      }
    }

    void tick();
    const timer = setInterval(() => void tick(), GAME_POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [serverId, wantsPerformance]);

  if (failed && !resources) {
    return (
      <Card className="text-xs text-muted">
        {t('stats.unavailable')}
      </Card>
    );
  }
  if (!resources) return null;

  const diskHint =
    resources.diskLimitBytes > 0
      ? t('stats.of', { value: formatBytes(resources.diskLimitBytes, t) })
      : t('stats.noLimit');

  /**
   * ЦПУ считается ОТ ЛИМИТА СЕРВЕРА, а не от абстрактных 100%.
   *
   * У Pterodactyl лимит задаётся в процентах от одного ядра: 200 — два ядра.
   * Значение потребления приходит в тех же единицах, поэтому «150%» — это
   * перегрузка на сервере с одним ядром и половина выделенного на сервере с
   * тремя. Раньше здесь стояло сравнение с 90 и 70, и панель красила в
   * красный совершенно здоровый сервер.
   *
   * Крупным показываем долю от лимита — она отвечает на вопрос «всё ли в
   * порядке». Абсолютные цифры идут подсказкой: они отвечают на другой
   * вопрос — «сколько это в ядрах», — и без них доля повисает в воздухе.
   */
  const cpu = cpuUsage(resources.cpuPercent, resources.cpuLimitPercent);
  const memory = memoryUsage(resources.memoryBytes, resources.memoryLimitBytes);

  return (
    <Card className="flex flex-wrap items-start gap-x-6 gap-y-3">
      <Metric
        label={t('servers.cpu')}
        value={
          cpu.unlimited ? `${cpu.absolutePercent.toFixed(1)} %` : `${Math.round(cpu.percentOfLimit ?? 0)} %`
        }
        hint={formatCpu(cpu, t)}
        tone={resourceTone(cpu.percentOfLimit)}
      />
      <Metric
        label={t('servers.memory')}
        value={formatBytes(resources.memoryBytes, t)}
        hint={
          memory.unlimited ? t('stats.noLimit') : t('stats.of', { value: formatBytes(resources.memoryLimitBytes, t) })
        }
        tone={resourceTone(memory.percentOfLimit)}
      />
      <Metric label={t('stats.disk')} value={formatBytes(resources.diskBytes, t)} hint={diskHint} />
      <Metric
        label={t('stats.network')}
        value={`↓ ${formatBytes(resources.networkRxBytes, t)}`}
        hint={`↑ ${formatBytes(resources.networkTxBytes, t)}`}
      />
      <Metric label={t('stats.uptime')} value={formatUptime(resources.uptimeMs, t)} hint={resources.state} />

      {wantsPerformance && performance && (
        <>
          <Metric
            label="TPS"
            value={
              performance.tpsSupported && performance.tps1m !== null
                ? performance.tps1m.toFixed(2)
                : '—'
            }
            hint={
              !performance.tpsSupported
                ? t('stats.tps.needsPaper')
                : performance.tps5m !== null && performance.tps15m !== null
                  ? t('stats.tps.detail', { tps5: performance.tps5m.toFixed(1), tps15: performance.tps15m.toFixed(1) })
                  : undefined
            }
            // Ниже 18 — заметно на глаз, ниже 15 — сервер ощутимо тормозит.
            tone={
              performance.tps1m === null
                ? 'normal'
                : performance.tps1m < 15
                  ? 'bad'
                  : performance.tps1m < 18
                    ? 'warn'
                    : 'normal'
            }
          />
          <Metric
            label="MSPT"
            value={
              performance.msptSupported && performance.mspt !== null
                ? t('stats.mspt.value', { value: performance.mspt.toFixed(1) })
                : '—'
            }
            // Тик длится 50 мс: если обработка дольше, TPS начинает падать.
            hint={t(performance.msptSupported ? 'stats.mspt.budget' : 'stats.mspt.needsPaper')}
            tone={
              performance.mspt === null
                ? 'normal'
                : performance.mspt > 50
                  ? 'bad'
                  : performance.mspt > 35
                    ? 'warn'
                    : 'normal'
            }
          />
        </>
      )}

    </Card>
  );
}

