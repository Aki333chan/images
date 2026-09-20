import { useEffect, useRef, useState } from 'react';
import {
  SEVENDAYS_PERMISSIONS as P,
  type SevenDaysTools,
  type SevenDaysPlayerDto,
  type SevenDaysKit,
  type SevenDaysPoint,
  type SevenDaysToolResult,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { useI18n } from '../../i18n';
import { Button, Card, ErrorText, Input, Select, Spinner } from '../../components/ui';
import { SevenDaysGiveItemPanel } from './GiveItemPanel';
import { SevenDaysAutomation } from './ToolsSchedules';

const playerId = (p: SevenDaysPlayerDto) => p.platformId ?? p.crossId ?? '';
export function SevenDaysToolsPanel({
  serverId,
  players,
  onRefreshPlayers,
}: {
  serverId: string;
  players: SevenDaysPlayerDto[];
  onRefreshPlayers: () => Promise<unknown>;
}) {
  const { hasPermission } = useAuth();
  const { t } = useI18n();
  const manage = hasPermission(P.toolsManage),
    teleport = hasPermission(P.teleport),
    give = hasPermission(P.inventoryGive);
  const [open, setOpen] = useState(false);
  const [chosenTab, setTab] = useState('teleport');
  const allowedTabs = ['teleport', 'kits', 'automation'].filter((k) =>
    k === 'teleport'
      ? teleport || manage
      : k === 'kits'
        ? give || manage
        : hasPermission('schedules.manage'),
  );
  const tab = allowedTabs.includes(chosenTab) ? chosenTab : allowedTabs[0];
  const [data, setData] = useState<SevenDaysTools | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [target, setTarget] = useState('');
  const [destination, setDestination] = useState('position');
  const [point, setPoint] = useState<SevenDaysPoint>({ id: '', name: '', x: 0, y: -1, z: 0 });
  const [kit, setKit] = useState<SevenDaysKit>({ id: '', name: '', items: [] });
  const [result, setResult] = useState<SevenDaysToolResult | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const lock = useRef(false);
  const kitNameInput = useRef<HTMLInputElement>(null);
  const focusAfterLastItem = useRef(false);
  useEffect(() => {
    if (focusAfterLastItem.current) {
      focusAfterLastItem.current = false;
      kitNameInput.current?.focus();
    }
  }, [kit.items.length]);
  const kitDirty =
    !!kit.id && JSON.stringify(kit) !== JSON.stringify(data?.kits.find((k) => k.id === kit.id));
  const base = `/api/modules/sevendays/servers/${serverId}`;
  useEffect(() => {
    if (!open || !(manage || teleport || give)) return;
    const abort = new AbortController();
    setData(null);
    setError('');
    void api<SevenDaysTools>(`${base}/tools`, { signal: abort.signal })
      .then((d) => {
        if (!abort.signal.aborted) setData(d);
      })
      .catch((e: Error) => {
        if (!abort.signal.aborted) setError(e.message);
      });
    return () => abort.abort();
  }, [base, open, refresh, manage, teleport, give]);
  useEffect(() => {
    setConfirmed(false);
    setResult(null);
  }, [target, destination, point, kit, tab]);
  if (!(manage || teleport || give || hasPermission('schedules.manage'))) return null;
  async function save(next: SevenDaysTools) {
    if (lock.current) return false;
    lock.current = true;
    setBusy(true);
    setError('');
    try {
      setData(
        await api<SevenDaysTools>(`${base}/tools`, { method: 'PUT', body: JSON.stringify(next) }),
      );
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
      lock.current = false;
    }
  }
  async function run(kind: 'teleport' | 'kit') {
    if (lock.current || !confirmed || !target || result) return;
    lock.current = true;
    setBusy(true);
    setError('');
    const body =
      kind === 'kit'
        ? { kitId: kit.id }
        : destination === 'position'
          ? { position: point }
          : destination.startsWith('point:')
            ? { pointId: destination.slice(6) }
            : { targetId: destination.slice(7) };
    try {
      setResult(
        await api<SevenDaysToolResult>(`${base}/players/${encodeURIComponent(target)}/${kind}`, {
          method: 'POST',
          body: JSON.stringify({ ...body, requestId: crypto.randomUUID(), confirmed: true }),
        }),
      );
    } catch (e) {
      setError((e as Error).message);
      if (![400, 403, 409, 503].includes((e as { status?: number }).status ?? 0))
        setResult({ status: 'unknown' });
    } finally {
      setBusy(false);
      lock.current = false;
      setConfirmed(false);
    }
  }
  const playerOptions = [
    { value: '', label: t('sdtd.tools.choosePlayer') },
    ...players.filter(playerId).map((p) => ({ value: playerId(p), label: p.name })),
  ];
  return (
    <Card className="space-y-4">
      <Button
        variant="outline"
        aria-expanded={open}
        aria-controls="sdtd-tools-content"
        onClick={() => setOpen((v) => !v)}
        disabled={busy}
      >
        {t('sdtd.tools.title')}
      </Button>
      {open && (
        <section id="sdtd-tools-content" className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {allowedTabs.map((k) => (
              <Button
                key={k}
                variant={tab === k ? 'default' : 'outline'}
                aria-pressed={tab === k}
                disabled={busy}
                onClick={() => setTab(k)}
              >
                {t(`sdtd.tools.${k}`)}
              </Button>
            ))}
          </div>
          <ErrorText>{error}</ErrorText>
          {tab === 'automation' ? (
            <SevenDaysAutomation serverId={serverId} />
          ) : !data ? (
            <>
              <Button variant="outline" onClick={() => setRefresh((v) => v + 1)}>
                {t('common.refresh')}
              </Button>
              {!error && <Spinner />}
            </>
          ) : (
            <>
              <fieldset disabled={busy} className="flex flex-wrap items-end gap-3">
                <label className="min-w-0 flex-1 text-sm" htmlFor="sdtd-tools-player">
                  {t('sdtd.tools.player')}
                  <Select
                    id="sdtd-tools-player"
                    value={target}
                    onChange={setTarget}
                    options={playerOptions}
                  />
                </label>
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={async () => {
                    if (lock.current) return;
                    lock.current = true;
                    setBusy(true);
                    setConfirmed(false);
                    setResult(null);
                    setError('');
                    try {
                      await onRefreshPlayers();
                      setRefresh((v) => v + 1);
                    } catch (e) {
                      setError((e as Error).message);
                    } finally {
                      lock.current = false;
                      setBusy(false);
                    }
                  }}
                >
                  {t('common.refresh')}
                </Button>
              </fieldset>
              <fieldset disabled={busy} className="space-y-4">
                {tab === 'teleport' && (
                  <>
                    <p className="max-w-prose text-sm text-muted">{t('sdtd.tools.teleportNote')}</p>
                    <label className="block text-sm" htmlFor="sdtd-tools-destination">
                      {t('sdtd.tools.destination')}
                      <Select
                        id="sdtd-tools-destination"
                        value={destination}
                        onChange={(v) => {
                          setDestination(v);
                          if (v.startsWith('point:')) {
                            const p = data.points.find((p) => p.id === v.slice(6));
                            if (p) setPoint({ ...p });
                          }
                        }}
                        options={[
                          { value: 'position', label: t('sdtd.tools.coordinates') },
                          ...data.points.map((p) => ({ value: `point:${p.id}`, label: p.name })),
                          ...players
                            .filter((p) => playerId(p) && playerId(p) !== target)
                            .map((p) => ({
                              value: `player:${playerId(p)}`,
                              label: `${t('sdtd.tools.player')}: ${p.name}`,
                            })),
                        ]}
                      />
                    </label>
                    {!destination.startsWith('player:') && (
                      <div className="grid grid-cols-3 gap-3">
                        {(['x', 'y', 'z'] as const).map((axis) => (
                          <label key={axis} className="text-sm">
                            {axis.toUpperCase()}
                            <Input
                              type="number"
                              step={1}
                              min={axis === 'y' ? -1 : -100000}
                              max={axis === 'y' ? 255 : 100000}
                              value={point[axis]}
                              onChange={(e) => {
                                setDestination('position');
                                setPoint({ ...point, [axis]: Number(e.target.value) });
                              }}
                            />
                          </label>
                        ))}
                      </div>
                    )}
                    {target && (
                      <Button
                        variant="outline"
                        onClick={() => {
                          const p = players.find((p) => playerId(p) === target)?.position;
                          if (p) {
                            setPoint({
                              ...point,
                              x: Math.round(p.x),
                              y: Math.round(p.y),
                              z: Math.round(p.z),
                            });
                            setDestination('position');
                          }
                        }}
                      >
                        {t('sdtd.tools.usePosition')}
                      </Button>
                    )}
                    {manage && !destination.startsWith('player:') && (
                      <div className="space-y-3 border-t border-border pt-4">
                        <label className="block text-sm">
                          {t('sdtd.tools.pointName')}
                          <Input
                            value={point.name}
                            maxLength={64}
                            onChange={(e) => setPoint({ ...point, name: e.target.value })}
                          />
                        </label>
                        <div className="flex flex-wrap gap-2">
                          <Button
                            disabled={!point.name.trim()}
                            onClick={() => {
                              const next = { ...point, id: point.id || crypto.randomUUID() };
                              void save({
                                ...data,
                                points: [...data.points.filter((p) => p.id !== point.id), next],
                              }).then((ok) => {
                                if (ok) setPoint(next);
                              });
                            }}
                          >
                            {t('sdtd.tools.savePoint')}
                          </Button>
                          <Button
                            variant="outline"
                            onClick={() => {
                              setPoint({ id: '', name: '', x: 0, y: -1, z: 0 });
                              setDestination('position');
                            }}
                          >
                            {t('sdtd.tools.newPoint')}
                          </Button>
                          {point.id && (
                            <Button
                              variant="destructive"
                              onClick={() => {
                                if (confirm(t('sdtd.tools.deleteConfirm')))
                                  void save({
                                    ...data,
                                    points: data.points.filter((p) => p.id !== point.id),
                                  }).then((ok) => {
                                    if (ok) {
                                      setPoint({ ...point, id: '', name: '' });
                                      setDestination('position');
                                    }
                                  });
                              }}
                            >
                              {t('common.delete')}
                            </Button>
                          )}
                        </div>
                      </div>
                    )}
                  </>
                )}
                {tab === 'kits' && (
                  <>
                    <p className="max-w-prose text-sm text-muted">{t('sdtd.tools.kitNote')}</p>
                    <label htmlFor="sdtd-tools-kit" className="block text-sm">
                      {t('sdtd.tools.kits')}
                      <Select
                        id="sdtd-tools-kit"
                        value={kit.id}
                        onChange={(id) =>
                          setKit(
                            data.kits.find((k) => k.id === id) ?? { id: '', name: '', items: [] },
                          )
                        }
                        options={[
                          { value: '', label: t('sdtd.tools.newKit') },
                          ...data.kits.map((k) => ({ value: k.id, label: k.name })),
                        ]}
                      />
                    </label>
                    {manage && (
                      <label className="block text-sm">
                        {t('sdtd.tools.name')}
                        <Input
                          ref={kitNameInput}
                          value={kit.name}
                          maxLength={64}
                          onChange={(e) => setKit({ ...kit, name: e.target.value })}
                        />
                      </label>
                    )}
                    <ul className="space-y-2">
                      {kit.items.map((i, n) => (
                        <li
                          key={n}
                          className="flex flex-wrap items-center gap-2 border-b border-border pb-2 text-sm"
                        >
                          <span className="min-w-0 flex-1 break-all">{i.name}</span>
                          <span>
                            ×{i.count}
                            {i.quality ? ` · Q${i.quality}` : ''}
                          </span>
                          {manage && (
                            <Button
                              variant="ghost"
                              aria-label={`${t('common.delete')} ${i.name}`}
                              onClick={() =>
                                setKit({ ...kit, items: kit.items.filter((_, j) => j !== n) })
                              }
                            >
                              {t('common.delete')}
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                    {manage && (
                      <>
                        {kit.items.length < 16 && (
                          <SevenDaysGiveItemPanel
                            key={kit.id}
                            serverId={serverId}
                            playerId=""
                            name={kit.name}
                            onPick={(i) => {
                              focusAfterLastItem.current = kit.items.length === 15;
                              setKit({ ...kit, items: [...kit.items, i] });
                            }}
                          />
                        )}
                        <div className="flex flex-wrap gap-2">
                          <Button
                            disabled={!kit.name.trim() || !kit.items.length}
                            onClick={() => {
                              const next = { ...kit, id: kit.id || crypto.randomUUID() };
                              void save({
                                ...data,
                                kits: [...data.kits.filter((k) => k.id !== kit.id), next],
                              }).then((ok) => {
                                if (ok) setKit(next);
                              });
                            }}
                          >
                            {t('sdtd.tools.saveKit')}
                          </Button>
                          {kit.id && (
                            <Button
                              variant="destructive"
                              onClick={() => {
                                if (confirm(t('sdtd.tools.deleteConfirm')))
                                  void save({
                                    ...data,
                                    kits: data.kits.filter((k) => k.id !== kit.id),
                                  }).then((ok) => {
                                    if (ok) setKit({ id: '', name: '', items: [] });
                                  });
                              }}
                            >
                              {t('common.delete')}
                            </Button>
                          )}
                        </div>
                      </>
                    )}
                  </>
                )}
                {(tab === 'teleport' ? teleport : give) && (
                  <div className="space-y-3 border-t border-border pt-4">
                    <label className="flex items-start gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                        disabled={!!result}
                      />
                      <span>
                        {t(
                          tab === 'teleport'
                            ? 'sdtd.tools.confirmTeleport'
                            : 'sdtd.tools.confirmKit',
                        )}
                      </span>
                    </label>
                    {tab === 'kits' && kitDirty && <p>{t('sdtd.tools.unsavedKit')}</p>}
                    <Button
                      disabled={
                        !target ||
                        !confirmed ||
                        !!result ||
                        (tab === 'kits' && (!kit.id || kitDirty))
                      }
                      onClick={() => void run(tab === 'teleport' ? 'teleport' : 'kit')}
                    >
                      {t(
                        tab === 'teleport' ? 'sdtd.tools.executeTeleport' : 'sdtd.tools.executeKit',
                      )}
                    </Button>
                  </div>
                )}
              </fieldset>
              {busy && <Spinner />}
              {result && (
                <div role="status" className="space-y-2 text-sm">
                  <p>{t(`sdtd.tools.result.${result.status}`)}</p>
                  {result.items?.map((i, n) => (
                    <p key={n} className="break-words">
                      <span className="break-all">{i.name}</span>: {t(`sdtd.give.${i.status}`)}
                    </p>
                  ))}
                  <Button
                    variant="outline"
                    onClick={() => {
                      setResult(null);
                      setConfirmed(false);
                    }}
                  >
                    {t('sdtd.tools.newAction')}
                  </Button>
                </div>
              )}
            </>
          )}
        </section>
      )}
    </Card>
  );
}
