import { useEffect, useId, useRef, useState } from 'react';
import type { SevenDaysItemCatalogue, SevenDaysItemGrantResult } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, ErrorText, Input, Select, Spinner } from '../../components/ui';
import { useI18n } from '../../i18n';

type Grant = {
  sessionId: string;
  requestId: string;
  itemId: number;
  itemName: string;
  count: number;
  quality: number;
  reason: string;
  confirmed: true;
};

export function SevenDaysGiveItemPanel({
  serverId,
  playerId,
  name,
}: {
  serverId: string;
  playerId: string;
  name: string;
}) {
  const { t } = useI18n();
  const controlId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [catalogue, setCatalogue] = useState<SevenDaysItemCatalogue | null>(null);
  const [itemId, setItemId] = useState('');
  const [count, setCount] = useState('1');
  const [quality, setQuality] = useState('1');
  const [reason, setReason] = useState('');
  const [review, setReview] = useState<Grant | null>(null);
  const [attempted, setAttempted] = useState(false);
  const [result, setResult] = useState<SevenDaysItemGrantResult['status'] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [searching, setSearching] = useState(false);
  const [lookupRevision, setLookupRevision] = useState(0);
  const lock = useRef(false);
  const searchAbort = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const searchInput = useRef<HTMLInputElement>(null);
  const reviewHeading = useRef<HTMLHeadingElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const openedBefore = useRef(false);
  const item = catalogue?.items.find((p) => p.itemId === Number(itemId));
  const base = `/api/modules/sevendays/servers/${serverId}`;
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      searchAbort.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (open) {
      openedBefore.current = true;
      if (review) reviewHeading.current?.focus();
      else searchInput.current?.focus();
    } else if (openedBefore.current) trigger.current?.focus();
  }, [open, review]);
  useEffect(() => {
    setSearching(false);
    if (!open || review || itemId || query.trim().length < 2) return;
    const abort = new AbortController();
    searchAbort.current = abort;
    const timer = setTimeout(() => {
      setSearching(true);
      setError('');
      void api<SevenDaysItemCatalogue>(
        `${base}/items?q=${encodeURIComponent(query.trim().slice(0, 64))}`,
        { signal: abort.signal },
      )
        .then((data) => {
          if (abort.signal.aborted) return;
          setCatalogue(data);
          const exact = data.items.find((p) => p.name.toLowerCase() === query.trim().toLowerCase());
          if (data.ready && exact) {
            setItemId(String(exact.itemId));
            setCount('1');
            setQuality('1');
          }
        })
        .catch((e: Error) => {
          if (!abort.signal.aborted) setError(`${t('sdtd.give.searchFailed')} ${e.message}`);
        })
        .finally(() => {
          if (!abort.signal.aborted) setSearching(false);
        });
    }, 400);
    return () => {
      clearTimeout(timer);
      abort.abort();
    };
  }, [base, open, review, query, itemId, lookupRevision]);
  async function send() {
    if (!review || lock.current) return;
    lock.current = true;
    setBusy(true);
    setAttempted(true);
    setError('');
    setResult(null);
    try {
      const response = await api<SevenDaysItemGrantResult>(
        `${base}/players/${encodeURIComponent(playerId)}/item-drop`,
        { method: 'POST', body: JSON.stringify(review) },
      );
      if (alive.current) setResult(response.status);
    } catch {
      if (alive.current) setResult('unknown');
    } finally {
      lock.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return (
    <div className="border-t border-border pt-4">
      <Button
        ref={trigger}
        type="button"
        variant="outline"
        aria-expanded={open}
        onClick={() => setOpen(true)}
        disabled={open}
      >
        {t('sdtd.give.open')}
      </Button>
      {open && (
        <section aria-label={t('sdtd.give.open')} className="mt-4 space-y-4">
          <p className="max-w-prose text-sm text-muted">{t('sdtd.give.warning')}</p>
          {!review ? (
            <>
              <div className="space-y-2">
                <label className="block space-y-1 text-sm">
                  {t('sdtd.give.search')}
                  <Input
                    ref={searchInput}
                    value={query}
                    list={`${controlId}-suggestions`}
                    autoComplete="off"
                    aria-describedby={`${controlId}-hint`}
                    onChange={(e) => {
                      const value = e.target.value;
                      setQuery(value);
                      setError('');
                      setSearching(false);
                      const chosen =
                        catalogue?.ready &&
                        catalogue.items.find(
                          (p) => p.name.toLowerCase() === value.trim().toLowerCase(),
                        );
                      if (chosen) {
                        setItemId(String(chosen.itemId));
                        setCount('1');
                        setQuality('1');
                      } else {
                        setCatalogue(null);
                        setItemId('');
                      }
                    }}
                    minLength={2}
                    maxLength={128}
                    required
                    disabled={busy}
                  />
                </label>
                <datalist id={`${controlId}-suggestions`}>
                  {catalogue?.ready &&
                    catalogue.items.map((p) => <option key={p.itemId} value={p.name} />)}
                </datalist>
                <p id={`${controlId}-hint`} className="text-xs text-muted">
                  {t('sdtd.give.suggestHint')}
                </p>
                {searching && <Spinner />}
              </div>
              {catalogue && !catalogue.ready && <p role="status">{t('sdtd.give.not_ready')}</p>}
              {catalogue?.ready && (
                <>
                  {catalogue.truncated && (
                    <p className="text-sm text-muted">{t('sdtd.give.truncated')}</p>
                  )}
                  {!catalogue.items.length ? (
                    <p role="status">{t('sdtd.give.empty')}</p>
                  ) : (
                    <form
                      className="space-y-4"
                      onSubmit={(e) => {
                        e.preventDefault();
                        if (!item || busy) return;
                        setReview({
                          sessionId: catalogue.sessionId,
                          requestId: crypto.randomUUID(),
                          itemId: item.itemId,
                          itemName: item.name,
                          count: Number(count),
                          quality: item.hasQuality ? Number(quality) : 0,
                          reason: reason.trim(),
                          confirmed: true,
                        });
                      }}
                    >
                      {item && (
                        <>
                          <p className="break-all text-sm">
                            {item.name} · ID {item.itemId}
                          </p>
                          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            <label className="space-y-1 text-sm">
                              {t('sdtd.inventory.count')} (1–{item.maxCount})
                              <Input
                                type="number"
                                min={1}
                                max={item.maxCount}
                                step={1}
                                required
                                value={count}
                                onChange={(e) => setCount(e.target.value)}
                              />
                            </label>
                            {item.hasQuality && (
                              <div className="space-y-1 text-sm">
                                <label htmlFor={`${controlId}-quality`} className="block">
                                  {t('sdtd.inventory.quality')}
                                </label>
                                <Select
                                  id={`${controlId}-quality`}
                                  value={quality}
                                  onChange={setQuality}
                                  options={[1, 2, 3, 4, 5, 6].map((q) => ({
                                    value: String(q),
                                    label: String(q),
                                  }))}
                                />
                              </div>
                            )}
                          </div>
                          <label className="block space-y-1 text-sm">
                            {t('sdtd.give.reason')}
                            <Input
                              value={reason}
                              onChange={(e) => setReason(e.target.value)}
                              minLength={3}
                              maxLength={200}
                              required
                            />
                          </label>
                          <Button type="submit" disabled={busy || reason.trim().length < 3}>
                            {t('sdtd.give.review')}
                          </Button>
                        </>
                      )}
                    </form>
                  )}
                </>
              )}
            </>
          ) : (
            <div className="space-y-3">
              <h4 tabIndex={-1} ref={reviewHeading} className="font-semibold">
                {t('sdtd.give.review')}
              </h4>
              <dl className="space-y-2 break-words text-sm">
                <div>
                  <dt className="text-muted">{t('sdtd.give.player')}</dt>
                  <dd className="break-all">
                    {name} · {playerId}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted">{t('sdtd.give.item')}</dt>
                  <dd className="break-all">
                    {review.itemName} ×{review.count}
                    {review.quality > 0 && (
                      <>
                        {' '}
                        · {t('sdtd.inventory.quality')} {review.quality}
                      </>
                    )}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted">{t('sdtd.give.reason')}</dt>
                  <dd>{review.reason}</dd>
                </div>
              </dl>
              <div role="status" aria-live="polite">
                {result && <p>{t(`sdtd.give.${result}`)}</p>}
              </div>
              {attempted && (
                <p className="break-all font-mono text-xs text-muted">ID: {review.requestId}</p>
              )}
              <div className="flex flex-wrap gap-2">
                {!attempted && (
                  <>
                    <Button type="button" disabled={busy} onClick={() => void send()}>
                      {t('sdtd.give.confirm')}
                    </Button>
                    <Button type="button" variant="ghost" onClick={() => setReview(null)}>
                      {t('sdtd.give.back')}
                    </Button>
                  </>
                )}
                {result === 'unknown' && (
                  <Button
                    type="button"
                    disabled={busy}
                    variant="outline"
                    onClick={() => void send()}
                  >
                    {t('sdtd.give.retry')}
                  </Button>
                )}
                {result && result !== 'unknown' && (
                  <Button
                    type="button"
                    disabled={busy}
                    variant="outline"
                    onClick={() => {
                      setReview(null);
                      setAttempted(false);
                      setResult(null);
                      setCatalogue(null);
                      setItemId('');
                      setQuery('');
                    }}
                  >
                    {t('sdtd.give.new')}
                  </Button>
                )}
              </div>
            </div>
          )}
          {busy && <Spinner />}
          {error && (
            <>
              <ErrorText>{error}</ErrorText>
              <Button
                type="button"
                variant="outline"
                onClick={() => setLookupRevision((v) => v + 1)}
                disabled={searching}
              >
                {t('sdtd.give.retrySearch')}
              </Button>
            </>
          )}
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            onClick={() => {
              setOpen(false);
              trigger.current?.focus();
            }}
          >
            {t('sdtd.give.close')}
          </Button>
        </section>
      )}
    </div>
  );
}
