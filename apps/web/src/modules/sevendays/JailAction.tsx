import { useEffect, useRef, useState } from 'react';
import {
  hasZoneCommands,
  parseSevenDaysZones,
  SEVENDAYS_PERMISSIONS,
  type SevenDaysPlayerDto,
  type SevenDaysPlayersResponse,
  type SevenDaysZones,
} from '@aurum/shared';
import { Modal } from '../../components/Modal';
import { Button, ErrorText, Input, Spinner } from '../../components/ui';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../lib/auth';
import { api } from '../../lib/api';
import { useI18n } from '../../i18n';

export function SevenDaysJailAction({
  serverId,
  disabled,
}: {
  serverId: string;
  disabled?: boolean;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <>
      <Button
        ref={trigger}
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        {t('sdtd.jail.action')}
      </Button>
      {open && (
        <JailModal
          key={serverId}
          serverId={serverId}
          onClose={() => {
            setOpen(false);
            trigger.current?.focus();
          }}
        />
      )}
    </>
  );
}

function JailModal({ serverId, onClose }: { serverId: string; onClose: () => void }) {
  const { t } = useI18n();
  const { hasPermission } = useAuth();
  const toast = useToast();
  const commandsAllowed = hasPermission(SEVENDAYS_PERMISSIONS.zonesCommands);
  const [data, setData] = useState<{ zones: SevenDaysZones; players: SevenDaysPlayerDto[] } | null>(
    null,
  );
  const [refresh, setRefresh] = useState(0);
  const [query, setQuery] = useState('');
  const [playerId, setPlayerId] = useState('');
  const [prisonId, setPrisonId] = useState('');
  const [minutes, setMinutes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const lock = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const base = `/api/modules/sevendays/servers/${serverId}`;
  const idOf = (p: SevenDaysPlayerDto) => p.platformId ?? p.crossId ?? '';

  useEffect(() => {
    const abort = new AbortController();
    setData(null);
    setPlayerId('');
    setPrisonId('');
    setError('');
    void Promise.all([
      api<unknown>(`${base}/zones`, { signal: abort.signal }),
      api<SevenDaysPlayersResponse>(`${base}/players`, { signal: abort.signal }),
    ])
      .then(([rawZones, online]) => {
        if (abort.signal.aborted) return;
        const zones = parseSevenDaysZones(rawZones);
        setData({
          zones,
          players: online.players
            .filter((p) => idOf(p))
            .sort((a, b) => a.name.localeCompare(b.name)),
        });
        const prisons = zones.zones.filter(
          (z) =>
            z.enabled && z.movement.mode === 'prison' && (commandsAllowed || !hasZoneCommands(z)),
        );
        if (prisons.length === 1) setPrisonId(prisons[0]!.id);
      })
      .catch((e: Error) => {
        if (!abort.signal.aborted) setError(e.message);
      });
    return () => abort.abort();
  }, [base, refresh, commandsAllowed]);

  const prisons = data?.zones.zones.filter((z) => z.enabled && z.movement.mode === 'prison') ?? [];
  const prison = prisons.find((z) => z.id === prisonId);
  const player = data?.players.find((p) => idOf(p) === playerId);
  const existing =
    player &&
    prisons.find((z) =>
      z.movement.sentences.some(
        (s) => s.player === player.platformId || s.player === player.crossId,
      ),
    );
  const validDuration =
    minutes === '' || (/^\d+$/.test(minutes) && Number(minutes) >= 1 && Number(minutes) <= 525600);
  const allowedPrison = prison && (commandsAllowed || !hasZoneCommands(prison));
  const canSubmit =
    !!player &&
    !!allowedPrison &&
    !existing &&
    prison!.movement.sentences.length < 64 &&
    validDuration;
  const needle = query.trim().toLocaleLowerCase();
  const matches =
    data?.players.filter((p) => `${p.name} ${idOf(p)}`.toLocaleLowerCase().includes(needle)) ?? [];

  async function submit() {
    if (!canSubmit || !data || !prison || lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    try {
      // Keep the fetched revision: a concurrent map edit must conflict, never be overwritten.
      const next = parseSevenDaysZones({
        ...data.zones,
        zones: data.zones.zones.map((z) =>
          z.id !== prison.id
            ? z
            : {
                ...z,
                movement: {
                  ...z.movement,
                  sentences: [
                    ...z.movement.sentences,
                    {
                      player: playerId,
                      until: minutes ? Math.floor(Date.now() / 1000) + Number(minutes) * 60 : 0,
                    },
                  ],
                },
              },
        ),
      });
      await api(`${base}/zones`, { method: 'PUT', body: JSON.stringify(next) });
      toast.success(t('sdtd.jail.saved'));
      onClose();
    } catch (e) {
      setError(`${(e as Error).message} ${t('sdtd.jail.retry')}`);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }

  return (
    <Modal
      title={t('sdtd.jail.action')}
      onClose={() => {
        if (!lock.current) onClose();
      }}
    >
      <form
        ref={form}
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
        onKeyDown={(e) => {
          // Keep keyboard focus inside this administrative action without changing other modals.
          if (e.key !== 'Tab') return;
          const nodes = Array.from(
            form.current?.querySelectorAll<HTMLElement>(
              'input:not(:disabled), select:not(:disabled), button:not(:disabled)',
            ) ?? [],
          );
          const first = nodes[0],
            last = nodes[nodes.length - 1];
          if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last?.focus();
          } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first?.focus();
          }
        }}
      >
        <p className="text-sm text-muted">{t('sdtd.jail.help')}</p>
        <ErrorText>{error}</ErrorText>
        {!data && !error && <Spinner />}
        <Button
          type="button"
          variant="outline"
          disabled={busy || (!data && !error)}
          onClick={() => setRefresh((n) => n + 1)}
        >
          {t('sdtd.jail.refresh')}
        </Button>
        {data && (
          <fieldset disabled={busy} className="min-w-0 space-y-4">
            <div className="space-y-2">
              <label className="block space-y-1 text-sm">
                {t('sdtd.jail.player')}
                <Input
                  autoFocus
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={t('sdtd.jail.search')}
                  autoComplete="off"
                  spellCheck={false}
                />
              </label>
              <fieldset
                className="max-h-48 space-y-1 overflow-y-auto overscroll-contain"
                aria-label={t('sdtd.jail.player')}
              >
                {matches.map((p) => (
                  <label
                    key={p.entityId}
                    className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md px-2 py-2 hover:bg-white/5"
                  >
                    <input
                      type="radio"
                      name="jail-player"
                      value={idOf(p)}
                      checked={playerId === idOf(p)}
                      onChange={() => setPlayerId(idOf(p))}
                    />
                    <span className="min-w-0 text-sm">
                      <span className="block break-words">{p.name}</span>
                      <span className="block break-all text-xs text-muted">{idOf(p)}</span>
                    </span>
                  </label>
                ))}
                {matches.length === 0 && (
                  <p className="text-sm text-muted">
                    {t(data.players.length ? 'sdtd.jail.noMatch' : 'sdtd.jail.noPlayers')}
                  </p>
                )}
              </fieldset>
              {player && (
                <p className="break-words text-sm" role="status">
                  {t('sdtd.jail.selected')} {player.name}
                </p>
              )}
            </div>
            <label className="block space-y-1 text-sm">
              {t('sdtd.jail.prison')}
              <select
                aria-label={t('sdtd.jail.prison')}
                className="block min-h-11 w-full rounded-md border border-border bg-background px-3 text-base sm:text-sm"
                value={prisonId}
                onChange={(e) => setPrisonId(e.target.value)}
              >
                <option value="">{t('sdtd.jail.choose')}</option>
                {prisons.map((z) => (
                  <option key={z.id} value={z.id} disabled={!commandsAllowed && hasZoneCommands(z)}>
                    {z.name}
                  </option>
                ))}
              </select>
            </label>
            {prisons.length === 0 && (
              <p className="text-sm text-muted">{t('sdtd.jail.noPrisons')}</p>
            )}
            {!commandsAllowed && prisons.some(hasZoneCommands) && (
              <p className="text-sm text-muted">{t('sdtd.jail.scripted')}</p>
            )}
            <label className="block space-y-1 text-sm">
              {t('sdtd.jail.duration')}
              <Input
                type="number"
                min={1}
                max={525600}
                step={1}
                value={minutes}
                onChange={(e) => setMinutes(e.target.value)}
                placeholder={t('sdtd.jail.forever')}
              />
            </label>
            {existing && (
              <p className="text-sm text-warn">
                {t('sdtd.jail.already')} {existing.name}
              </p>
            )}
            {prison && prison.movement.sentences.length >= 64 && (
              <p className="text-sm text-warn">{t('sdtd.jail.full')}</p>
            )}
          </fieldset>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || !canSubmit}>
            {t(busy ? 'sdtd.jail.saving' : 'sdtd.jail.action')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
