import { useEffect, useState } from 'react';
import type { MinecraftPerformanceDto, ServerResourcesDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Card } from './ui';

/** Байты в человекочитаемый вид: 1.5 ГБ вместо 1610612736. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 Б';
  const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  const power = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / 1024 ** power;
  return `${value >= 100 || power === 0 ? Math.round(value) : value.toFixed(1)} ${units[power]}`;
}

/** Аптайм: 3 д 4 ч, 12 м — точность до секунд тут не нужна. */
export function formatUptime(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '—';
  const totalMinutes = Math.floor(ms / 60000);
  const days = Math.floor(totalMinutes / (60 * 24));
  const hours = Math.floor((totalMinutes % (60 * 24)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) return `${days} д ${hours} ч`;
  if (hours > 0) return `${hours} ч ${minutes} м`;
  return `${minutes} м`;
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
  tone?: 'normal' | 'warn' | 'bad';
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
  const [resources, setResources] = useState<ServerResourcesDto | null>(null);
  const [performance, setPerformance] = useState<MinecraftPerformanceDto | null>(null);
  const [failed, setFailed] = useState(false);

  const wantsPerformance = moduleId === 'minecraft' && canSeePerformance;

  useEffect(() => {
    let stopped = false;

    async function tick() {
      try {
        const res = await api<ServerResourcesDto>(`/api/servers/${serverId}/resources`);
        if (!stopped) {
          setResources(res);
          setFailed(false);
        }
      } catch {
        if (!stopped) setFailed(true);
      }

      if (!wantsPerformance) return;
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
    const timer = setInterval(() => void tick(), 10_000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [serverId, wantsPerformance]);

  if (failed && !resources) {
    return (
      <Card className="text-xs text-muted">
        Статистика недоступна: Pterodactyl не отвечает или у служебного пользователя нет доступа к
        этому серверу.
      </Card>
    );
  }
  if (!resources) return null;

  const memoryHint =
    resources.memoryLimitBytes > 0 ? `из ${formatBytes(resources.memoryLimitBytes)}` : 'без лимита';
  const diskHint =
    resources.diskLimitBytes > 0 ? `из ${formatBytes(resources.diskLimitBytes)}` : 'без лимита';
  const memoryRatio =
    resources.memoryLimitBytes > 0 ? resources.memoryBytes / resources.memoryLimitBytes : 0;

  return (
    <Card className="flex flex-wrap items-start gap-x-6 gap-y-3">
      <Metric
        label="ЦПУ"
        value={`${resources.cpuPercent.toFixed(1)} %`}
        tone={resources.cpuPercent > 90 ? 'bad' : resources.cpuPercent > 70 ? 'warn' : 'normal'}
      />
      <Metric
        label="Память"
        value={formatBytes(resources.memoryBytes)}
        hint={memoryHint}
        tone={memoryRatio > 0.9 ? 'bad' : memoryRatio > 0.75 ? 'warn' : 'normal'}
      />
      <Metric label="Диск" value={formatBytes(resources.diskBytes)} hint={diskHint} />
      <Metric
        label="Сеть"
        value={`↓ ${formatBytes(resources.networkRxBytes)}`}
        hint={`↑ ${formatBytes(resources.networkTxBytes)}`}
      />
      <Metric label="Аптайм" value={formatUptime(resources.uptimeMs)} hint={resources.state} />

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
                ? 'нужен Paper/Spigot'
                : performance.tps5m !== null && performance.tps15m !== null
                  ? `5м ${performance.tps5m.toFixed(1)} · 15м ${performance.tps15m.toFixed(1)}`
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
                ? `${performance.mspt.toFixed(1)} мс`
                : '—'
            }
            // Тик длится 50 мс: если обработка дольше, TPS начинает падать.
            hint={!performance.msptSupported ? 'нужен Paper' : 'бюджет тика 50 мс'}
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
