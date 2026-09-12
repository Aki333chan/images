import { useCallback, useEffect, useState } from 'react';
import { LOCALE_TAGS, type MinecraftEconomyDto } from '@aurum/shared';
import { Button, Card, ErrorText, Spinner } from '../../components/ui';
import { api } from '../../lib/api';
import { useApiText, useI18n } from '../../i18n';

function EconomyMetric({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="min-w-0 rounded-md border border-border bg-neutral-950/40 px-3 py-2">
      <div className="text-[11px] uppercase tracking-wide text-muted">{label}</div>
      <div className="truncate text-base font-semibold text-neutral-100">{value}</div>
      <div className="text-[11px] text-muted">{hint}</div>
    </div>
  );
}

/**
 * Лёгкий обзор active ledger. Один snapshot Core содержит денежную массу,
 * казну, налоги и ограниченный top; компонент не опрашивает сервер по таймеру.
 */
export function EconomyOverviewPanel({ serverId }: { serverId: string }) {
  const { t, locale } = useI18n();
  const apiText = useApiText();
  const [economy, setEconomy] = useState<MinecraftEconomyDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [showRich, setShowRich] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async (refresh: boolean) => {
    setBusy(true);
    setError('');
    try {
      const suffix = refresh ? '?refresh=1' : '';
      setEconomy(await api<MinecraftEconomyDto>(
        `/api/modules/minecraft/servers/${serverId}/economy${suffix}`,
      ));
    } catch (e) {
      setEconomy(null);
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }, [serverId]);

  useEffect(() => {
    void load(false);
  }, [load]);

  const unavailable = economy && !economy.available;
  const countedAt = economy?.calculatedAt
    ? new Intl.DateTimeFormat(LOCALE_TAGS[locale], { hour: '2-digit', minute: '2-digit' })
        .format(new Date(economy.calculatedAt))
    : null;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-semibold">{t('stats.economy')}</h3>
          <p className="text-xs text-muted">{t('stats.economy.supply')}</p>
        </div>
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => void load(true)}>
          {t('common.refresh')}
        </Button>
      </div>

      {!economy && !error ? <Spinner /> : null}
      {unavailable ? <p className="text-sm text-muted">{apiText(economy.reason)}</p> : null}

      {economy?.available ? (
        <>
          <div className="grid gap-2 sm:grid-cols-3">
            <EconomyMetric
              label={t('stats.economy')}
              value={economy.moneySupplyFormatted ?? economy.totalFormatted ?? String(economy.total ?? 0)}
              hint={t('stats.economy.supply')}
            />
            <EconomyMetric
              label={t('stats.economy.treasury')}
              value={economy.treasuryFormatted ?? String(economy.treasury ?? 0)}
              hint={t('stats.economy.treasuryHint')}
            />
            <EconomyMetric
              label={t('stats.economy.taxes')}
              value={economy.taxesFormatted ?? String(economy.taxesCollected ?? 0)}
              hint={t('stats.economy.taxesHint')}
            />
          </div>

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-border pt-3 text-[11px] text-muted">
            <span>
              {countedAt
                ? t('stats.economy.countedAt', { time: countedAt })
                : t('stats.economy.countedNow')}
              {economy.cached ? t('stats.economy.cached') : ''}
            </span>
            {(economy.top?.length ?? 0) > 0 ? (
              <Button size="sm" variant="ghost" onClick={() => setShowRich((value) => !value)}>
                {t(showRich ? 'stats.economy.hideRich' : 'stats.economy.showRich')}
              </Button>
            ) : null}
          </div>

          {showRich && (economy.top?.length ?? 0) > 0 ? (
            <ol className="space-y-1">
              {economy.top!.map((entry, index) => (
                <li key={entry.uuid} className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate">
                    <span className="mr-2 text-muted">{index + 1}.</span>
                    {entry.name}
                  </span>
                  <span className="shrink-0 font-medium">{entry.formatted || entry.balance}</span>
                </li>
              ))}
            </ol>
          ) : null}
        </>
      ) : null}

      {error ? <ErrorText>{error}</ErrorText> : null}
    </Card>
  );
}
