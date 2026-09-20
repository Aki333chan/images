import { useEffect, useRef, useState } from 'react';
import type { SevenDaysInventory, SevenDaysInventoryItem } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Spinner } from '../../components/ui';
import { useI18n } from '../../i18n';
import { IconArchive, IconClose } from '../../components/icons';
import { useAuth } from '../../lib/auth';
import { SevenDaysGiveItemPanel } from './GiveItemPanel';
import { SevenDaysReduceStack } from './ReduceStack';

export function SevenDaysInventoryPanel({
  serverId,
  playerId,
  name,
  saved = false,
  onClose,
}: {
  serverId: string;
  playerId: string;
  name: string;
  saved?: boolean;
  onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const [data, setData] = useState<SevenDaysInventory | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);
  const [editing, setEditing] = useState(false);
  const [revision, setRevision] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const selectedButton = useRef<HTMLButtonElement | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const canReplace =
    saved && !!data?.canReplace && !!data?.revision && hasPermission('sevendays.inventory.edit');
  const selectedItem =
    data?.items.find((p) => `${p.section}:${p.slot}` === selected) ??
    (canReplace && selected
      ? {
          section: selected.split(':')[0] as SevenDaysInventoryItem['section'],
          slot: Number(selected.split(':')[1]),
          itemId: 0,
          name: t('sdtd.inventory.emptySlot'),
          count: 0,
          quality: 0,
        }
      : undefined);
  useEffect(() => {
    if (selected) detailHeading.current?.focus();
  }, [selected]);
  useEffect(() => {
    heading.current?.focus();
  }, [serverId, playerId, saved]);
  useEffect(() => {
    const abort = new AbortController();
    setBusy(true);
    setError('');
    setData(null);
    setSelected(null);
    void api<SevenDaysInventory>(
      `/api/modules/sevendays/servers/${serverId}/players/${encodeURIComponent(playerId)}/${saved ? 'saved-inventory' : 'inventory'}`,
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
  }, [serverId, playerId, saved, revision]);
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
              disabled={busy || editing}
              onClick={() => {
                setBusy(true);
                setRevision((r) => r + 1);
              }}
            >
              {t('sdtd.inventory.refresh')}
            </Button>
            <Button size="sm" variant="ghost" disabled={editing} onClick={onClose}>
              {t('common.close')}
            </Button>
          </div>
        </div>
        <p className="max-w-prose text-sm text-muted">
          {t(saved ? 'sdtd.inventory.savedNote' : 'sdtd.inventory.note')}
        </p>
        {!saved && hasPermission('sevendays.inventory.give') && (
          <SevenDaysGiveItemPanel
            key={`${serverId}:${playerId}`}
            serverId={serverId}
            playerId={playerId}
            name={name}
          />
        )}
        <div role="status" aria-live="polite">
          {data?.available && data.savedAt && (
            <p className="text-sm">
              {t('sdtd.inventory.savedAt')} {new Date(data.savedAt).toLocaleString(locale)}
            </p>
          )}
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
            <p className="text-sm text-muted">{t('sdtd.inventory.choose')}</p>
            {!data.slotCounts && (
              <p className="text-sm text-muted">{t('sdtd.inventory.unknownCapacity')}</p>
            )}
            {data.truncated && (
              <p className="text-sm text-amber-400">{t('sdtd.inventory.truncated')}</p>
            )}
            {(['belt', 'bag', 'equipment', 'cursor'] as const).map((section) => {
              const items = data.items.filter((p) => p.section === section);
              const bySlot = new Map(items.map((p) => [p.slot, p]));
              const slots = data.slotCounts
                ? Array.from({ length: data.slotCounts[section] }, (_, i) => i)
                : items.map((p) => p.slot).sort((a, b) => a - b);
              return (
                <section
                  key={section}
                  className="space-y-2"
                  aria-label={t(`sdtd.inventory.${section}`)}
                >
                  <h4 className="font-medium">{t(`sdtd.inventory.${section}`)}</h4>
                  {selectedItem?.section === section && (
                    <div
                      className="relative border-l-2 border-primary bg-background p-3 pr-12 text-sm"
                      data-inventory-details
                    >
                      <h5 ref={detailHeading} tabIndex={-1} className="break-all font-semibold">
                        {selectedItem.name}
                      </h5>
                      <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-2">
                        {[
                          ...(selectedItem.itemId ? [['ID', selectedItem.itemId]] : []),
                          [t('sdtd.inventory.slot'), selectedItem.slot + 1],
                          [t('sdtd.inventory.count'), selectedItem.count.toLocaleString(locale)],
                          [t('sdtd.inventory.quality'), selectedItem.quality],
                        ].map(([label, value]) => (
                          <div key={label}>
                            <dt className="text-xs text-muted">{label}</dt>
                            <dd className="break-all tabular-nums">{value}</dd>
                          </div>
                        ))}
                      </dl>
                      {saved &&
                        data.revision &&
                        selectedItem.count > 0 &&
                        hasPermission('sevendays.inventory.edit') &&
                        (selectedItem.section === 'belt' || selectedItem.section === 'bag') && (
                          <fieldset disabled={editing}>
                            <SevenDaysReduceStack
                              key={`${data.revision}/${selected}`}
                              serverId={serverId}
                              playerId={playerId}
                              revision={data.revision}
                              item={selectedItem}
                              onBusy={setEditing}
                            />
                          </fieldset>
                        )}
                      {canReplace && (section === 'belt' || section === 'bag') && (
                        <fieldset disabled={editing}>
                          <SevenDaysGiveItemPanel
                            key={`replace/${data.revision}/${selected}`}
                            serverId={serverId}
                            playerId={playerId}
                            name={name}
                            savedTarget={{
                              revision: data.revision!,
                              section,
                              slot: selectedItem.slot,
                              occupied: selectedItem.count > 0,
                              onBusy: setEditing,
                            }}
                          />
                        </fieldset>
                      )}
                      <button
                        type="button"
                        disabled={editing}
                        aria-label={t('sdtd.inventory.closeDetails')}
                        className="absolute right-0 top-0 flex h-11 w-11 items-center justify-center rounded hover:bg-white/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
                        onClick={() => {
                          setSelected(null);
                          selectedButton.current?.focus();
                        }}
                      >
                        <IconClose size={16} />
                      </button>
                    </div>
                  )}
                  {!slots.length ? (
                    <p className="text-sm text-muted">{t('sdtd.inventory.empty')}</p>
                  ) : (
                    <div className="grid grid-cols-4 gap-2 sm:grid-cols-6 lg:grid-cols-8">
                      {slots.map((slot) => {
                        const p = bySlot.get(slot);
                        const key = `${section}:${slot}`;
                        return p ? (
                          <button
                            key={slot}
                            type="button"
                            disabled={editing}
                            data-inventory-slot={key}
                            aria-pressed={selected === key}
                            aria-label={`${t('sdtd.inventory.slot')} ${slot + 1}: ${p.name}, ${t('sdtd.inventory.count')} ${p.count}, ${t('sdtd.inventory.quality')} ${p.quality}`}
                            title={p.name}
                            className={`flex h-24 min-w-0 flex-col rounded border p-2 text-left hover:bg-white/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary ${selected === key ? 'border-primary bg-primary/10' : 'border-border bg-background'}`}
                            onClick={(e) => {
                              selectedButton.current = e.currentTarget;
                              setSelected(key);
                            }}
                          >
                            <span className="flex w-full items-center justify-between gap-1 text-xs text-muted">
                              <span>{slot + 1}</span>
                              <IconArchive size={18} />
                            </span>
                            <span className="mt-1 block w-full truncate text-xs">{p.name}</span>
                            <span className="mt-auto flex w-full flex-wrap justify-between gap-x-1 text-xs tabular-nums">
                              <span title={t('sdtd.inventory.quality')}>Q{p.quality}</span>
                              <span className="max-w-full truncate">
                                ×
                                {new Intl.NumberFormat(locale, {
                                  notation: 'compact',
                                  maximumFractionDigits: 1,
                                }).format(p.count)}
                              </span>
                            </span>
                          </button>
                        ) : (
                          <button
                            key={slot}
                            type="button"
                            disabled={
                              editing || !canReplace || (section !== 'belt' && section !== 'bag')
                            }
                            aria-pressed={selected === key}
                            onClick={(e) => {
                              selectedButton.current = e.currentTarget;
                              setSelected(key);
                            }}
                            data-inventory-slot={key}
                            aria-label={`${t('sdtd.inventory.slot')} ${slot + 1}: ${t('sdtd.inventory.emptySlot')}`}
                            className={`h-24 min-w-0 rounded border border-dashed p-2 text-left text-xs text-muted enabled:hover:bg-white/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary ${selected === key ? 'border-primary bg-primary/10' : 'border-border'}`}
                          >
                            {slot + 1}
                          </button>
                        );
                      })}
                    </div>
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
