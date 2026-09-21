import { useEffect, useRef, useState } from 'react';
import {
  SEVENDAYS_PERMISSIONS,
  SEVENDAYS_ZONE_TYPES,
  SEVENDAYS_ZONE_BONUSES,
  SEVENDAYS_ZONE_BONUS_MAX,
  parseSevenDaysZones,
  hasZoneCommands,
  defaultZoneMovement,
  defaultZoneSchedule,
  type SevenDaysZone,
  type SevenDaysZones,
} from '@aurum/shared';
import { useAuth } from '../../lib/auth';
import { useI18n } from '../../i18n';
import { api } from '../../lib/api';
import { Button, ErrorText, Input, Spinner } from '../../components/ui';
import { zoneClock, zoneOffset, zoneDate, zoneDateUtc } from './zone-time';

const selectClass =
  'block w-full rounded border border-border bg-card px-3 py-2 text-base sm:text-sm';

export function zonePreset(type: SevenDaysZone['type']) {
  return {
    type,
    noPvp: ['safe', 'sanctuary', 'restricted', 'prison', 'event'].includes(type),
    noDamage: false,
    noCreatureBlockDamage: false,
    noExplosionBlockDamage: false,
    blockSpawn: type === 'sanctuary' ? 5 : 0,
    despawn: 0,
    bonuses: { regeneration: type === 'bonus' ? 1 : 0, stamina: 0, speed: 0 },
  };
}

export function useSevenDaysZones(serverId: string) {
  const { hasPermission } = useAuth();
  const { t } = useI18n();
  const manage = hasPermission(SEVENDAYS_PERMISSIONS.zonesManage);
  const commandsManage = hasPermission(SEVENDAYS_PERMISSIONS.zonesCommands);
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
      commandsEnabled: false,
      commandCooldown: 30,
      enterCommands: [],
      exitCommands: [],
      movement: defaultZoneMovement(),
      schedule: defaultZoneSchedule(),
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
    if (!commandsManage && hasZoneCommands(draft)) return;
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
        zones: [
          ...data.zones.filter((z) => z.id !== draft.id),
          ...(remove
            ? []
            : [
                {
                  ...draft,
                  enterCommands: draft.enterCommands.map((c) => c.trim()).filter(Boolean),
                  exitCommands: draft.exitCommands.map((c) => c.trim()).filter(Boolean),
                  movement: {
                    ...draft.movement,
                    players: draft.movement.players.map((id) => id.trim()).filter(Boolean),
                  },
                },
              ]),
        ],
      });
    } catch (e) {
      setError(
        t(
          (e as Error).message === 'zones_destination_conflict'
            ? 'sdtd.zones.destinationConflict'
            : (e as Error).message === 'zones_prison_schedule'
              ? 'sdtd.zones.schedule.prison'
              : 'sdtd.zones.invalid',
        ),
      );
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
        setError(
          (e as Error).message.includes('zones_destination_unavailable')
            ? t('sdtd.zones.destinationUnavailable')
            : `${(e as Error).message} ${t('sdtd.zones.retryHelp')}`,
        );
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
    commandsManage,
    scriptedLocked: !commandsManage && !!draft && hasZoneCommands(draft),
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

export function SevenDaysZoneEditor({
  zone,
  players = [],
}: {
  zone: ReturnType<typeof useSevenDaysZones>;
  players?: { id: string; name: string }[];
}) {
  const { t } = useI18n();
  const z = zone.draft;
  const containment = z?.movement.mode === 'prison' || z?.movement.mode === 'event';
  const [prisonerId, setPrisonerId] = useState('');
  const selectedId = z?.id;
  const nameInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    setPrisonerId('');
    if (selectedId && !zone.drawing) nameInput.current?.focus({ preventScroll: true });
  }, [selectedId, zone.drawing]);
  if (!zone.open) return null;
  const change = (fields: Partial<SevenDaysZone>) => {
    if (z) zone.setDraft({ ...z, ...fields });
  };
  const addPrisoner = (player: string) => {
    if (
      !z ||
      z.movement.sentences.length >= 64 ||
      !/^[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}$/.test(player) ||
      z.movement.sentences.some((s) => s.player === player)
    )
      return;
    change({
      movement: { ...z.movement, sentences: [...z.movement.sentences, { player, until: 0 }] },
    });
    setPrisonerId('');
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
          {zone.scriptedLocked && (
            <p className="text-sm text-muted">{t('sdtd.zones.commandsLocked')}</p>
          )}
          <fieldset
            disabled={!zone.manage || zone.busy || zone.scriptedLocked}
            className="space-y-4"
          >
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
                  onChange={(e) => {
                    const type = e.target.value as SevenDaysZone['type'];
                    change({
                      ...zonePreset(type),
                      schedule: type === 'prison' ? { ...z.schedule, enabled: false } : z.schedule,
                      movement: {
                        ...z.movement,
                        mode:
                          type === 'restricted' ||
                          type === 'portal' ||
                          type === 'prison' ||
                          type === 'event'
                            ? type
                            : 'none',
                      },
                    });
                  }}
                >
                  {SEVENDAYS_ZONE_TYPES.map((type) => (
                    <option
                      key={type}
                      value={type}
                      disabled={
                        !zone.commandsManage &&
                        ['restricted', 'portal', 'prison', 'event'].includes(type)
                      }
                    >
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
            <details
              className="border-t border-border pt-3"
              open={z.schedule.enabled ? true : undefined}
            >
              <summary className="cursor-pointer text-sm font-semibold">
                {t('sdtd.zones.schedule.title')}
              </summary>
              <div className="mt-3 space-y-3">
                <p className="max-w-prose text-xs text-muted">
                  {t(
                    z.movement.mode === 'prison'
                      ? 'sdtd.zones.schedule.prison'
                      : 'sdtd.zones.schedule.help',
                  )}
                </p>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    disabled={z.movement.mode === 'prison'}
                    checked={z.schedule.enabled}
                    onChange={(e) =>
                      change({ schedule: { ...z.schedule, enabled: e.target.checked } })
                    }
                  />
                  {t('sdtd.zones.schedule.enabled')}
                </label>
                {z.schedule.enabled && z.movement.mode !== 'prison' && (
                  <fieldset className="space-y-3">
                    <label className="block space-y-1 text-sm">
                      {t('sdtd.zones.schedule.offset')}
                      <select
                        className={selectClass}
                        aria-label={t('sdtd.zones.schedule.offset')}
                        value={z.schedule.offsetMinutes}
                        onChange={(e) =>
                          change({
                            schedule: { ...z.schedule, offsetMinutes: Number(e.target.value) },
                          })
                        }
                      >
                        {Array.from({ length: 105 }, (_, i) => i * 15 - 720).map((offset) => (
                          <option key={offset} value={offset}>
                            {zoneOffset(offset)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <p className="max-w-prose text-xs text-muted">
                      {t('sdtd.zones.schedule.offsetHelp')}
                    </p>
                    <fieldset>
                      <legend className="mb-2 text-sm font-semibold">
                        {t('sdtd.zones.schedule.days')}
                      </legend>
                      <div className="flex flex-wrap gap-x-4 gap-y-2">
                        {['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'].map((day, index) => (
                          <label key={day} className="flex items-center gap-2 text-sm">
                            <input
                              type="checkbox"
                              checked={(z.schedule.days & (1 << index)) !== 0}
                              onChange={(e) =>
                                change({
                                  schedule: {
                                    ...z.schedule,
                                    days: e.target.checked
                                      ? z.schedule.days | (1 << index)
                                      : z.schedule.days & ~(1 << index),
                                  },
                                })
                              }
                            />
                            {t(`sdtd.zones.schedule.${day}`)}
                          </label>
                        ))}
                      </div>
                    </fieldset>
                    {z.schedule.days === 0 && (
                      <p role="status" className="text-sm text-amber-400">
                        {t('sdtd.zones.schedule.noDays')}
                      </p>
                    )}
                    <div className="grid gap-3 sm:grid-cols-2">
                      {(['fromMinute', 'toMinute'] as const).map((key) => (
                        <label key={key} className="min-w-0 space-y-1 text-sm">
                          {t(`sdtd.zones.schedule.${key}`)}
                          <Input
                            type="time"
                            required
                            step={60}
                            value={
                              Number.isFinite(z.schedule[key]) ? zoneClock(z.schedule[key]) : ''
                            }
                            onChange={(e) => {
                              const [hour = NaN, minute = NaN] = e.target.value
                                .split(':')
                                .map(Number);
                              change({ schedule: { ...z.schedule, [key]: hour * 60 + minute } });
                            }}
                          />
                        </label>
                      ))}
                    </div>
                    <p className="max-w-prose text-xs text-muted">
                      {t('sdtd.zones.schedule.hoursHelp')}
                    </p>
                    <div className="grid gap-3 sm:grid-cols-2">
                      {(['start', 'end'] as const).map((key) => (
                        <label key={key} className="min-w-0 space-y-1 text-sm">
                          {t(`sdtd.zones.schedule.${key}`)}
                          <Input
                            type="datetime-local"
                            step={60}
                            min="1970-01-02T00:00"
                            max="9999-12-30T23:59"
                            value={zoneDate(z.schedule[key], z.schedule.offsetMinutes)}
                            onChange={(e) =>
                              change({
                                schedule: {
                                  ...z.schedule,
                                  [key]: zoneDateUtc(e.target.value, z.schedule.offsetMinutes),
                                },
                              })
                            }
                          />
                        </label>
                      ))}
                    </div>
                    <p className="max-w-prose text-xs text-muted">
                      {t('sdtd.zones.schedule.datesHelp')}
                    </p>
                    <p className="max-w-prose text-xs text-muted">
                      {t('sdtd.zones.schedule.transitions')}
                    </p>
                  </fieldset>
                )}
              </div>
            </details>
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
            <fieldset className="space-y-2">
              <legend className="mb-2 text-sm font-semibold">
                {t('sdtd.zones.blockProtection')}
              </legend>
              {(['noCreatureBlockDamage', 'noExplosionBlockDamage'] as const).map((key) => (
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
              <p className="max-w-prose text-xs text-muted">
                {t('sdtd.zones.blockProtectionHelp')}
              </p>
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
            <fieldset className="space-y-3 rounded border border-border p-3">
              <legend className="px-1 text-sm font-medium">{t('sdtd.zones.bonus')}</legend>
              <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.bonus.help')}</p>
              <div className="grid gap-3 sm:grid-cols-3">
                {SEVENDAYS_ZONE_BONUSES.map((b) => (
                  <label key={b} className="space-y-1 text-sm">
                    {t(`sdtd.zones.bonus.${b}`)}
                    <Input
                      type="number"
                      min={0}
                      max={SEVENDAYS_ZONE_BONUS_MAX[b]}
                      step="any"
                      required
                      value={Number.isNaN(z.bonuses[b]) ? '' : z.bonuses[b]}
                      onChange={(e) =>
                        change({ bonuses: { ...z.bonuses, [b]: e.target.valueAsNumber } })
                      }
                    />
                  </label>
                ))}
              </div>
            </fieldset>
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
            <details
              className="border-t border-border pt-3"
              open={z.movement.mode !== 'none' ? true : undefined}
            >
              <summary className="cursor-pointer text-sm font-semibold">
                {t('sdtd.zones.movement')}
              </summary>
              <fieldset disabled={!zone.commandsManage} className="mt-3 space-y-3">
                <label className="block space-y-1 text-sm">
                  {t('sdtd.zones.movementMode')}
                  <select
                    className={selectClass}
                    value={z.movement.mode}
                    aria-label={t('sdtd.zones.movementMode')}
                    onChange={(e) =>
                      change({
                        movement: {
                          ...z.movement,
                          mode: e.target.value as SevenDaysZone['movement']['mode'],
                        },
                        schedule:
                          e.target.value === 'prison'
                            ? { ...z.schedule, enabled: false }
                            : z.schedule,
                      })
                    }
                  >
                    {(['none', 'restricted', 'portal', 'prison', 'event'] as const).map((mode) => (
                      <option key={mode} value={mode}>
                        {t(`sdtd.zones.movement.${mode}`)}
                      </option>
                    ))}
                  </select>
                </label>
                {z.movement.mode !== 'none' && (
                  <>
                    <p className="max-w-prose text-xs text-muted">
                      {t(`sdtd.zones.movementHelp.${z.movement.mode}`)}
                    </p>
                    {!containment && (
                      <div className="grid gap-3 sm:grid-cols-2">
                        {(['minLevel', 'maxLevel'] as const).map((key) => (
                          <label key={key} className="block space-y-1 text-sm">
                            {t(`sdtd.zones.${key}`)}
                            <Input
                              required
                              type="number"
                              min={0}
                              max={10000}
                              step={1}
                              value={Number.isFinite(z.movement[key]) ? z.movement[key] : ''}
                              onChange={(e) =>
                                change({
                                  movement: { ...z.movement, [key]: e.target.valueAsNumber },
                                })
                              }
                            />
                          </label>
                        ))}
                      </div>
                    )}
                    {z.movement.mode === 'prison' ? (
                      <fieldset className="space-y-3">
                        <legend className="mb-2 text-sm font-semibold">
                          {t('sdtd.zones.prisoners')}
                        </legend>
                        <p className="max-w-prose text-xs text-muted">
                          {t('sdtd.zones.sentencesHelp')}
                        </p>
                        {z.movement.sentences.length === 0 && (
                          <p className="text-sm text-muted">{t('sdtd.zones.noPrisoners')}</p>
                        )}
                        {z.movement.sentences.map((sentence) => (
                          <div
                            key={sentence.player}
                            className="space-y-2 border-b border-border pb-3"
                          >
                            <p className="break-all text-sm">
                              {players.find((p) => p.id === sentence.player)?.name ??
                                sentence.player}
                            </p>
                            <div className="flex flex-wrap items-end gap-2">
                              <label className="min-w-0 flex-1 space-y-1 text-sm">
                                {t('sdtd.zones.releaseAt')}
                                <Input
                                  type="datetime-local"
                                  aria-label={`${t('sdtd.zones.releaseAt')} ${sentence.player}`}
                                  value={
                                    sentence.until
                                      ? new Date(
                                          sentence.until * 1000 -
                                            new Date(sentence.until * 1000).getTimezoneOffset() *
                                              60000,
                                        )
                                          .toISOString()
                                          .slice(0, 16)
                                      : ''
                                  }
                                  onChange={(e) =>
                                    change({
                                      movement: {
                                        ...z.movement,
                                        sentences: z.movement.sentences.map((s) =>
                                          s.player !== sentence.player
                                            ? s
                                            : {
                                                ...s,
                                                until: e.target.value
                                                  ? Math.floor(
                                                      new Date(e.target.value).getTime() / 1000,
                                                    )
                                                  : 0,
                                              },
                                        ),
                                      },
                                    })
                                  }
                                />
                              </label>
                              <Button
                                type="button"
                                variant="outline"
                                onClick={() =>
                                  change({
                                    movement: {
                                      ...z.movement,
                                      sentences: z.movement.sentences.filter(
                                        (s) => s.player !== sentence.player,
                                      ),
                                    },
                                  })
                                }
                              >
                                {t('sdtd.zones.release')}
                              </Button>
                            </div>
                          </div>
                        ))}
                        <div className="flex flex-wrap items-end gap-2">
                          <label className="min-w-0 flex-1 space-y-1 text-sm">
                            {t('sdtd.zones.prisonerId')}
                            <Input
                              maxLength={121}
                              value={prisonerId}
                              onChange={(e) => setPrisonerId(e.target.value)}
                            />
                          </label>
                          <Button
                            type="button"
                            variant="outline"
                            disabled={
                              z.movement.sentences.length >= 64 ||
                              !/^[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}$/.test(
                                prisonerId.trim(),
                              ) ||
                              z.movement.sentences.some((s) => s.player === prisonerId.trim())
                            }
                            onClick={() => addPrisoner(prisonerId.trim())}
                          >
                            {t('sdtd.zones.addPrisoner')}
                          </Button>
                        </div>
                      </fieldset>
                    ) : (
                      <label className="block space-y-1 text-sm">
                        {t(containment ? 'sdtd.zones.participants' : 'sdtd.zones.allowedPlayers')}
                        <textarea
                          rows={3}
                          aria-label={t(
                            containment ? 'sdtd.zones.participants' : 'sdtd.zones.allowedPlayers',
                          )}
                          maxLength={7808}
                          className={`${selectClass} font-mono`}
                          value={z.movement.players.join('\n')}
                          onChange={(e) =>
                            change({
                              movement: { ...z.movement, players: e.target.value.split(/\r?\n/) },
                            })
                          }
                        />
                      </label>
                    )}
                    <label className="block space-y-1 text-sm">
                      {t('sdtd.zones.addOnlinePlayer')}
                      <select
                        className={selectClass}
                        aria-label={t('sdtd.zones.addOnlinePlayer')}
                        value=""
                        onChange={(e) => {
                          if (z.movement.mode === 'prison') addPrisoner(e.target.value);
                          else if (e.target.value)
                            change({
                              movement: {
                                ...z.movement,
                                players: [...z.movement.players.filter(Boolean), e.target.value],
                              },
                            });
                        }}
                      >
                        <option value="">{t('sdtd.zones.chooseOnlinePlayer')}</option>
                        {players
                          .filter((p) =>
                            z.movement.mode === 'prison'
                              ? !z.movement.sentences.some((s) => s.player === p.id)
                              : !z.movement.players.includes(p.id),
                          )
                          .map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.name}
                            </option>
                          ))}
                      </select>
                    </label>
                    {!containment && (
                      <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.accessHelp')}</p>
                    )}
                    <fieldset className="space-y-2">
                      <legend className="mb-2 text-sm font-semibold">
                        {t(
                          containment
                            ? 'sdtd.zones.internalReturn'
                            : z.movement.mode === 'restricted'
                              ? 'sdtd.zones.returnPoint'
                              : 'sdtd.zones.destination',
                        )}
                      </legend>
                      <div className="grid grid-cols-3 gap-3">
                        {(['x', 'y', 'z'] as const).map((key) => (
                          <label key={key} className="block space-y-1 text-sm">
                            {key.toUpperCase()}
                            <Input
                              required
                              type="number"
                              min={key === 'y' ? 2 : -500000}
                              max={key === 'y' ? 251 : 500000}
                              step={1}
                              value={Number.isFinite(z.movement[key]) ? z.movement[key] : ''}
                              onChange={(e) =>
                                change({
                                  movement: { ...z.movement, [key]: e.target.valueAsNumber },
                                })
                              }
                            />
                          </label>
                        ))}
                      </div>
                      <p className="max-w-prose text-xs text-muted">
                        {t(
                          containment
                            ? 'sdtd.zones.internalReturnHelp'
                            : 'sdtd.zones.destinationHelp',
                        )}
                      </p>
                    </fieldset>
                    <div className="grid gap-3 sm:grid-cols-2">
                      {(['priority', 'cooldown'] as const).map((key) => (
                        <label key={key} className="block space-y-1 text-sm">
                          {t(`sdtd.zones.movement.${key}`)}
                          <Input
                            required
                            type="number"
                            min={key === 'priority' ? -1000 : 10}
                            max={key === 'priority' ? 1000 : 86400}
                            step={1}
                            value={Number.isFinite(z.movement[key]) ? z.movement[key] : ''}
                            onChange={(e) =>
                              change({ movement: { ...z.movement, [key]: e.target.valueAsNumber } })
                            }
                          />
                        </label>
                      ))}
                    </div>
                    {containment && (
                      <fieldset className="space-y-2">
                        {(['dismount', 'kickOnFailure'] as const).map((key) => (
                          <label key={key} className="flex items-start gap-2 text-sm">
                            <input
                              className="mt-1"
                              type="checkbox"
                              checked={z.movement[key]}
                              onChange={(e) =>
                                change({ movement: { ...z.movement, [key]: e.target.checked } })
                              }
                            />
                            {t(`sdtd.zones.${key}`)}
                          </label>
                        ))}
                        <p className="max-w-prose text-xs text-muted">
                          {t('sdtd.zones.failureHelp')}
                        </p>
                      </fieldset>
                    )}
                    <label className="block space-y-1 text-sm">
                      {t('sdtd.zones.movementMessage')}
                      <Input
                        maxLength={240}
                        value={z.movement.message}
                        onChange={(e) =>
                          change({ movement: { ...z.movement, message: e.target.value } })
                        }
                      />
                    </label>
                    <p className="max-w-prose text-xs text-muted">
                      {t(
                        containment ? 'sdtd.zones.containmentLimits' : 'sdtd.zones.movementLimits',
                      )}
                    </p>
                  </>
                )}
              </fieldset>
            </details>
            <details className="border-t border-border pt-3">
              <summary className="cursor-pointer text-sm font-semibold">
                {t('sdtd.zones.commands')}
              </summary>
              <fieldset disabled={!zone.commandsManage} className="mt-3 space-y-3">
                <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.commandsHelp')}</p>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={z.commandsEnabled}
                    onChange={(e) => change({ commandsEnabled: e.target.checked })}
                  />
                  {t('sdtd.zones.commandsEnabled')}
                </label>
                <label className="block max-w-xs space-y-1 text-sm">
                  {t('sdtd.zones.commandCooldown')}
                  <Input
                    type="number"
                    min={10}
                    max={86400}
                    step={1}
                    value={z.commandCooldown}
                    onChange={(e) => change({ commandCooldown: Number(e.target.value) })}
                  />
                </label>
                <div className="grid gap-3 sm:grid-cols-2">
                  {(['enterCommands', 'exitCommands'] as const).map((key) => (
                    <label key={key} className="block space-y-1 text-sm">
                      {t(`sdtd.zones.${key}`)}
                      <textarea
                        rows={4}
                        maxLength={644}
                        className={`${selectClass} font-mono`}
                        value={z[key].join('\n')}
                        onChange={(e) => change({ [key]: e.target.value.split(/\r?\n/) })}
                      />
                    </label>
                  ))}
                </div>
                <p className="max-w-prose whitespace-pre-line text-xs text-muted">
                  {t('sdtd.zones.commandSyntax')}
                </p>
                <p className="max-w-prose text-xs text-muted">{t('sdtd.zones.commandsCaution')}</p>
              </fieldset>
            </details>
          </fieldset>
          <div className="flex flex-wrap gap-2">
            {zone.manage && (
              <Button
                type="submit"
                disabled={zone.busy || zone.scriptedLocked || !zone.dirty || !z.name.trim()}
              >
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
                disabled={zone.busy || zone.scriptedLocked}
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
