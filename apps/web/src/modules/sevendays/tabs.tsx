import { useCallback, useEffect, useRef, useState } from 'react';
import { SevenDaysInventoryPanel } from './InventoryPanel';
import { SevenDaysSavedPlayersPanel } from './SavedPlayersPanel';
import { SevenDaysToolsPanel } from './ToolsPanel';
import {
  SEVENDAYS_BAN_UNITS,
  SEVENDAYS_PERMISSIONS,
  type SevenDaysActionDto,
  type SevenDaysEventDto,
  type SevenDaysPlayerHistory,
  type SevenDaysEventKind,
  type SevenDaysBanDto,
  type SevenDaysBanUnit,
  type SevenDaysPlayerDto,
  type SevenDaysPlayersResponse,
  type SevenDaysStateDto,
  type SevenDaysWhitelistEntryDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import type { ModuleTabProps } from '../registry';
import { useI18n } from '../../i18n';

const base = (serverId: string) => `/api/modules/sevendays/servers/${serverId}`;
type HistoryPlayer = { id: string; name: string; alternateId?: string };

/**
 * Вкладки модуля 7 Days to Die.
 *
 * Их три — по числу возможностей ВАНИЛЬНОГО сервера: игроки, баны и белый
 * список. Консоль даёт ядро. Инвентарь требует companion и отдельного права.
 */

/** Единицы срока бана заданы игрой; названия — для человека. */
const UNIT_LABELS: Record<SevenDaysBanUnit, string> = {
  minutes: 'минут',
  hours: 'часов',
  days: 'дней',
  weeks: 'недель',
  months: 'месяцев',
  years: 'лет',
};

/**
 * Чем адресовать команду для конкретного игрока.
 *
 * Идентификатор платформы переживает выход игрока, а ник — нет: пока человек
 * в сети, работает и то и другое, но бан по нику снимать потом не с чего.
 * Поэтому предпочитаем идентификатор, а к нику откатываемся только если
 * сервер идентификатора не отдал.
 */
function targetOf(player: SevenDaysPlayerDto): string {
  return player.platformId ?? player.crossId ?? player.name;
}

// ---------------------------------------------------------------- Игроки

export function SevenDaysPlayersTab({ serverId, moduleId, capabilityState }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [historyPlayer, setHistoryPlayer] = useState<HistoryPlayer | null>(null);
  const historyTrigger = useRef<HTMLElement | null>(null);
  const restoreHistoryFocus = useRef(false);
  useEffect(() => {
    if (!historyPlayer && restoreHistoryFocus.current) {
      restoreHistoryFocus.current = false;
      const target = historyTrigger.current;
      if (target?.isConnected) target.focus();
      else document.getElementById('sdtd-events-title')?.focus();
    }
  }, [historyPlayer]);
  const showHistory = (player: HistoryPlayer) => {
    historyTrigger.current = document.activeElement as HTMLElement;
    setHistoryPlayer({ ...player });
  };
  const historyPanel = (
    <SevenDaysEventsPanel
      key={`${serverId}/${historyPlayer?.id ?? ''}`}
      serverId={serverId}
      moduleId={moduleId}
      capabilityState={capabilityState}
      player={historyPlayer}
      onPlayer={showHistory}
      onClear={() => {
        restoreHistoryFocus.current = true;
        setHistoryPlayer(null);
      }}
    />
  );
  const [inventoryPlayer, setInventoryPlayer] = useState<{
    id: string;
    name: string;
    entityId?: number;
    saved: boolean;
  } | null>(null);
  const inventoryTrigger = useRef<HTMLElement | null>(null);
  useEffect(() => {
    setInventoryPlayer(null);
    setHistoryPlayer(null);
  }, [serverId]);
  const showInventory = (player: SevenDaysPlayerDto) => {
    inventoryTrigger.current = document.activeElement as HTMLElement | null;
    setInventoryPlayer({
      id: (player.platformId ?? player.crossId)!,
      name: player.name,
      entityId: player.entityId,
      saved: false,
    });
  };
  const [data, setData] = useState<SevenDaysPlayersResponse | null>(null);
  const [state, setState] = useState<SevenDaysStateDto | null>(null);
  const [error, setError] = useState('');
  const [punish, setPunish] = useState<{ player: SevenDaysPlayerDto; kind: 'kick' | 'ban' } | null>(
    null,
  );

  const load = useCallback(() => {
    setError('');
    return Promise.all([
      api<SevenDaysPlayersResponse>(`${base(serverId)}/players`).then(setData),
      api<SevenDaysStateDto>(`${base(serverId)}/state`).then(setState),
    ]).catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
    // Реже, чем в остальных модулях: каждый опрос — это отдельное
    // telnet-подключение к игровому серверу, а не запрос к HTTP API.
    const timer = setInterval(() => void load(), 30000);
    return () => clearInterval(timer);
  }, [load]);

  if (error && !data) {
    return (
      <div className="space-y-4">
        <Card>
          <ErrorText>{error}</ErrorText>
          <Button size="sm" variant="outline" className="mt-3" onClick={() => void load()}>
            Повторить
          </Button>
        </Card>
        {historyPanel}
      </div>
    );
  }
  if (!data) return <Spinner />;

  return (
    <div className="space-y-4">
      {state && <ServerState state={state} />}

      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm text-muted">Онлайн: {data.online}</div>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            Обновить
          </Button>
        </div>

        {data.players.length === 0 ? (
          <p className="text-muted">Сейчас никого нет онлайн.</p>
        ) : (
          <>
            {/* Таблица с md, ниже — карточки: те же данные без прокрутки вбок. */}
            <table className="hidden w-full text-sm md:table">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="pb-2">Игрок</th>
                  <th className="pb-2">Уровень</th>
                  <th className="pb-2">Зомби</th>
                  <th className="pb-2">Смерти</th>
                  <th className="pb-2">Положение</th>
                  <th className="pb-2">Пинг</th>
                  <th className="pb-2 text-right">Действия</th>
                </tr>
              </thead>
              <tbody>
                {data.players.map((p) => (
                  <tr key={p.entityId} className="border-t border-border">
                    <td className="py-2">
                      <div className="font-medium">{p.name}</div>
                      {/* Идентификатор показываем: именно им оперируют кик и
                          бан, и без него непонятно, кого затронет действие. */}
                      <div className="break-all font-mono text-[11px] text-muted">
                        {p.platformId ?? p.crossId ?? `id ${p.entityId}`}
                      </div>
                    </td>
                    <td className="py-2 text-muted">{p.level ?? '—'}</td>
                    <td className="py-2 text-muted">{p.zombieKills ?? '—'}</td>
                    <td className="py-2 text-muted">{p.deaths ?? '—'}</td>
                    <td className="py-2 text-xs text-muted">{formatPosition(p)}</td>
                    <td className="py-2 text-muted">{p.ping !== null ? `${p.ping} мс` : '—'}</td>
                    <td className="py-2 text-right">
                      <PlayerActions
                        player={p}
                        hasPermission={hasPermission}
                        onPunish={setPunish}
                        onInventory={showInventory}
                        onHistory={showHistory}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <ul className="space-y-2 md:hidden">
              {data.players.map((p) => (
                <li key={p.entityId} className="rounded-md border border-border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-medium">{p.name}</span>
                    <span className="shrink-0 text-xs text-muted">
                      {p.ping !== null ? `${p.ping} мс` : ''}
                    </span>
                  </div>
                  <p className="mt-1 break-all font-mono text-[11px] text-muted">
                    {p.platformId ?? p.crossId ?? `id ${p.entityId}`}
                  </p>
                  <p className="mt-1 text-xs text-muted">
                    Уровень {p.level ?? '—'} · зомби {p.zombieKills ?? '—'} · смертей{' '}
                    {p.deaths ?? '—'}
                  </p>
                  <p className="mt-1 text-xs text-muted">{formatPosition(p)}</p>
                  <div className="mt-2">
                    <PlayerActions
                      player={p}
                      hasPermission={hasPermission}
                      onPunish={setPunish}
                      onInventory={showInventory}
                      onHistory={showHistory}
                    />
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}

        {punish && (
          <PunishModal
            serverId={serverId}
            player={punish.player}
            kind={punish.kind}
            onClose={() => setPunish(null)}
            onDone={() => void load()}
          />
        )}
      </Card>

      {hasPermission(SEVENDAYS_PERMISSIONS.inventoryView) && (
        <SevenDaysSavedPlayersPanel
          key={serverId}
          serverId={serverId}
          onHistory={hasPermission(SEVENDAYS_PERMISSIONS.eventsView) ? showHistory : undefined}
          onInventory={(player) => {
            inventoryTrigger.current = document.activeElement as HTMLElement | null;
            setInventoryPlayer({ ...player, saved: true });
          }}
        />
      )}
      <SevenDaysToolsPanel
        key={`tools/${serverId}`}
        serverId={serverId}
        players={data.players}
        onRefreshPlayers={load}
      />
      {inventoryPlayer && hasPermission(SEVENDAYS_PERMISSIONS.inventoryView) && (
        <SevenDaysInventoryPanel
          key={`${serverId}/${inventoryPlayer.id}/${inventoryPlayer.saved}`}
          serverId={serverId}
          playerId={inventoryPlayer.id}
          name={inventoryPlayer.name}
          saved={inventoryPlayer.saved}
          onClose={() => {
            setInventoryPlayer(null);
            const trigger = inventoryTrigger.current;
            const visibleTrigger = trigger?.getClientRects().length
              ? trigger
              : Array.from(
                  document.querySelectorAll<HTMLButtonElement>(
                    `[data-inventory-player="${inventoryPlayer.entityId}"]`,
                  ),
                ).find((button) => button.getClientRects().length > 0);
            visibleTrigger?.focus();
          }}
        />
      )}

      {/*
        Журнал под списком игроков, а не отдельной вкладкой: разбирающий
        жалобу уже здесь — смотрит, кто сейчас в сети, — и ему нужно то же
        самое, только за прошедшее время. Отдельная вкладка заставила бы
        ходить туда-сюда.
      */}
      {historyPanel}
    </div>
  );
}

/** Координаты: у 7 Days to Die их три, и высота значима — подземелья и башни. */
function formatPosition(player: SevenDaysPlayerDto): string {
  if (!player.position) return '—';
  const { x, y, z } = player.position;
  return `${Math.round(x)}, ${Math.round(y)}, ${Math.round(z)}`;
}

function PlayerActions({
  player,
  hasPermission,
  onPunish,
  onInventory,
  onHistory,
}: {
  player: SevenDaysPlayerDto;
  hasPermission: (key: string) => boolean;
  onPunish: (value: { player: SevenDaysPlayerDto; kind: 'kick' | 'ban' }) => void;
  onInventory: (player: SevenDaysPlayerDto) => void;
  onHistory: (player: HistoryPlayer) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-wrap justify-end gap-2">
      {hasPermission(SEVENDAYS_PERMISSIONS.eventsView) && (player.platformId || player.crossId) && (
        <Button
          size="sm"
          variant="outline"
          onClick={() =>
            onHistory({
              id: (player.platformId ?? player.crossId)!,
              name: player.name,
              alternateId: player.crossId ?? undefined,
            })
          }
        >
          {t('sdtd.history.open')}
        </Button>
      )}
      {hasPermission(SEVENDAYS_PERMISSIONS.inventoryView) &&
        (player.platformId || player.crossId) && (
          <Button
            size="sm"
            variant="outline"
            aria-controls="sdtd-inventory"
            data-inventory-player={player.entityId}
            onClick={() => onInventory(player)}
          >
            {t('sdtd.inventory.title')}
          </Button>
        )}
      {hasPermission(SEVENDAYS_PERMISSIONS.kick) && (
        <Button size="sm" variant="outline" onClick={() => onPunish({ player, kind: 'kick' })}>
          Кик
        </Button>
      )}
      {hasPermission(SEVENDAYS_PERMISSIONS.ban) && (
        <Button size="sm" variant="destructive" onClick={() => onPunish({ player, kind: 'ban' })}>
          Бан
        </Button>
      )}
    </div>
  );
}

/**
 * Состояние сервера.
 *
 * Аналога TPS у 7 Days to Die нет — сервер такого показателя не отдаёт, и
 * рисовать пустую полосу вместо него панель не станет. Зато есть игровой
 * день, а он здесь важнее: каждый седьмой день приходит орда, и «сколько
 * осталось» — первое, что смотрит дежурный.
 */
function ServerState({ state }: { state: SevenDaysStateDto }) {
  const { t } = useI18n();
  if (!state.available) {
    return <Card className="text-xs text-muted">{state.reason}</Card>;
  }

  const fromMod = state.source === 'companion';
  const bloodMoonNow = fromMod ? state.bloodMoonActive === true : state.daysToBloodMoon === 0;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">
            {t('sdtd.state.day')}
          </div>
          <div className="text-sm font-semibold">{state.day ?? '—'}</div>
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">
            {t('sdtd.state.time')}
          </div>
          <div className="text-sm font-semibold">{state.time ?? '—'}</div>
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">
            {t('sdtd.state.bloodMoon')}
          </div>
          <div className={`text-sm font-semibold ${bloodMoonNow ? 'text-red-400' : ''}`}>
            {bloodMoonNow
              ? fromMod
                ? t('sdtd.state.active')
                : t('sdtd.state.tonight')
              : fromMod && state.bloodMoonFrequency === 0
                ? t('sdtd.state.disabled')
                : state.daysToBloodMoon === null || state.daysToBloodMoon === undefined
                  ? t('sdtd.state.unknown')
                  : state.daysToBloodMoon === 0
                    ? t('sdtd.state.tonight')
                    : t('sdtd.state.inDays', { days: state.daysToBloodMoon })}
          </div>
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">
            {t('sdtd.state.online')}
          </div>
          <div className="text-sm font-semibold">
            {state.onlineCount ?? '—'}
            {fromMod && state.maxPlayers ? ` / ${state.maxPlayers}` : ''}
          </div>
        </div>

        {/* Показатели, которые есть только у мода: консоль их не отдаёт. */}
        {fromMod && (
          <>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">
                {t('sdtd.state.fps')}
              </div>
              <div
                className={`text-sm font-semibold ${
                  typeof state.fps === 'number' && state.fps < 20 ? 'text-red-400' : ''
                }`}
              >
                {typeof state.fps === 'number' ? Math.round(state.fps) : '—'}
              </div>
            </div>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">
                {t('sdtd.state.zombies')}
              </div>
              <div className="text-sm font-semibold">
                {state.zombies ?? '—'}
                {state.maxZombies ? ` / ${state.maxZombies}` : ''}
              </div>
            </div>
          </>
        )}

        <div className="min-w-0">
          <div className="text-[11px] uppercase tracking-wide text-muted">
            {t('sdtd.state.version')}
          </div>
          <div className="truncate text-sm font-semibold">{state.version ?? '—'}</div>
        </div>
      </div>

      {/*
        Откуда взяты цифры — не мелочь. Через консоль «до кровавой луны» это
        расчёт по номеру дня, а частота орды настраивается и через консоль не
        читается. Показать догадку как факт значит однажды подвести дежурного.
      */}
      <p className="text-[11px] text-muted">
        {fromMod ? (
          <>
            {t('sdtd.state.fromMod')}
            {typeof state.bloodMoonFrequency === 'number' && state.bloodMoonFrequency > 0
              ? ` ${t(
                  (state.bloodMoonRange ?? 0) > 0
                    ? 'sdtd.state.intervalRange'
                    : 'sdtd.state.interval',
                  {
                    days: state.bloodMoonFrequency,
                    max: state.bloodMoonFrequency + (state.bloodMoonRange ?? 0),
                  },
                )}`
              : ''}
          </>
        ) : (
          t('sdtd.state.fromConsole')
        )}
      </p>
    </Card>
  );
}

/** Кик или бан. У бана есть срок: временные баны игра поддерживает. */
function PunishModal({
  serverId,
  player,
  kind,
  onClose,
  onDone,
}: {
  serverId: string;
  player: SevenDaysPlayerDto;
  kind: 'kick' | 'ban';
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const [duration, setDuration] = useState('7');
  const [unit, setUnit] = useState<SevenDaysBanUnit>('days');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    try {
      await api(`${base(serverId)}/players/${kind}`, {
        method: 'POST',
        body: JSON.stringify({
          target: targetOf(player),
          reason: reason.trim(),
          ...(kind === 'ban' ? { duration: Number(duration), unit } : {}),
        }),
      });
      onDone();
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const durationOk = kind === 'kick' || (Number.isInteger(+duration) && +duration >= 1);

  return (
    <Modal title={`${kind === 'kick' ? 'Кик' : 'Бан'} игрока ${player.name}`} onClose={onClose}>
      <div className="space-y-3">
        <p className="break-all font-mono text-[11px] text-muted">{targetOf(player)}</p>

        {kind === 'ban' && (
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Срок</Label>
              <Input
                value={duration}
                onChange={(e) => setDuration(e.target.value)}
                inputMode="numeric"
                autoFocus
              />
            </div>
            <div>
              <Label>Единица</Label>
              <Select
                value={unit}
                onChange={(v) => setUnit(v as SevenDaysBanUnit)}
                options={SEVENDAYS_BAN_UNITS.map((u) => ({ value: u, label: UNIT_LABELS[u] }))}
              />
            </div>
          </div>
        )}

        <div>
          <Label>Причина (увидит игрок)</Label>
          <Input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={kind === 'kick' ? 'Нарушение правил' : 'Разрушение чужой базы'}
            autoFocus={kind === 'kick'}
          />
        </div>

        {kind === 'ban' && (
          <p className="text-xs text-muted">
            Бессрочного бана в 7 Days to Die нет — «навсегда» здесь выражается большим сроком,
            например 100 лет. Снять бан можно на вкладке «Баны».
          </p>
        )}

        <ErrorText>{error}</ErrorText>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Отмена
          </Button>
          <Button
            variant="destructive"
            onClick={() => void submit()}
            disabled={busy || !durationOk}
          >
            {kind === 'kick' ? 'Кикнуть' : 'Забанить'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ Баны

export function SevenDaysBansTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [bans, setBans] = useState<SevenDaysBanDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState('');

  const load = useCallback(() => {
    setError('');
    return api<SevenDaysBanDto[]>(`${base(serverId)}/bans`)
      .then(setBans)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function pardon(target: string) {
    setBusy(target);
    try {
      await api(`${base(serverId)}/bans/pardon`, {
        method: 'POST',
        body: JSON.stringify({ target }),
      });
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  }

  if (!bans && !error) return <Spinner />;

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Баны</h2>
        <Button size="sm" variant="outline" onClick={() => void load()}>
          Обновить
        </Button>
      </div>

      <p className="mb-3 text-xs text-muted">
        Список ведёт сам игровой сервер — панель его только показывает. Поэтому здесь виден бан,
        выданный и из игровой консоли тоже, но не видно, кто из персонала его выдал: игра такого не
        хранит. Кто нажал кнопку в панели, видно в журнале действий.
      </p>

      <ErrorText>{error}</ErrorText>

      {bans && bans.length === 0 ? (
        <p className="text-muted">Банов нет.</p>
      ) : (
        <ul className="space-y-2">
          {bans?.map((b) => (
            <li key={b.id} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate font-medium">{b.displayName ?? 'имя неизвестно'}</div>
                  <div className="break-all font-mono text-[11px] text-muted">{b.id}</div>
                </div>
                <Badge variant="destructive">активен</Badge>
              </div>
              {b.reason && <p className="mt-1 break-words text-sm text-muted">{b.reason}</p>}
              <p className="mt-1 text-xs text-muted">
                {b.until ? `до ${b.until}` : 'срок не указан'}
              </p>
              {hasPermission(SEVENDAYS_PERMISSIONS.pardon) && (
                <Button
                  size="sm"
                  variant="outline"
                  className="mt-2 w-full sm:w-auto"
                  disabled={busy === b.id}
                  onClick={() => void pardon(b.id)}
                >
                  Снять бан
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

// ------------------------------------------------------------- Whitelist

export function SevenDaysWhitelistTab({ serverId }: ModuleTabProps) {
  const [entries, setEntries] = useState<SevenDaysWhitelistEntryDto[] | null>(null);
  const [target, setTarget] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setError('');
    return api<SevenDaysWhitelistEntryDto[]>(`${base(serverId)}/whitelist`)
      .then(setEntries)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function change(method: 'POST' | 'DELETE', value: string) {
    setBusy(true);
    setError('');
    try {
      await api(`${base(serverId)}/whitelist`, {
        method,
        body: JSON.stringify({ target: value }),
      });
      setTarget('');
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!entries && !error) return <Spinner />;

  const empty = entries !== null && entries.length === 0;

  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">Белый список</h2>

      {/* Это не украшение, а предупреждение о необратимом на вид действии:
          пока список пуст, он не работает, и первый же добавленный игрок
          закрывает сервер для всех остальных. */}
      <p className={`text-xs ${empty ? 'text-amber-400' : 'text-muted'}`}>
        {empty
          ? 'Список пуст, поэтому не действует: пускают всех. Как только в нём появится хотя бы один игрок, сервер закроется для всех, кого в списке нет.'
          : 'Список не пуст — на сервер пускают только тех, кто в нём есть.'}
      </p>

      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          onKeyDown={(e) =>
            e.key === 'Enter' && target.trim() && void change('POST', target.trim())
          }
          placeholder="Ник или Steam_7656…"
        />
        <Button
          className="sm:w-auto"
          disabled={busy || target.trim() === ''}
          onClick={() => void change('POST', target.trim())}
        >
          Добавить
        </Button>
      </div>

      <ErrorText>{error}</ErrorText>

      {entries && entries.length > 0 && (
        <ul className="space-y-2">
          {entries.map((e) => (
            <li
              key={e.id}
              className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border p-3"
            >
              <div className="min-w-0">
                <div className="truncate font-medium">{e.displayName ?? 'имя неизвестно'}</div>
                <div className="break-all font-mono text-[11px] text-muted">{e.id}</div>
              </div>
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => void change('DELETE', e.id)}
              >
                Убрать
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

// ------------------------------------------------- Быстрые действия (виджет)

export function SevenDaysQuickActionsWidget({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const [actions, setActions] = useState<SevenDaysActionDto[] | null>(null);
  const [active, setActive] = useState<SevenDaysActionDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api<{ actions: SevenDaysActionDto[] }>(`${base(serverId)}/actions`)
      .then((r) => setActions(r.actions))
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  async function run(action: SevenDaysActionDto, values: Record<string, string>) {
    setBusy(true);
    setError('');
    try {
      const path = `${base(serverId)}/actions/${action.id}`;
      const res = await api<{ ok: boolean; message: string }>(path, {
        method: 'POST',
        body: JSON.stringify({ args: values }),
      });
      toast.success(res.message);
      setActive(null);
      setArgs({});
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!actions) return null;

  // Показываем только то, на что у роли есть право: кнопка, ведущая в 403,
  // хуже, чем её отсутствие.
  const allowed = actions.filter((a) => a.id !== 'shutdown' && hasPermission(a.permission));
  if (allowed.length === 0) return null;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-sm text-muted">Действия сервера:</span>
        {allowed.map((a) => (
          <Button
            key={a.id}
            size="sm"
            variant={a.destructive && a.args.length === 0 ? 'destructive' : 'outline'}
            title={a.description}
            disabled={busy}
            onClick={() => {
              if (a.args.length > 0) {
                setArgs({});
                setActive(a);
                return;
              }
              if (a.destructive && !confirm(`${a.description}\n\nВыполнить?`)) return;
              void run(a, {});
            }}
          >
            {a.label}
          </Button>
        ))}
      </div>

      <ErrorText>{error}</ErrorText>

      {active && (
        <Modal title={active.label} onClose={() => setActive(null)}>
          <div className="space-y-3">
            <p className="text-xs text-muted">{active.description}</p>
            {active.args.map((field, index) => (
              <div key={field.name}>
                <Label>{field.label}</Label>
                <Input
                  value={args[field.name] ?? ''}
                  onChange={(e) => setArgs((prev) => ({ ...prev, [field.name]: e.target.value }))}
                  placeholder={field.placeholder}
                  autoFocus={index === 0}
                />
              </div>
            ))}
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button variant="ghost" onClick={() => setActive(null)} disabled={busy}>
                Отмена
              </Button>
              <Button
                variant={active.destructive ? 'destructive' : 'default'}
                onClick={() => void run(active, args)}
                disabled={busy}
              >
                Выполнить
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Card>
  );
}

// -------------------------------------------------- Журнал событий (виджет)

const EVENT_FILTERS: (SevenDaysEventKind | '')[] = [
  '',
  'chat',
  'player-kill',
  'death',
  'join',
  'leave',
];

/**
 * Лента событий игры.
 *
 * Существует только при установленном моде: сама игра событий нигде не
 * хранит — они происходят и исчезают. Смысл ленты именно в разборе задним
 * числом, поэтому по умолчанию показывается всё подряд, а не только
 * «интересное»: что окажется интересным, заранее неизвестно.
 */
export function SevenDaysEventsPanel({
  serverId,
  player,
  onPlayer,
  onClear,
}: ModuleTabProps & {
  player?: HistoryPlayer | null;
  onPlayer?: (player: HistoryPlayer) => void;
  onClear?: () => void;
}) {
  const { hasPermission } = useAuth();
  const { t, locale } = useI18n();
  const [expanded, setExpanded] = useState(!!player);
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (player) heading.current?.focus();
  }, [player]);
  const cursor = cursors[cursors.length - 1];
  const [refresh, setRefresh] = useState(0);
  const [events, setEvents] = useState<SevenDaysEventDto[] | null>(null);
  const [kind, setKind] = useState('');
  const [error, setError] = useState('');

  const allowed = hasPermission(SEVENDAYS_PERMISSIONS.eventsView);
  const invalidHistoryMessage = t('sdtd.history.invalid');

  const load = useCallback(
    (filter: string, signal?: AbortSignal) => {
      if (!allowed) return Promise.resolve();
      setError('');
      setEvents(null);
      setNextCursor(null);
      const qs = `?limit=5${filter ? `&kind=${encodeURIComponent(filter)}` : ''}`;
      const url = player
        ? `${base(serverId)}/players/${encodeURIComponent(player.id)}/history?kind=${encodeURIComponent(filter)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${player.alternateId ? `&alternateId=${encodeURIComponent(player.alternateId)}` : ''}`
        : `${base(serverId)}/events${qs}`;
      return api<SevenDaysEventDto[] | SevenDaysPlayerHistory>(url, { signal })
        .then((result) => {
          if (signal?.aborted) return;
          if (player && !Array.isArray(result)) {
            setEvents(result.events.slice(0, 10));
            setNextCursor(result.nextCursor);
          } else if (Array.isArray(result)) setEvents(result.slice(0, 5));
          else throw new Error(invalidHistoryMessage);
        })
        .catch((e: Error) => {
          if (!signal?.aborted) setError(e.message);
        });
    },
    [serverId, allowed, player, cursor, invalidHistoryMessage],
  );

  useEffect(() => {
    if (!expanded) return;
    const abort = new AbortController();
    void load(kind, abort.signal);
    return () => abort.abort();
  }, [load, kind, expanded, refresh]);

  if (!allowed) return null;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="sdtd-events-title"
          ref={heading}
          tabIndex={-1}
          className="min-w-0 break-words font-semibold"
        >
          {player ? `${t('sdtd.history.open')} · ${player.name}` : t('sdtd.events.title')}
        </h2>
        {player && (
          <Button size="sm" variant="ghost" onClick={onClear}>
            {t('sdtd.history.clear')}
          </Button>
        )}
        <Button
          size="sm"
          variant="outline"
          aria-expanded={expanded}
          aria-controls="sdtd-events-content"
          onClick={() => setExpanded((value) => !value)}
        >
          {t(expanded ? 'sdtd.events.collapse' : 'sdtd.events.expand')}
        </Button>
      </div>
      {expanded && (
        <div id="sdtd-events-content" className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <p className="mr-auto text-sm text-muted">
              {t(player ? 'sdtd.history.note' : 'sdtd.events.latest')}
            </p>
            <label htmlFor="sdtd-event-kind" className="sr-only">
              {t('sdtd.history.filter')}
            </label>
            <Select
              id="sdtd-event-kind"
              value={kind}
              onChange={(value) => {
                setKind(value);
                setCursors([undefined]);
              }}
              options={EVENT_FILTERS.map((value) => ({
                value,
                label: t(`sdtd.history.kind.${value || 'all'}`),
              }))}
            />
            <Button
              size="sm"
              variant="outline"
              disabled={events === null && !error}
              onClick={() => {
                setCursors([undefined]);
                setRefresh((value) => value + 1);
              }}
            >
              {t('sdtd.inventory.refresh')}
            </Button>
          </div>

          <ErrorText>{error}</ErrorText>

          {events === null && !error ? (
            <Spinner />
          ) : events?.length === 0 ? (
            <p className="text-xs text-muted">{t('sdtd.history.empty')}</p>
          ) : (
            <ul className="space-y-1.5">
              {events?.map((e) => (
                <li key={e.id} className="rounded-md border border-border px-3 py-2 text-sm">
                  <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                    <Badge variant={e.kind === 'player-kill' ? 'destructive' : 'outline'}>
                      {t(`sdtd.history.kind.${e.kind}`)}
                    </Badge>
                    <button
                      type="button"
                      className="break-all text-left font-medium underline decoration-dotted underline-offset-4"
                      title={e.playerId}
                      onClick={() => onPlayer?.({ id: e.playerId, name: e.playerName })}
                    >
                      {e.playerName}
                    </button>
                    {e.kind === 'player-kill' && e.actorName && (
                      <span className="min-w-0 text-muted">
                        {t('sdtd.history.killedBy')}{' '}
                        <button
                          type="button"
                          className="break-all text-left underline decoration-dotted underline-offset-4"
                          title={e.actorId ?? undefined}
                          disabled={!e.actorId}
                          onClick={() =>
                            e.actorId && onPlayer?.({ id: e.actorId, name: e.actorName! })
                          }
                        >
                          {e.actorName}
                        </button>
                      </span>
                    )}
                    <span className="ml-auto text-[11px] text-muted">
                      {new Date(e.occurredAt).toLocaleString(locale)}
                    </span>
                  </div>
                  {e.text && <p className="mt-1 break-words text-muted">{e.text}</p>}
                  {/* Координаты — для разбора жалоб, а не для красоты. */}
                  {e.position && (
                    <p className="mt-1 font-mono text-[11px] text-muted">
                      {Math.round(e.position.x)}, {Math.round(e.position.y)},{' '}
                      {Math.round(e.position.z)}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
          {player && (
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                disabled={!events || cursors.length === 1}
                onClick={() => setCursors((c) => c.slice(0, -1))}
              >
                {t('sdtd.inventory.savedPrevious')}
              </Button>
              <Button
                variant="outline"
                disabled={!events || !nextCursor}
                onClick={() => nextCursor && setCursors((c) => [...c, nextCursor])}
              >
                {t('sdtd.history.older')}
              </Button>
              <span className="break-all font-mono text-xs text-muted">{player.id}</span>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
