import { useCallback, useEffect, useState } from 'react';
import {
  SEVENDAYS_BAN_UNITS,
  SEVENDAYS_PERMISSIONS,
  type SevenDaysActionDto,
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
import type { ModuleTabProps } from '../registry';

const base = (serverId: string) => `/api/modules/sevendays/servers/${serverId}`;

/**
 * Вкладки модуля 7 Days to Die.
 *
 * Их три — по числу возможностей ВАНИЛЬНОГО сервера: игроки, баны и белый
 * список. Консоль даёт ядро. Инвентаря и тикетов здесь нет потому, что на
 * голом сервере их взять неоткуда; с серверным модом они появились бы как
 * 'requires-plugin' — см. оговорку в sevendays.def.ts.
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

export function SevenDaysPlayersTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
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
      <Card>
        <ErrorText>{error}</ErrorText>
        <Button size="sm" variant="outline" className="mt-3" onClick={() => void load()}>
          Повторить
        </Button>
      </Card>
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
                      <PlayerActions player={p} hasPermission={hasPermission} onPunish={setPunish} />
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
                    <PlayerActions player={p} hasPermission={hasPermission} onPunish={setPunish} />
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
}: {
  player: SevenDaysPlayerDto;
  hasPermission: (key: string) => boolean;
  onPunish: (value: { player: SevenDaysPlayerDto; kind: 'kick' | 'ban' }) => void;
}) {
  return (
    <div className="flex flex-wrap justify-end gap-2">
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
  if (!state.available) {
    return <Card className="text-xs text-muted">{state.reason}</Card>;
  }

  const bloodMoonToday = state.daysToBloodMoon === 0;

  return (
    <Card className="flex flex-wrap items-start gap-x-6 gap-y-3">
      <div>
        <div className="text-[11px] uppercase tracking-wide text-muted">День</div>
        <div className="text-sm font-semibold">{state.day ?? '—'}</div>
      </div>
      <div>
        <div className="text-[11px] uppercase tracking-wide text-muted">Время</div>
        <div className="text-sm font-semibold">{state.time ?? '—'}</div>
      </div>
      <div>
        <div className="text-[11px] uppercase tracking-wide text-muted">Кровавая луна</div>
        <div className={`text-sm font-semibold ${bloodMoonToday ? 'text-red-400' : ''}`}>
          {state.daysToBloodMoon === null || state.daysToBloodMoon === undefined
            ? '—'
            : bloodMoonToday
              ? 'сегодня ночью'
              : `через ${state.daysToBloodMoon} дн.`}
        </div>
      </div>
      <div>
        <div className="text-[11px] uppercase tracking-wide text-muted">Онлайн</div>
        <div className="text-sm font-semibold">{state.onlineCount ?? '—'}</div>
      </div>
      <div className="min-w-0">
        <div className="text-[11px] uppercase tracking-wide text-muted">Версия</div>
        <div className="truncate text-sm font-semibold">{state.version ?? '—'}</div>
      </div>
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
          <Button variant="destructive" onClick={() => void submit()} disabled={busy || !durationOk}>
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
        выданный и из игровой консоли тоже, но не видно, кто из персонала его выдал: игра такого
        не хранит. Кто нажал кнопку в панели, видно в журнале действий.
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
          onKeyDown={(e) => e.key === 'Enter' && target.trim() && void change('POST', target.trim())}
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
  const [actions, setActions] = useState<SevenDaysActionDto[] | null>(null);
  const [active, setActive] = useState<SevenDaysActionDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [result, setResult] = useState('');
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
    setResult('');
    try {
      // Остановка сервера — отдельный роут со своим правом, см. контроллер.
      const path =
        action.id === 'shutdown'
          ? `${base(serverId)}/shutdown`
          : `${base(serverId)}/actions/${action.id}`;
      const res = await api<{ ok: boolean; message: string }>(path, {
        method: 'POST',
        body: JSON.stringify({ args: values }),
      });
      setResult(res.message);
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
  const allowed = actions.filter((a) => hasPermission(a.permission));
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

      {result && <p className="text-xs text-emerald-400">{result}</p>}
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
