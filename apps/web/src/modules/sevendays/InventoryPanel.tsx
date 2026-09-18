import { useEffect, useRef, useState } from 'react';
import type { SevenDaysInventory } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Spinner } from '../../components/ui';
import { useI18n } from '../../i18n';

export function SevenDaysInventoryPanel({
  serverId,
  playerId,
  name,
  onClose,
}: {
  serverId: string;
  playerId: string;
  name: string;
  onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const [data, setData] = useState<SevenDaysInventory | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);
  const [revision, setRevision] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, [serverId, playerId]);
  useEffect(() => {
    const abort = new AbortController();
    setBusy(true);
    setError('');
    setData(null);
    void api<SevenDaysInventory>(
      `/api/modules/sevendays/servers/${serverId}/players/${encodeURIComponent(playerId)}/inventory`,
      { signal: abort.signal },
    )
      .then((result) => {
        if (!abort.signal.aborted) setData(result);
      })
      .catch((e: Error) => {
        if (!abort.signal.aborted) setError(e.message);
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [serverId, playerId, revision]);
  return (
    <Card>
      <section id="sdtd-inventory" aria-labelledby="sdtd-inventory-title" className="space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h3
              id="sdtd-inventory-title"
              ref={heading}
              tabIndex={-1}
              className="break-all font-semibold"
            >
              {t('sdtd.inventory.title')} · {name}
            </h3>
            <p className="break-all font-mono text-xs text-muted">{playerId}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                setRevision((r) => r + 1);
              }}
            >
              {t('sdtd.inventory.refresh')}
            </Button>
            <Button size="sm" variant="ghost" onClick={onClose}>
              {t('common.close')}
            </Button>
          </div>
        </div>
        <p className="max-w-prose text-sm text-muted">{t('sdtd.inventory.note')}</p>
        <div role="status" aria-live="polite">
          {busy && <Spinner />}
          {error && <ErrorText>{error}</ErrorText>}
          {!busy && data && !data.available && (
            <p className="text-sm">{t(`sdtd.inventory.${data.reason ?? 'snapshot_pending'}`)}</p>
          )}
          {data?.available && data.fetchedAt && (
            <p className="text-xs text-muted">
              {t('sdtd.inventory.fetched')} {new Date(data.fetchedAt).toLocaleString(locale)}
            </p>
          )}
        </div>
        {data?.available && (
          <>
            {data.truncated && (
              <p className="text-sm text-amber-400">{t('sdtd.inventory.truncated')}</p>
            )}
            {(['belt', 'bag', 'equipment', 'cursor'] as const).map((section) => {
              const items = data.items.filter((p) => p.section === section);
              return (
                <section
                  key={section}
                  className="space-y-2"
                  aria-label={t(`sdtd.inventory.${section}`)}
                >
                  <h4 className="font-medium">{t(`sdtd.inventory.${section}`)}</h4>
                  {!items.length ? (
                    <p className="text-sm text-muted">{t('sdtd.inventory.empty')}</p>
                  ) : (
                    <table className="w-full table-fixed text-sm">
                      <thead className="text-left text-muted">
                        <tr>
                          <th className="w-12 pb-2">{t('sdtd.inventory.slot')}</th>
                          <th className="pb-2">{t('sdtd.inventory.item')}</th>
                          <th className="w-28 pb-2 pl-2 text-right">{t('sdtd.inventory.count')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {items.map((p) => (
                          <tr key={p.slot} className="border-t border-border align-top">
                            <td className="py-2 tabular-nums">{p.slot + 1}</td>
                            <td className="break-all py-2 pr-2">
                              {p.name}
                              <span className="block text-xs text-muted">
                                ID {p.itemId}
                                {p.quality > 0 && ` · ${t('sdtd.inventory.quality')} ${p.quality}`}
                              </span>
                            </td>
                            <td className="whitespace-nowrap py-2 pl-2 text-right tabular-nums">
                              {p.count.toLocaleString(locale)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </section>
              );
            })}
          </>
        )}
      </section>
    </Card>
  );
}
