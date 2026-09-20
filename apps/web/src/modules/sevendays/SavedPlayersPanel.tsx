import { useEffect, useState } from 'react';
import type { SevenDaysSavedPlayers } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Input, Spinner } from '../../components/ui';
import { useI18n } from '../../i18n';

export function SevenDaysSavedPlayersPanel({
  serverId,
  onInventory,
  onHistory,
}: {
  serverId: string;
  onInventory: (player: { id: string; name: string }) => void;
  onHistory?: (player: { id: string; name: string }) => void;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [request, setRequest] = useState({ query: '', offset: 0 });
  const [data, setData] = useState<SevenDaysSavedPlayers | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    setBusy(true);
    setError('');
    setData(null);
    void api<SevenDaysSavedPlayers>(
      `/api/modules/sevendays/servers/${serverId}/saved-players?q=${encodeURIComponent(request.query)}&offset=${request.offset}`,
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
  }, [serverId, open, request]);
  return (
    <Card>
      <Button
        variant="outline"
        aria-expanded={open}
        aria-controls="sdtd-saved-players"
        onClick={() => setOpen((value) => !value)}
      >
        {t('sdtd.inventory.savedPlayers')}
      </Button>
      {open && (
        <section
          id="sdtd-saved-players"
          className="mt-4 space-y-4"
          aria-label={t('sdtd.inventory.savedPlayers')}
        >
          <p className="max-w-prose text-sm text-muted">{t('sdtd.inventory.savedListNote')}</p>
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setRequest({ query: query.trim(), offset: 0 });
            }}
          >
            <label className="min-w-0 flex-1 space-y-1 text-sm">
              <span>{t('sdtd.inventory.savedSearch')}</span>
              <Input value={query} maxLength={80} onChange={(e) => setQuery(e.target.value)} />
            </label>
            <Button type="submit" variant="outline" disabled={busy}>
              {t('sdtd.inventory.savedFind')}
            </Button>
          </form>
          <div role="status" aria-live="polite">
            {busy && <Spinner />}
            {error && <ErrorText>{error}</ErrorText>}
            {data && !data.ready && (
              <p className="text-sm">{t(`sdtd.inventory.${data.reason ?? 'save_unavailable'}`)}</p>
            )}
            {data?.ready && !data.players.length && (
              <p className="text-sm text-muted">{t('sdtd.inventory.savedEmpty')}</p>
            )}
            {data?.truncated && (
              <p className="text-sm text-muted">{t('sdtd.inventory.savedTruncated')}</p>
            )}
          </div>
          {!!data?.players.length && (
            <ul className="divide-y divide-border">
              {data.players.map((player) => (
                <li
                  key={player.id}
                  className="flex flex-wrap items-center justify-between gap-3 py-3"
                >
                  <div className="min-w-0 flex-1">
                    <p className="break-all font-medium">{player.name}</p>
                    <p className="break-all font-mono text-xs text-muted">{player.id}</p>
                  </div>
                  <Button size="sm" variant="outline" onClick={() => onInventory(player)}>
                    {t('sdtd.inventory.savedOpen')}
                  </Button>
                  {onHistory && (
                    <Button size="sm" variant="outline" onClick={() => onHistory(player)}>
                      {t('sdtd.history.open')}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {(request.offset > 0 || data?.hasMore) && (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                disabled={busy || request.offset === 0}
                onClick={() => setRequest((r) => ({ ...r, offset: Math.max(0, r.offset - 50) }))}
              >
                {t('sdtd.inventory.savedPrevious')}
              </Button>
              <Button
                variant="outline"
                disabled={busy || !data?.hasMore}
                onClick={() => setRequest((r) => ({ ...r, offset: r.offset + 50 }))}
              >
                {t('sdtd.inventory.savedNext')}
              </Button>
            </div>
          )}
        </section>
      )}
    </Card>
  );
}
