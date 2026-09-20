import { useRef, useState } from 'react';
import type { SevenDaysInventoryItem } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Input } from '../../components/ui';
import { useI18n } from '../../i18n';

export function SevenDaysReduceStack({
  serverId,
  playerId,
  revision,
  item,
  onBusy,
}: {
  serverId: string;
  playerId: string;
  revision: string;
  item: SevenDaysInventoryItem;
  onBusy: (busy: boolean) => void;
}) {
  const { t } = useI18n();
  const [count, setCount] = useState(String(Math.max(0, item.count - 1)));
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState('');
  const sent = useRef(false);
  const valid =
    /^\d+$/.test(count) && Number(count) < item.count && Number.isSafeInteger(Number(count));
  return (
    <form
      className="mt-4 space-y-3"
      onSubmit={async (e) => {
        e.preventDefault();
        if (sent.current || !valid || !confirmed) return;
        sent.current = true;
        setBusy(true);
        onBusy(true);
        const requestId = crypto.randomUUID();
        try {
          const reply = await api<{ requestId: string; status: string }>(
            `/api/modules/sevendays/servers/${serverId}/players/${encodeURIComponent(playerId)}/saved-stack`,
            {
              method: 'POST',
              body: JSON.stringify({
                requestId,
                revision,
                section: item.section,
                slot: item.slot,
                count: Number(count),
                confirmed: true,
              }),
            },
          );
          const statuses = [
            'saved',
            'busy',
            'offline_required',
            'revision_conflict',
            'unsupported',
            'invalid_stack',
            'write_failed',
            'unknown',
          ];
          setResult(
            reply.requestId === requestId && statuses.includes(reply.status)
              ? reply.status
              : 'unknown',
          );
        } catch {
          setResult('unknown');
        } finally {
          setBusy(false);
          onBusy(false);
        }
      }}
    >
      <p className="max-w-prose text-sm text-muted">{t('sdtd.edit.note')}</p>
      <label className="block max-w-xs space-y-1">
        <span>{t('sdtd.edit.remaining')}</span>
        <Input
          type="number"
          min={0}
          max={item.count - 1}
          step={1}
          value={count}
          disabled={busy || !!result}
          onChange={(e) => {
            setCount(e.target.value);
            setConfirmed(false);
          }}
        />
      </label>
      <label className="flex items-start gap-2">
        <input
          type="checkbox"
          checked={confirmed}
          disabled={busy || !!result}
          onChange={(e) => setConfirmed(e.target.checked)}
        />
        <span>{t('sdtd.edit.confirm')}</span>
      </label>
      <Button type="submit" variant="outline" disabled={busy || !!result || !valid || !confirmed}>
        {t(Number(count) === 0 ? 'sdtd.edit.remove' : 'sdtd.edit.reduce')}
      </Button>
      <div role="status" aria-live="polite">
        {result && (
          <p>
            {t(`sdtd.edit.${result}`)} {t('sdtd.edit.refresh')}
          </p>
        )}
      </div>
    </form>
  );
}
