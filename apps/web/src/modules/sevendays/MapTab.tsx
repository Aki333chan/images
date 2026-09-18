import { useEffect, useRef, useState } from 'react';
import type { SevenDaysMapSnapshot } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Spinner } from '../../components/ui';
import { useI18n } from '../../i18n';
import type { ModuleTabProps } from '../registry';
import { MAP_WIDTH, MAP_HEIGHT, TILE_PIXELS, tileSpan, visibleTiles, mapPoint } from './map-math';

export function SevenDaysMapTab({ serverId }: ModuleTabProps) {
  const { t } = useI18n();
  const [data, setData] = useState<SevenDaysMapSnapshot | null>(null);
  const [error, setError] = useState('');
  const [view, setView] = useState({ x: 0, z: 0, zoom: 0 });
  const [layers, setLayers] = useState({ players: true, claims: true });
  const [selected, setSelected] = useState('');
  const [images, setImages] = useState<Record<string, string>>({});
  const [tileError, setTileError] = useState(false);
  const [markerScale, setMarkerScale] = useState(1);
  const svg = useRef<SVGSVGElement | null>(null);
  const drag = useRef<{ x: number; y: number; cx: number; cz: number; moved: boolean } | null>(
    null,
  );
  const initialized = useRef(false);
  const cache = useRef(new Map<string, { url: string | null; until: number }>());
  const base = `/api/modules/sevendays/servers/${serverId}/map`;
  const maxZoom = data?.info?.maxZoom ?? 4;
  const level = Math.min(view.zoom, maxZoom);
  const span = tileSpan(data?.info?.blockSize ?? 128, maxZoom, level);
  const tiles = visibleTiles(view.x, view.z, span);
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
    setImages({});
    setTileError(false);
    if (!data?.info || !data.available) return;
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
              entry = {
                url: result.png ? `data:image/png;base64,${result.png}` : null,
                until: Date.now() + 15000,
              };
              if (cache.current.size >= 64)
                cache.current.delete(cache.current.keys().next().value!);
              cache.current.set(key, entry);
            }
            if (entry.url && !abort.signal.aborted)
              setImages((old) => ({ ...old, [key]: entry!.url! }));
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
  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
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
        {(['players', 'claims'] as const).map((k) => (
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
      <p className="text-xs text-muted">{t('sdtd.map.help')}</p>
      {data.reason && <p className="text-xs text-amber-400">{t(`sdtd.map.${data.reason}`)}</p>}
      {data.truncated && <p className="text-xs text-amber-400">{t('sdtd.map.truncated')}</p>}
      {tileError && <p className="text-xs text-amber-400">{t('sdtd.map.tileError')}</p>}
      <svg
        ref={svg}
        viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`}
        role="img"
        aria-label={t('sdtd.map.title')}
        className="w-full rounded-lg border border-border bg-black/30"
        style={{ touchAction: 'none', cursor: 'grab' }}
        onPointerDown={(e) => {
          drag.current = { x: e.clientX, y: e.clientY, cx: view.x, cz: view.z, moved: false };
          e.currentTarget.setPointerCapture(e.pointerId);
        }}
        onPointerMove={(e) => {
          const d = drag.current;
          if (!d) return;
          const rect = e.currentTarget.getBoundingClientRect();
          const dx = ((e.clientX - d.x) * MAP_WIDTH) / rect.width,
            dy = ((e.clientY - d.y) * MAP_HEIGHT) / rect.height;
          d.moved ||= Math.abs(dx) + Math.abs(dy) > 4;
          setView((v) => ({
            ...v,
            x: Math.max(-500000, Math.min(500000, d.cx - (dx / TILE_PIXELS) * span)),
            z: Math.max(-500000, Math.min(500000, d.cz + (dy / TILE_PIXELS) * span)),
          }));
        }}
        onPointerUp={(e) => {
          if (e.currentTarget.hasPointerCapture(e.pointerId))
            e.currentTarget.releasePointerCapture(e.pointerId);
          drag.current = null;
        }}
        onPointerCancel={() => {
          drag.current = null;
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
              />
              {images[key] && (
                <image
                  href={images[key]}
                  x={p.x}
                  y={p.y}
                  width={TILE_PIXELS}
                  height={TILE_PIXELS}
                />
              )}
            </g>
          );
        })}
        {layers.claims &&
          data.claims.map((c, i) => {
            const p = point(c.x + 0.5, c.z + 0.5),
              size = (c.size / span) * TILE_PIXELS;
            if (!onScreen(p.x, p.y, size)) return null;
            return (
              <rect
                key={`${c.ownerId}/${i}`}
                x={p.x - size / 2}
                y={p.y - size / 2}
                width={size}
                height={size}
                fill="#facc1522"
                stroke="#facc15"
                strokeWidth="1.5"
                onPointerDown={(e) => e.stopPropagation()}
                onClick={() =>
                  setSelected(`${c.owner} · X ${c.x} / Z ${c.z} · ${c.size}×${c.size}`)
                }
              >
                <title>{`${c.owner} (${c.x}, ${c.z})`}</title>
              </rect>
            );
          })}
        {layers.players &&
          data.players.map((p) => {
            const q = point(p.x, p.z);
            if (!onScreen(q.x, q.y)) return null;
            return (
              <g
                key={p.id}
                onPointerDown={(e) => e.stopPropagation()}
                onClick={() =>
                  setSelected(`${p.name} · X ${Math.round(p.x)} / Z ${Math.round(p.z)}`)
                }
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
        <text x={12 * markerScale} y={22 * markerScale} fill="white" fontSize={16 * markerScale}>
          N ↑
        </text>
      </svg>
      <p className="break-words text-xs text-muted" aria-live="polite">
        {selected || `X ${Math.round(view.x)} / Z ${Math.round(view.z)}`} · {t('sdtd.map.readOnly')}
      </p>
    </Card>
  );
}
const yFinite = (n: number) => Number.isFinite(n);
