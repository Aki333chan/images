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
type Item = SevenDaysItemCatalogue['items'][number];

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
  const [request, setRequest] = useState<Grant | null>(null);
  const [result, setResult] = useState<SevenDaysItemGrantResult['status'] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [searching, setSearching] = useState(false);
  const [lookupRevision, setLookupRevision] = useState(0);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const lock = useRef(false);
  const unlockTimer = useRef<ReturnType<typeof setTimeout>>();
  const alive = useRef(true);
  const searchInput = useRef<HTMLInputElement>(null);
  const searchBox = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const openedBefore = useRef(false);
  const item = catalogue?.items.find((p) => p.itemId === Number(itemId));
  const items = catalogue?.ready
    ? catalogue.items.filter((p) => p.name.toLowerCase().includes(query.trim().toLowerCase()))
    : [];
  const frozen = busy || result === 'unknown';
  const showSuggestions = suggestionsOpen && !frozen && query.trim().length >= 2;
  const base = `/api/modules/sevendays/servers/${serverId}`;

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      clearTimeout(unlockTimer.current);
    };
  }, []);
  useEffect(() => {
    if (open) {
      openedBefore.current = true;
      searchInput.current?.focus();
    } else if (openedBefore.current) trigger.current?.focus();
  }, [open]);
  useEffect(() => {
    if (showSuggestions && active >= 0)
      list.current?.children[active]?.scrollIntoView({ block: 'nearest' });
  }, [active, showSuggestions]);
  useEffect(() => {
    if (!showSuggestions) return;
    const closeOutside = (e: PointerEvent) => {
      if (!searchBox.current?.contains(e.target as Node)) setSuggestionsOpen(false);
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [showSuggestions]);
  useEffect(() => {
    setSearching(false);
    if (!open || frozen || itemId || query.trim().length < 2) return;
    const abort = new AbortController();
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
          setActive(-1);
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
  }, [base, open, frozen, query, itemId, lookupRevision]);

  function choose(selected: Item) {
    searchInput.current?.focus();
    setQuery(selected.name);
    setItemId(String(selected.itemId));
    setCount('1');
    setQuality('1');
    setSuggestionsOpen(false);
    setActive(-1);
  }
  async function send(payload: Grant) {
    if (lock.current) return;
    lock.current = true;
    const started = Date.now();
    setBusy(true);
    setRequest(payload);
    setError('');
    setResult(null);
    setSuggestionsOpen(false);
    try {
      const response = await api<SevenDaysItemGrantResult>(
        `${base}/players/${encodeURIComponent(playerId)}/item-drop`,
        { method: 'POST', body: JSON.stringify(payload) },
      );
      if (alive.current) {
        setResult(response.status);
        if (response.status === 'session_expired') {
          setCatalogue(null);
          setItemId('');
          setQuery('');
        }
      }
    } catch {
      if (alive.current) setResult('unknown');
    } finally {
      // Keep rapid double-clicks/Enter repeats inside the game's one-second grant limit.
      if (alive.current)
        unlockTimer.current = setTimeout(
          () => {
            lock.current = false;
            setBusy(false);
          },
          Math.max(0, 1000 - (Date.now() - started)),
        );
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
        <section aria-label={`${t('sdtd.give.open')} · ${name}`} className="mt-4 space-y-4">
          <p className="max-w-prose text-sm text-muted">{t('sdtd.give.warning')}</p>
          <form
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (!item || !catalogue || frozen) return;
              void send({
                sessionId: catalogue.sessionId,
                requestId: crypto.randomUUID(),
                itemId: item.itemId,
                itemName: item.name,
                count: Number(count),
                quality: item.hasQuality ? Number(quality) : 0,
                // Preserve the existing audited API contract; clicking Give is the confirmation.
                reason: 'Panel item grant',
                confirmed: true,
              });
            }}
          >
            <div
              ref={searchBox}
              className="relative space-y-2"
              onBlur={(e) => {
                if (e.relatedTarget && !e.currentTarget.contains(e.relatedTarget as Node))
                  setSuggestionsOpen(false);
              }}
            >
              <label htmlFor={`${controlId}-item`} className="block text-sm">
                {t('sdtd.give.search')}
              </label>
              <Input
                id={`${controlId}-item`}
                ref={searchInput}
                value={query}
                autoComplete="off"
                role="combobox"
                aria-autocomplete="list"
                aria-expanded={showSuggestions}
                aria-controls={showSuggestions ? `${controlId}-suggestions` : undefined}
                aria-activedescendant={
                  showSuggestions && active >= 0 && items[active]
                    ? `${controlId}-option-${active}`
                    : undefined
                }
                aria-describedby={`${controlId}-hint`}
                disabled={frozen}
                maxLength={128}
                required
                onFocus={() => setSuggestionsOpen(true)}
                onChange={(e) => {
                  const value = e.target.value;
                  setQuery(value);
                  setError('');
                  setSearching(false);
                  setActive(-1);
                  setSuggestionsOpen(true);
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
                onKeyDown={(e) => {
                  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                    e.preventDefault();
                    setSuggestionsOpen(true);
                    setActive((i) =>
                      items.length
                        ? e.key === 'ArrowDown'
                          ? (i + 1) % items.length
                          : i <= 0
                            ? items.length - 1
                            : i - 1
                        : -1,
                    );
                  } else if (e.key === 'Enter') {
                    e.preventDefault();
                    if (showSuggestions && items[active]) choose(items[active]);
                    else setSuggestionsOpen(false);
                  } else if (e.key === 'Tab') setSuggestionsOpen(false);
                  else if (e.key === 'Escape' && showSuggestions) {
                    e.preventDefault();
                    e.stopPropagation();
                    setSuggestionsOpen(false);
                    setActive(-1);
                  }
                }}
              />
              {showSuggestions && (
                <div className="absolute left-0 right-0 top-full z-20 mt-1 overflow-hidden rounded-md border border-border bg-background">
                  <ul
                    id={`${controlId}-suggestions`}
                    ref={list}
                    role="listbox"
                    aria-label={t('sdtd.give.choose')}
                    aria-busy={searching}
                    className="max-h-60 overflow-y-auto overscroll-contain p-1"
                  >
                    {items.map((p, index) => (
                      <li
                        key={p.itemId}
                        role="option"
                        id={`${controlId}-option-${index}`}
                        aria-selected={index === active}
                        className={`flex min-h-11 cursor-pointer items-center justify-between gap-3 rounded px-3 py-2 text-sm hover:bg-primary/10 ${index === active ? 'bg-primary/15' : ''}`}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => choose(p)}
                      >
                        <span className="min-w-0 break-all">{p.name}</span>
                        <span className="shrink-0 text-xs text-muted">
                          {p.hasQuality ? `${t('sdtd.inventory.quality')} 1–6` : `×${p.maxCount}`}
                        </span>
                      </li>
                    ))}
                  </ul>
                  {searching && <Spinner />}
                  {catalogue && !catalogue.ready && (
                    <p className="p-3 text-sm" role="status">
                      {t('sdtd.give.not_ready')}
                    </p>
                  )}
                  {catalogue?.ready && !items.length && (
                    <p className="p-3 text-sm" role="status">
                      {t('sdtd.give.empty')}
                    </p>
                  )}
                  {catalogue?.truncated && (
                    <p className="border-t border-border p-3 text-xs text-muted">
                      {t('sdtd.give.truncated')}
                    </p>
                  )}
                </div>
              )}
            </div>
            <p id={`${controlId}-hint`} className="text-xs text-muted">
              {t('sdtd.give.suggestHint')}
            </p>
            {item && (
              <fieldset disabled={frozen} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-sm">
                  {t('sdtd.inventory.count')} (1–{item.maxCount})
                  <Input
                    type="number"
                    min={1}
                    max={item.maxCount}
                    step={1}
                    required
                    value={count}
                    disabled={frozen}
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
              </fieldset>
            )}
            {error && (
              <>
                <ErrorText>{error}</ErrorText>
                <Button
                  type="button"
                  variant="outline"
                  disabled={searching || frozen}
                  onClick={() => {
                    setSuggestionsOpen(true);
                    setLookupRevision((v) => v + 1);
                  }}
                >
                  {t('sdtd.give.retrySearch')}
                </Button>
              </>
            )}
            <div className="flex flex-wrap items-center gap-2">
              {result === 'unknown' && request ? (
                <Button type="button" disabled={busy} onClick={() => void send(request)}>
                  {t('sdtd.give.retry')}
                </Button>
              ) : (
                <Button type="submit" disabled={!item || frozen}>
                  {t('sdtd.give.submit')}
                </Button>
              )}
              <Button
                type="button"
                variant="ghost"
                disabled={busy}
                onClick={() => {
                  setOpen(false);
                  setSuggestionsOpen(false);
                }}
              >
                {t('sdtd.give.close')}
              </Button>
              {busy && <Spinner />}
            </div>
          </form>
          <div role="status" aria-live="polite" className="space-y-1 text-sm">
            {request && result && (
              <>
                <p className="break-all">
                  {request.itemName} ×{request.count}
                  {request.quality > 0 && (
                    <>
                      {' '}
                      · {t('sdtd.inventory.quality')} {request.quality}
                    </>
                  )}
                </p>
                <p>{t(`sdtd.give.${result}`)}</p>
                <p className="break-all font-mono text-xs text-muted">ID: {request.requestId}</p>
              </>
            )}
          </div>
        </section>
      )}
    </div>
  );
}
