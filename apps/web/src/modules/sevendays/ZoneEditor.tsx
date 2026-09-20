import { useEffect, useRef, useState } from 'react';
import {
  SEVENDAYS_PERMISSIONS,
  SEVENDAYS_ZONE_TYPES,
  SEVENDAYS_ZONE_BONUSES,
  parseSevenDaysZones,
  type SevenDaysZone,
  type SevenDaysZones,
} from '@aurum/shared';
import { useAuth } from '../../lib/auth';
import { useI18n } from '../../i18n';
import { api } from '../../lib/api';
import { Button, ErrorText, Input, Spinner } from '../../components/ui';

const selectClass =
  'block w-full rounded border border-border bg-card px-3 py-2 text-base sm:text-sm';

export function zonePreset(type: SevenDaysZone['type']) {
  return {
    type,
    noPvp: type === 'safe' || type === 'sanctuary',
    noDamage: false,
    blockSpawn: type === 'sanctuary' ? 5 : 0,
    despawn: 0,
    bonus: type === 'bonus' ? ('regeneration' as const) : ('none' as const),
  };
}

export function useSevenDaysZones(serverId: string) {
  const { hasPermission } = useAuth();
  const { t } = useI18n();
  const manage = hasPermission(SEVENDAYS_PERMISSIONS.configure);
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<SevenDaysZones | null>(null);
  const [draft, setDraft] = useState<SevenDaysZone | null>(null);
  const [drawing, setDrawing] = useState(false);
  const [corner, setCorner] = useState<{ x: number; z: number } | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const lock = useRef(false);
  const base = `/api/modules/sevendays/servers/${serverId}/zones`;
  const activeBase = useRef(base);
  activeBase.current = base;
  useEffect(() => {
    setDraft(null);
    setData(null);
    setCorner(null);
    setDrawing(false);
  }, [base]);
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    setError('');
    void api<unknown>(base, { signal: abort.signal })
      .then((value) => {
        if (!abort.signal.aborted) setData(parseSevenDaysZones(value));
      })
      .catch((e: Error) => {
        if (!abort.signal.aborted) {
          setData(null);
          setError(e.message);
        }
      });
    return () => abort.abort();
  }, [base, open, refresh]);
  const dirty =
    !!draft && JSON.stringify(draft) !== JSON.stringify(data?.zones.find((z) => z.id === draft.id));
  function choose(zone: SevenDaysZone | null) {
    if (busy || (dirty && !window.confirm(t('sdtd.zones.discard')))) return;
    setDraft(zone ? { ...zone } : null);
    setDrawing(false);
    setCorner(null);
    setNotice('');
  }
  function add(x = 0, z = 0) {
    if (
      !manage ||
      busy ||
      !data ||
      data.zones.length >= 100 ||
      (dirty && !window.confirm(t('sdtd.zones.discard')))
    )
      return;
    setDraft({
      id: `zone-${crypto.randomUUID()}`,
      name: '',
      enabled: true,
      x1: x,
      z1: z,
      x2: x + 10,
      z2: z + 10,
      enter: '',
      exit: '',
      ...zonePreset('safe'),
    });
    setCorner(null);
    setDrawing(true);
    setNotice('');
  }
  function pick(x: number, z: number) {
    if (!drawing || !draft || busy) return;
    if (!corner) {
      setCorner({ x, z });
      return;
    }
    if (x === corner.x || z === corner.z) return;
    setDraft({
      ...draft,
      x1: Math.min(x, corner.x),
      z1: Math.min(z, corner.z),
      x2: Math.max(x, corner.x),
      z2: Math.max(z, corner.z),
    });
    setCorner(null);
    setDrawing(false);
  }
  async function save(remove = false) {
    if (!manage || !draft || !data || lock.current) return;
    if (remove && !window.confirm(t('sdtd.zones.confirmDelete'))) return;
    const existing = data.zones.find((z) => z.id === draft.id);
    if (
      !remove &&
      (draft.despawn & ~(existing?.despawn ?? 0)) !== 0 &&
      !window.confirm(t('sdtd.zones.despawnWarning'))
    )
      return;
    let next: SevenDaysZones;
    try {
      next = parseSevenDaysZones({
        ...data,
        zones: [...data.zones.filter((z) => z.id !== draft.id), ...(remove ? [] : [draft])],
      });
    } catch {
      setError(t('sdtd.zones.invalid'));
      return;
    }
    lock.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = parseSevenDaysZones(
        await api(base, { method: 'PUT', body: JSON.stringify(next) }),
      );
      if (activeBase.current !== base) return;
      setData(saved);
      setDraft(remove ? null : (saved.zones.find((z) => z.id === draft.id) ?? null));
      setDrawing(false);
      setCorner(null);
      setNotice(t('sdtd.zones.saved'));
    } catch (e) {
      if (activeBase.current === base)
        setError(`${(e as Error).message} ${t('sdtd.zones.retryHelp')}`);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return {
    open,
    setOpen,
    data,
    draft,
    setDraft,
    drawing,
    setDrawing,
    corner,
    setCorner,
    error,
    notice,
    busy,
    dirty,
    manage,
    choose,
    add,
    pick,
    save,
    reload: () => {
      if (!busy && (!dirty || window.confirm(t('sdtd.zones.discard')))) {
        setDraft(null);
        setData(null);
        setRefresh((n) => n + 1);
      }
    },
  };
}

export function SevenDaysZoneEditor({ zone }: { zone: ReturnType<typeof useSevenDaysZones> }) {
  const { t } = useI18n();
  const z = zone.draft;
  const selectedId = z?.id;
  const nameInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (selectedId && !zone.drawing) nameInput.current?.focus({ preventScroll: true });
  }, [selectedId, zone.drawing]);
  if (!zone.open) return null;
  const change = (fields: Partial<SevenDaysZone>) => {
    if (z) zone.setDraft({ ...z, ...fields });
  };
  return (
    <section className="space-y-4 border-t border-border pt-4" aria-label={t('sdtd.zones.title')}>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="mr-auto text-base font-semibold">{t('sdtd.zones.title')}</h3>
        <Button variant="outline" disabled={zone.busy} onClick={zone.reload}>
          {t('sdtd.zones.refresh')}
        </Button>
        {zone.manage && (
          <Button
            disabled={zone.busy || !zone.data || zone.data.zones.length >= 100}
            onClick={() => zone.add()}
          >
            {t('sdtd.zones.add')}
          </Button>
        )}
      </div>
      <p className="max-w-prose text-sm text-muted">{t('sdtd.zones.help')}</p>
      {zone.error && <ErrorText>{zone.error}</ErrorText>}
      {zone.notice && (
        <p role="status" className="text-sm text-emerald-400">
          {zone.notice}
        </p>
      )}
      {!zone.data && !zone.error && <Spinner />}
      {zone.data && (
        <label className="block space-y-1 text-sm">
          {t('sdtd.zones.select')}
          <select
            className={selectClass}
            aria-label={t('sdtd.zones.select')}
            value={z?.id ?? ''}
            disabled={zone.busy}
            onChange={(e) =>
              zone.choose(zone.data!.zones.find((v) => v.id === e.target.value) ?? null)
            }
          >
            <option value="">{t('sdtd.zones.select')}</option>
            {z && !zone.data.zones.some((v) => v.id === z.id) && (
              <option value={z.id}>{t('sdtd.zones.new')}</option>
            )}
            {zone.data.zones.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}
                {v.enabled ? '' : ` (${t('sdtd.zones.disabled')})`}
              </option>
            ))}
          </select>
        </label>
      )}
      {z && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void zone.save();
          }}
          className="space-y-4"
        >
          {zone.drawing && (
            <p role="status" className="text-sm text-primary">
              {t(zone.corner ? 'sdtd.zones.secondCorner' : 'sdtd.zones.firstCorner')}
            </p>
          )}
          <fieldset disabled={!zone.manage || zone.busy} className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-1 text-sm">
                {t('sdtd.zones.name')}
                <Input
                  ref={nameInput}
                  required
                  maxLength={80}
                  value={z.name}
                  onChange={(e) => change({ name: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                {t('sdtd.zones.type')}
                <select
                  className={selectClass}
                  aria-label={t('sdtd.zones.type')}
                  value={z.type}
                  onChange={(e) => change(zonePreset(e.target.value as SevenDaysZone['type']))}
                >
                  {SEVENDAYS_ZONE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {t(`sdtd.zones.type.${type}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={z.enabled}
                onChange={(e) => change({ enabled: e.target.checked })}
              />
              {t('sdtd.zones.enabled')}
            </label>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {(['x1', 'z1', 'x2', 'z2'] as const).map((key) => (
                <label key={key} className="space-y-1 text-sm">
                  {key.toUpperCase()}
                  <Input
                    type="number"
                    step="any"
                    required
                    min={-500000}
                    max={500000}
                    value={Number.isFinite(z[key]) ? z[key] : ''}
                    onChange={(e) => change({ [key]: e.target.valueAsNumber })}
                  />
                </label>
              ))}
            </div>
            <Button
              variant="outline"
              type="button"
              onClick={() => {
                zone.setDrawing(!zone.drawing);
                zone.setCorner(null);
              }}
            >
              {t(zone.drawing ? 'sdtd.zones.stopDrawing' : 'sdtd.zones.redraw')}
            </Button>
            <fieldset className="space-y-2">
              <legend className="mb-2 text-sm font-semibold">{t('sdtd.zones.protection')}</legend>
              {(['noPvp', 'noDamage'] as const).map((key) => (
                <label key={key} className="flex items-start gap-2 text-sm">
                  <input
                    className="mt-1"
                    type="checkbox"
                    checked={z[key]}
                    onChange={(e) => change({ [key]: e.target.checked })}
                  />
                  {t(`sdtd.zones.${key}`)}
                </label>
              ))}
              <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.protectionHelp')}</p>
            </fieldset>
            <fieldset className="space-y-3">
              <legend className="mb-2 text-sm font-semibold">{t('sdtd.zones.creatures')}</legend>
              {[
                ['zombies', 1],
                ['peaceful', 2],
                ['hostile', 4],
              ].map(([name, bit]) => (
                <div key={name} className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
                  <span className="w-full font-medium sm:w-40">{t(`sdtd.zones.${name}`)}</span>
                  {(['blockSpawn', 'despawn'] as const).map((key) => (
                    <label key={key} className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={(z[key] & Number(bit)) !== 0}
                        onChange={(e) =>
                          change({
                            [key]: e.target.checked ? z[key] | Number(bit) : z[key] & ~Number(bit),
                          })
                        }
                      />
                      {t(`sdtd.zones.${key}`)}
                    </label>
                  ))}
                </div>
              ))}
              <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.despawnWarning')}</p>
            </fieldset>
            <label className="block space-y-1 text-sm">
              {t('sdtd.zones.bonus')}
              <select
                className={selectClass}
                aria-label={t('sdtd.zones.bonus')}
                value={z.bonus}
                onChange={(e) => change({ bonus: e.target.value as SevenDaysZone['bonus'] })}
              >
                {SEVENDAYS_ZONE_BONUSES.map((b) => (
                  <option key={b} value={b}>
                    {t(`sdtd.zones.bonus.${b}`)}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              {(['enter', 'exit'] as const).map((key) => (
                <label key={key} className="space-y-1 text-sm">
                  {t(`sdtd.zones.${key}`)}
                  <Input
                    maxLength={240}
                    value={z[key]}
                    onChange={(e) => change({ [key]: e.target.value })}
                  />
                </label>
              ))}
            </div>
          </fieldset>
          <div className="flex flex-wrap gap-2">
            {zone.manage && (
              <Button type="submit" disabled={zone.busy || !zone.dirty || !z.name.trim()}>
                {t('sdtd.zones.save')}
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              disabled={zone.busy}
              onClick={() => zone.choose(null)}
            >
              {t('sdtd.zones.close')}
            </Button>
            {zone.manage && zone.data?.zones.some((v) => v.id === z.id) && (
              <Button
                className="sm:ml-auto"
                variant="destructive"
                type="button"
                disabled={zone.busy}
                onClick={() => void zone.save(true)}
              >
                {t('sdtd.zones.delete')}
              </Button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
