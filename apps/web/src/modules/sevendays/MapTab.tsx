import { useEffect, useRef, useState } from 'react';
import type { SevenDaysMapSnapshot, SevenDaysMapPois } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Spinner, Input } from '../../components/ui';
import { useI18n } from '../../i18n';
import { IconClose } from '../../components/icons';
import type { ModuleTabProps } from '../registry';
import { MAP_WIDTH, MAP_HEIGHT, TILE_PIXELS, tileSpan, visibleTiles, mapPoint } from './map-math';
import { SevenDaysZoneEditor, useSevenDaysZones } from './ZoneEditor';

export function SevenDaysMapTab({ serverId }: ModuleTabProps) {
  const { t } = useI18n();
  const zones = useSevenDaysZones(serverId);
  const [data, setData] = useState<SevenDaysMapSnapshot | null>(null);
  const [error, setError] = useState('');
  const [view, setView] = useState({ x: 0, z: 0, zoom: 0 });
  const [layers, setLayers] = useState({ players: true, claims: true, pois: false });
  const [pois, setPois] = useState<SevenDaysMapPois | null>(null);
  const [poiError, setPoiError] = useState('');
  const [poiSearch, setPoiSearch] = useState('');
  const [tradersOnly, setTradersOnly] = useState(false);
  const [selected, setSelected] = useState('');
  const [images, setImages] = useState<Record<string, string>>({});
  const [tileError, setTileError] = useState(false);
  const [markerScale, setMarkerScale] = useState(1);
  const svg = useRef<SVGSVGElement | null>(null);
  const drag = useRef<{
    id: number;
    x: number;
    y: number;
    cx: number;
    cz: number;
    units: number;
    span: number;
    moved: boolean;
    details: string | null;
    zoneId: string | null;
  } | null>(null);
  const initialized = useRef(false);
  const cache = useRef(new Map<string, { url: string | null; until: number }>());
  const base = `/api/modules/sevendays/servers/${serverId}/map`;
  const maxZoom = data?.info?.maxZoom ?? 4;
  const level = Math.min(view.zoom, maxZoom);
  const span = tileSpan(data?.info?.blockSize ?? 128, maxZoom, level);
  const tiles = visibleTiles(view.x, view.z, span);
  useEffect(() => {
    setPois(null);
    setPoiError('');
    if (!layers.pois || !data?.available) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function pollPois() {
      try {
        if (!document.hidden) {
          const result = await api<SevenDaysMapPois>(`${base}/pois`, { signal: abort.signal });
          if (abort.signal.aborted) return;
          setPois(result);
          setPoiError('');
        }
      } catch (e) {
        if (!abort.signal.aborted) {
          setPois(null);
          setPoiError((e as Error).message);
        }
      } finally {
        if (!abort.signal.aborted) timer = setTimeout(pollPois, 60000);
      }
    }
    void pollPois();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [base, layers.pois, data?.available]);
  useEffect(() => {
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    initialized.current = false;
    cache.current.clear();
    setData(null);
    setImages({});
    setSelected('');
    async function poll() {
      try {
        if (!document.hidden) {
          const next = await api<SevenDaysMapSnapshot>(base, { signal: abort.signal });
          if (abort.signal.aborted) return;
          setData(next);
          setError('');
          if (!initialized.current && next.available) {
            initialized.current = true;
            const p = next.players[0] ?? next.claims[0];
            setView({
              x: p?.x ?? 0,
              z: p?.z ?? 0,
              zoom: Math.max(0, (next.info?.maxZoom ?? 4) - 2),
            });
          }
        }
      } catch (e) {
        if (!abort.signal.aborted) {
          setError((e as Error).message);
          setData(null);
          setImages({});
        }
      } finally {
        if (!abort.signal.aborted) timer = setTimeout(poll, 10000);
      }
    }
    void poll();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [base]);
  useEffect(() => {
    const abort = new AbortController();
    setTileError(false);
    if (!data?.info || !data.available) {
      setImages({});
      cache.current.clear();
      return;
    }
    // Debounce pan/zoom; two workers only, no unbounded request queue or retry loop.
    const timer = setTimeout(() => {
      const todo = visibleTiles(view.x, view.z, span);
      let index = 0;
      async function worker() {
        while (index < todo.length && !abort.signal.aborted) {
          const { x, z } = todo[index++]!;
          const key = `${level}/${x}/${z}`;
          try {
            let entry = cache.current.get(key);
            if (!entry || entry.until < Date.now()) {
              const result = await api<{ png: string | null }>(`${base}/tiles/${key}`, {
                signal: abort.signal,
              });
              if (abort.signal.aborted) return;
              const url = result.png ? `data:image/png;base64,${result.png}` : (entry?.url ?? null);
              if (url && url !== entry?.url) {
                // Decode before replacing the visible image; stale terrain remains underneath.
                const image = new Image();
                image.src = url;
                await image.decode();
                if (abort.signal.aborted) return;
              }
              entry = {
                url,
                until: Date.now() + 15000,
              };
              cache.current.delete(key);
              if (cache.current.size >= 96)
                cache.current.delete(cache.current.keys().next().value!);
              cache.current.set(key, entry);
            }
            if (entry.url && !abort.signal.aborted)
              setImages((old) => {
                const url = entry!.url!;
                if (old[key] === url) return old;
                const next = { ...old };
                delete next[key];
                next[key] = url;
                // Two visible zoom levels plus a small pan buffer, never an unbounded world cache.
                if (Object.keys(next).length > 96) delete next[Object.keys(next)[0]!];
                return next;
              });
          } catch {
            if (!abort.signal.aborted) setTileError(true);
          }
        }
      }
      void worker();
      void worker();
    }, 250);
    return () => {
      clearTimeout(timer);
      abort.abort();
    };
  }, [base, data, view.x, view.z, span, level]);
  useEffect(() => {
    const el = svg.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      const width = el.getBoundingClientRect().width;
      if (width > 0) setMarkerScale(Math.max(1, Math.min(5, MAP_WIDTH / width)));
    });
    observer.observe(el);
    function wheel(e: WheelEvent) {
      e.preventDefault();
      setView((v) => ({
        ...v,
        zoom: Math.max(0, Math.min(maxZoom, v.zoom + (e.deltaY < 0 ? 1 : -1))),
      }));
    }
    el.addEventListener('wheel', wheel, { passive: false });
    return () => {
      observer.disconnect();
      el.removeEventListener('wheel', wheel);
    };
  }, [maxZoom, data?.available]);
  if (!data) return error ? <ErrorText>{error}</ErrorText> : <Spinner />;
  if (!data.available) return <Card>{t(`sdtd.map.${data.reason ?? 'mod_unavailable'}`)}</Card>;
  const point = (x: number, z: number) => mapPoint(x, z, view.x, view.z, span);
  const onScreen = (x: number, z: number, margin = 20) =>
    x >= -margin &&
    yFinite(z) &&
    x <= MAP_WIDTH + margin &&
    z >= -margin &&
    z <= MAP_HEIGHT + margin;
  const matchingPois = layers.pois
    ? (pois?.pois ?? []).filter(
        (p) =>
          (!tradersOnly || p.trader) &&
          p.name.toLowerCase().includes(poiSearch.trim().toLowerCase()),
      )
    : [];
  const visiblePois = matchingPois.filter((p) => {
    const q = point(p.x, p.z);
    return onScreen(q.x, q.y);
  });
  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant={zones.open ? 'default' : 'outline'}
          aria-expanded={zones.open}
          disabled={zones.busy}
          onClick={() => {
            if (zones.open && zones.dirty && !window.confirm(t('sdtd.zones.discard'))) return;
            zones.setOpen(!zones.open);
            zones.setDraft(null);
            zones.setDrawing(false);
            zones.setCorner(null);
          }}
        >
          {t('sdtd.zones.title')}
        </Button>
        <Button
          variant="outline"
          disabled={level <= 0}
          onClick={() => setView((v) => ({ ...v, zoom: Math.max(0, v.zoom - 1) }))}
          aria-label={t('sdtd.map.zoomOut')}
        >
          −
        </Button>
        <Button
          variant="outline"
          disabled={level >= maxZoom}
          onClick={() => setView((v) => ({ ...v, zoom: Math.min(maxZoom, v.zoom + 1) }))}
          aria-label={t('sdtd.map.zoomIn')}
        >
          +
        </Button>
        <Button variant="outline" onClick={() => setView((v) => ({ ...v, x: 0, z: 0 }))}>
          {t('sdtd.map.origin')}
        </Button>
        <select
          className="max-w-full rounded border border-border bg-card px-3 py-2 text-sm"
          aria-label={t('sdtd.map.findPlayer')}
          value=""
          onChange={(e) => {
            const p = data.players.find((p) => p.id === e.target.value);
            if (p) setView((v) => ({ ...v, x: p.x, z: p.z }));
          }}
        >
          <option value="">{t('sdtd.map.findPlayer')}</option>
          {data.players.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
        {(['players', 'claims', 'pois'] as const).map((k) => (
          <label key={k} className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={layers[k]}
              onChange={(e) => setLayers((l) => ({ ...l, [k]: e.target.checked }))}
            />
            {t(`sdtd.map.${k}`)}
          </label>
        ))}
      </div>
      {layers.pois && (
        <div className="space-y-2">
          <p className="text-xs text-muted">{t('sdtd.map.poiHelp')}</p>
          {!pois && !poiError && <Spinner />}
          {poiError && <ErrorText>{poiError}</ErrorText>}
          {pois && !pois.available && (
            <p className="text-sm text-amber-400">
              {t(
                pois.reason === 'mod_update'
                  ? 'sdtd.map.poiUpdate'
                  : `sdtd.map.${pois.reason ?? 'world_loading'}`,
              )}
            </p>
          )}
          {pois?.available && (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  className="w-full sm:w-64"
                  aria-label={t('sdtd.map.poiSearch')}
                  placeholder={t('sdtd.map.poiSearch')}
                  maxLength={128}
                  value={poiSearch}
                  onChange={(e) => setPoiSearch(e.target.value)}
                />
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={tradersOnly}
                    onChange={(e) => setTradersOnly(e.target.checked)}
                  />
                  {t('sdtd.map.poiTraders')}
                </label>
                <select
                  className="max-w-full rounded border border-border bg-card px-3 py-2 text-base sm:text-sm"
                  aria-label={t('sdtd.map.poiFind')}
                  value=""
                  onChange={(e) => {
                    const p = matchingPois.find((p) => String(p.id) === e.target.value);
                    if (p) {
                      setView((v) => ({ ...v, x: p.x, z: p.z }));
                      setSelected(
                        `${p.name} · ${t('sdtd.map.poiTier')} ${p.tier} · X ${Math.round(p.x)} / Z ${Math.round(p.z)}`,
                      );
                    }
                  }}
                >
                  <option value="">{t('sdtd.map.poiFind')}</option>
                  {matchingPois.slice(0, 50).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({Math.round(p.x)}, {Math.round(p.z)})
                    </option>
                  ))}
                </select>
              </div>
              {!matchingPois.length && (
                <p className="text-sm text-muted">{t('sdtd.map.poiEmpty')}</p>
              )}
              {(pois.truncated || visiblePois.length > 200 || matchingPois.length > 50) && (
                <p className="text-xs text-amber-400">{t('sdtd.map.poiLimit')}</p>
              )}
            </>
          )}
        </div>
      )}
      <p className="text-xs text-muted">{t('sdtd.map.help')}</p>
      {data.reason && <p className="text-xs text-amber-400">{t(`sdtd.map.${data.reason}`)}</p>}
      {data.truncated && <p className="text-xs text-amber-400">{t('sdtd.map.truncated')}</p>}
      {tileError && <p className="text-xs text-amber-400">{t('sdtd.map.tileError')}</p>}
      {zones.open && zones.drawing && (
        <p role="status" className="text-sm text-primary">
          {t(zones.corner ? 'sdtd.zones.secondCorner' : 'sdtd.zones.firstCorner')}
        </p>
      )}
      {/* Safari needs touch-action on an HTML layout box, not only SVG content. */}
      <div
        className="touch-none overscroll-contain"
        style={{ maxWidth: '122svh', marginInline: 'auto' }}
      >
        <svg
          ref={svg}
          viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`}
          role="group"
          tabIndex={-1}
          aria-label={t('sdtd.map.title')}
          className="w-full rounded-lg border border-border bg-black/30"
          style={{
            maxWidth: '122svh',
            display: 'block',
            marginInline: 'auto',
            touchAction: 'none',
            cursor: zones.drawing ? 'crosshair' : 'grab',
            userSelect: 'none',
            WebkitUserSelect: 'none',
          }}
          onDragStart={(e) => e.preventDefault()}
          onPointerDown={(e) => {
            if (e.button !== 0 || !e.isPrimary) return;
            e.preventDefault();
            const marker = (e.target as Element).closest('[data-map-details]');
            drag.current = {
              id: e.pointerId,
              x: e.clientX,
              y: e.clientY,
              cx: view.x,
              cz: view.z,
              units: MAP_WIDTH / e.currentTarget.getBoundingClientRect().width,
              span,
              moved: false,
              details: marker?.getAttribute('data-map-details') ?? null,
              zoneId:
                (e.target as Element).closest('[data-zone-id]')?.getAttribute('data-zone-id') ??
                null,
            };
            e.currentTarget.style.cursor = 'grabbing';
            e.currentTarget.setPointerCapture(e.pointerId);
          }}
          onPointerMove={(e) => {
            const d = drag.current;
            if (!d || d.id !== e.pointerId) return;
            const dx = e.clientX - d.x,
              dy = e.clientY - d.y;
            d.moved ||= Math.hypot(dx, dy) > 4;
            if (!d.moved) return;
            setView((v) => ({
              ...v,
              x: Math.max(
                -500000,
                Math.min(500000, d.cx - ((dx * d.units) / TILE_PIXELS) * d.span),
              ),
              z: Math.max(
                -500000,
                Math.min(500000, d.cz + ((dy * d.units) / TILE_PIXELS) * d.span),
              ),
            }));
          }}
          onPointerUp={(e) => {
            const d = drag.current;
            if (!d || d.id !== e.pointerId) return;
            if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) <= 4) {
              if (zones.open && zones.drawing) {
                const rect = e.currentTarget.getBoundingClientRect();
                zones.pick(
                  Math.round(
                    view.x +
                      ((((e.clientX - rect.left) * MAP_WIDTH) / rect.width - MAP_WIDTH / 2) /
                        TILE_PIXELS) *
                        span,
                  ),
                  Math.round(
                    view.z -
                      ((((e.clientY - rect.top) * MAP_HEIGHT) / rect.height - MAP_HEIGHT / 2) /
                        TILE_PIXELS) *
                        span,
                  ),
                );
              } else if (d.zoneId && zones.open)
                zones.choose(zones.data?.zones.find((z) => z.id === d.zoneId) ?? null);
              else if (d.details) setSelected(d.details);
            }
            drag.current = null;
            e.currentTarget.style.cursor = zones.drawing ? 'crosshair' : 'grab';
            if (e.currentTarget.hasPointerCapture(e.pointerId))
              e.currentTarget.releasePointerCapture(e.pointerId);
          }}
          onLostPointerCapture={(e) => {
            if (drag.current?.id !== e.pointerId) return;
            drag.current = null;
            e.currentTarget.style.cursor = zones.drawing ? 'crosshair' : 'grab';
          }}
          onPointerCancel={(e) => {
            if (drag.current?.id !== e.pointerId) return;
            drag.current = null;
            e.currentTarget.style.cursor = zones.drawing ? 'crosshair' : 'grab';
          }}
        >
          {tiles.map(({ x, z }) => {
            const key = `${level}/${x}/${z}`,
              p = point(x * span, (z + 1) * span);
            return (
              <g key={key}>
                <rect
                  x={p.x}
                  y={p.y}
                  width={TILE_PIXELS}
                  height={TILE_PIXELS}
                  fill="none"
                  stroke="#ffffff12"
                  pointerEvents="none"
                />
              </g>
            );
          })}
          {Object.entries(images)
            // Previous zoom levels are a scaled backdrop until current tiles are ready.
            .sort(([a], [b]) => {
              const az = Number(a.split('/')[0]),
                bz = Number(b.split('/')[0]);
              return (az === level ? 100 : az) - (bz === level ? 100 : bz);
            })
            .map(([key, url]) => {
              const [zoom, x, z] = key.split('/').map(Number) as [number, number, number];
              const oldSpan = tileSpan(data.info?.blockSize ?? 128, maxZoom, zoom);
              const p = point(x * oldSpan, (z + 1) * oldSpan);
              const size = (oldSpan / span) * TILE_PIXELS;
              if (!onScreen(p.x, p.y, size)) return null;
              return (
                <image
                  key={key}
                  href={url}
                  x={p.x}
                  y={p.y}
                  width={size}
                  height={size}
                  pointerEvents="none"
                />
              );
            })}
          {zones.open &&
            [
              ...(zones.data?.zones ?? []).filter((z) => z.id !== zones.draft?.id),
              ...(zones.draft && !zones.drawing ? [zones.draft] : []),
            ].map((z) => {
              const p = point(z.x1, z.z2),
                q = point(z.x2, z.z1);
              if (![p.x, p.y, q.x, q.y].every(Number.isFinite)) return null;
              return (
                <g
                  key={z.id}
                  role="button"
                  tabIndex={0}
                  aria-label={z.name || t('sdtd.zones.new')}
                  data-zone-id={z.id}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      zones.choose(z);
                    }
                  }}
                >
                  <rect
                    x={p.x}
                    y={p.y}
                    width={q.x - p.x}
                    height={q.y - p.y}
                    fill={zones.draft?.id === z.id ? '#a78bfa33' : '#34d39922'}
                    stroke={z.enabled ? '#a78bfa' : '#9ca3af'}
                    strokeWidth={zones.draft?.id === z.id ? 3 : 2}
                    strokeDasharray={z.enabled ? undefined : '6 4'}
                  />
                  <title>{z.name}</title>
                </g>
              );
            })}
          {zones.open && zones.corner && (
            <circle
              cx={point(zones.corner.x, zones.corner.z).x}
              cy={point(zones.corner.x, zones.corner.z).y}
              r={5 * markerScale}
              fill="#a78bfa"
              stroke="white"
              pointerEvents="none"
            />
          )}
          {visiblePois.slice(0, 200).map((p) => {
            const q = point(p.x, p.z),
              r = 6 * markerScale;
            const details = `${p.name} · ${p.trader ? t('sdtd.map.poiTraders') + ' · ' : ''}${t('sdtd.map.poiTier')} ${p.tier} · X ${Math.round(p.x)} / Z ${Math.round(p.z)}`;
            return (
              <g
                key={`poi/${p.id}`}
                role="button"
                tabIndex={0}
                aria-label={details}
                data-map-details={details}
                style={{ cursor: 'pointer' }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    setSelected(details);
                  }
                }}
              >
                <path
                  d={`M ${q.x} ${q.y - r} L ${q.x + r} ${q.y} L ${q.x} ${q.y + r} L ${q.x - r} ${q.y} Z`}
                  fill={p.trader ? '#34d399' : '#38bdf8'}
                  stroke="white"
                  pointerEvents="none"
                />
                <rect
                  x={q.x - 12 * markerScale}
                  y={q.y - 12 * markerScale}
                  width={24 * markerScale}
                  height={24 * markerScale}
                  fill="transparent"
                  pointerEvents="all"
                />
                <title>{details}</title>
              </g>
            );
          })}
          {layers.claims &&
            data.claims.map((c, i) => {
              const p = point(c.x + 0.5, c.z + 0.5),
                size = (c.size / span) * TILE_PIXELS;
              if (!onScreen(p.x, p.y, size)) return null;
              const hitSize = Math.max(size, 24 * markerScale);
              const details = `${t('sdtd.map.claims')} · ${c.owner || c.ownerId} · X ${c.x} / Z ${c.z} · ${c.size}×${c.size}`;
              return (
                <g
                  key={`${c.ownerId}/${c.x}/${c.z}/${i}`}
                  role="button"
                  tabIndex={0}
                  aria-label={details}
                  data-map-details={details}
                  style={{ cursor: 'pointer' }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelected(details);
                    }
                  }}
                >
                  <rect
                    x={p.x - size / 2}
                    y={p.y - size / 2}
                    width={size}
                    height={size}
                    fill="#facc1522"
                    stroke="#facc15"
                    strokeWidth={selected === details ? 3 : 1.5}
                    pointerEvents="none"
                  />
                  <rect
                    data-map-hit
                    x={p.x - hitSize / 2}
                    y={p.y - hitSize / 2}
                    width={hitSize}
                    height={hitSize}
                    fill="transparent"
                    pointerEvents="all"
                  />
                  <title>{`${c.owner} (${c.x}, ${c.z})`}</title>
                </g>
              );
            })}
          {layers.players &&
            data.players.map((p) => {
              const q = point(p.x, p.z);
              if (!onScreen(q.x, q.y)) return null;
              const details = `${p.name} · X ${Math.round(p.x)} / Z ${Math.round(p.z)}`;
              return (
                <g
                  key={p.id}
                  role="button"
                  tabIndex={0}
                  aria-label={details}
                  data-map-details={details}
                  style={{ cursor: 'pointer' }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelected(details);
                    }
                  }}
                >
                  <circle cx={q.x} cy={q.y} r={6 * markerScale} fill="#818cf8" stroke="white" />
                  <text
                    x={q.x + 10 * markerScale}
                    y={q.y - 9 * markerScale}
                    fill="white"
                    fontSize={14 * markerScale}
                    stroke="#000"
                    strokeWidth={3 * markerScale}
                    paintOrder="stroke"
                  >
                    {p.name}
                  </text>
                  <title>{p.name}</title>
                </g>
              );
            })}
          <text
            x={12 * markerScale}
            y={22 * markerScale}
            fill="white"
            fontSize={16 * markerScale}
            pointerEvents="none"
          >
            N ↑
          </text>
        </svg>
      </div>
      <div className="space-y-1">
        {selected && (
          <div className="flex items-start gap-2 rounded border border-border bg-background pl-3 text-sm">
            <p role="status" className="min-w-0 flex-1 break-words py-2">
              {selected}
            </p>
            <button
              type="button"
              aria-label={t('sdtd.map.clearSelection')}
              title={t('sdtd.map.clearSelection')}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded hover:bg-white/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
              onClick={() => {
                setSelected('');
                svg.current?.focus({ preventScroll: true });
              }}
            >
              <IconClose size={16} />
            </button>
          </div>
        )}
        <p className="break-words text-xs text-muted">
          X {Math.round(view.x)} / Z {Math.round(view.z)} ·{' '}
          {t(zones.open ? 'sdtd.zones.coordinates' : 'sdtd.map.readOnly')}
        </p>
      </div>
      <SevenDaysZoneEditor zone={zones} players={data?.players} />
    </Card>
  );
}
const yFinite = (n: number) => Number.isFinite(n);
