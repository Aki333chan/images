import { useCallback, useEffect, useState } from 'react';
import {
  cpuUsage,
  formatCpu,
  memoryUsage,
  resourceTone,
  LOCALE_TAGS,
  type Locale,
  type MinecraftEconomyDto,
  type MinecraftPerformanceDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { RUNTIME_POLL_MS, useServerRuntime } from '../lib/server-runtime';
import { Button, Card } from './ui';
import { useI18n } from '../i18n';

/** Байты в человекочитаемый вид: 1.5 ГБ вместо 1610612736. */
export /** Переводчик аргументом: функции вне компонента, хук туда не занести. */
/** Время без даты: «посчитано в 14:32» — дата там лишняя. */
function makeTimeFormatter(locale: Locale) {
  const format = new Intl.DateTimeFormat(LOCALE_TAGS[locale], { hour: '2-digit', minute: '2-digit' });
  return (value: string) => format.format(new Date(value));
}

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
 * Экономика из этого цикла намеренно исключена: её пересчёт обходит всех, кто
 * когда-либо заходил на сервер, поэтому цифра берётся один раз при открытии
 * (бэкенд отдаёт её из кэша) и дальше — только по кнопке «обновить».
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
  const { t, locale } = useI18n();
  const formatTime = makeTimeFormatter(locale);
  const { hasPermission } = useAuth();
  const { resources, failed } = useServerRuntime(serverId);
  const [performance, setPerformance] = useState<MinecraftPerformanceDto | null>(null);
  const [economy, setEconomy] = useState<MinecraftEconomyDto | null>(null);
  const [economyBusy, setEconomyBusy] = useState(false);
  const [showRich, setShowRich] = useState(false);

  const wantsPerformance = moduleId === 'minecraft' && canSeePerformance;
  const wantsEconomy = moduleId === 'minecraft' && hasPermission('minecraft.economy.view');

  const loadEconomy = useCallback(
    async (refresh: boolean) => {
      setEconomyBusy(true);
      try {
        setEconomy(
          await api<MinecraftEconomyDto>(
            `/api/modules/minecraft/servers/${serverId}/economy${refresh ? '?refresh=1' : ''}`,
          ),
        );
      } catch {
        // Нет Vault, нет плагина, сервер выключен — полосу метрик это гасить
        // не должно.
        setEconomy(null);
      } finally {
        setEconomyBusy(false);
      }
    },
    [serverId],
  );

  useEffect(() => {
    if (!wantsEconomy) {
      setEconomy(null);
      return;
    }
    void loadEconomy(false);
  }, [wantsEconomy, loadEconomy]);

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
    const timer = setInterval(() => void tick(), RUNTIME_POLL_MS);
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

      {wantsEconomy && economy?.available && (
        <Metric
          label={t('stats.economy')}
          value={economy.totalFormatted ?? String(economy.total ?? 0)}
          hint={t('stats.players', { count: economy.playersCounted ?? 0 })}
        />
      )}

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

      {wantsEconomy && economy?.available && (
        // Отдельной строкой во всю ширину: кнопка обновления и доска
        // богатства не помещаются в полосу метрик и ломали бы её ритм.
        <div className="w-full border-t border-border pt-3">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
            <span>
              {economy.calculatedAt
                ? t('stats.economy.countedAt', { time: formatTime(economy.calculatedAt) })
                : t('stats.economy.countedNow')}
              {economy.cached ? t('stats.economy.cached') : ''}
            </span>
            <Button size="sm" variant="ghost" disabled={economyBusy} onClick={() => void loadEconomy(true)}>
              {t('common.refresh')}
            </Button>
            {(economy.top?.length ?? 0) > 0 && (
              <Button size="sm" variant="ghost" onClick={() => setShowRich((v) => !v)}>
                {t(showRich ? 'stats.economy.hideRich' : 'stats.economy.showRich')}
              </Button>
            )}
          </div>

          {showRich && (economy.top?.length ?? 0) > 0 && (
            <ol className="mt-2 space-y-1">
              {economy.top!.map((entry, index) => (
                <li key={entry.uuid} className="flex items-baseline justify-between gap-3 text-xs">
                  <span className="min-w-0 truncate">
                    <span className="mr-2 text-muted">{index + 1}.</span>
                    {entry.name}
                  </span>
                  <span className="shrink-0 font-medium">{entry.formatted || entry.balance}</span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </Card>
  );
}

