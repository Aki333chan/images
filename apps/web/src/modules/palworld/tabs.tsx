import { useCallback, useEffect, useState } from 'react';
import type {
  PalworldBanDto,
  PalworldCommandResultDto,
  PalworldPlayerDto,
  PalworldPlayersResponse,
  PalworldQuickActionDto,
  PalworldServerStateDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { Modal } from '../../components/Modal';
import type { ModuleTabProps } from '../registry';

const base = (serverId: string) => `/api/modules/palworld/servers/${serverId}`;

/**
 * Вкладки модуля Palworld.
 *
 * Вкладок ровно столько, сколько возможностей у REST API игры: игроки и
 * баны. Whitelist, инвентаря и тикетов здесь нет не по недосмотру — их не
 * существует в самой игре, см. комментарий в palworld.def.ts.
 */

// ---------------------------------------------------------------- Игроки

export function PalworldPlayersTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [data, setData] = useState<PalworldPlayersResponse | null>(null);
  const [state, setState] = useState<PalworldServerStateDto | null>(null);
  const [error, setError] = useState('');
  const [punish, setPunish] = useState<{ player: PalworldPlayerDto; kind: 'kick' | 'ban' } | null>(
    null,
  );

  const load = useCallback(() => {
    setError('');
    return Promise.all([
      api<PalworldPlayersResponse>(`${base(serverId)}/players`).then(setData),
      api<PalworldServerStateDto>(`${base(serverId)}/state`).then(setState),
    ]).catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
    // Онлайн меняется часто — тот же интервал, что и в модуле Minecraft.
    const timer = setInterval(() => void load(), 15000);
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
          <div className="text-sm text-muted">
            Онлайн: {data.online}
            {data.max !== null && ` / ${data.max}`}
          </div>
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
                  <th className="pb-2">Положение</th>
                  <th className="pb-2">Пинг</th>
                  <th className="pb-2 text-right">Действия</th>
                </tr>
              </thead>
              <tbody>
                {data.players.map((p) => (
                  <tr key={p.userId ?? p.name} className="border-t border-border">
                    <td className="py-2">
                      <div className="font-medium">{p.name}</div>
                      {/* userId — то, чем оперируют кик и бан. Показываем его:
                          без него непонятно, кого именно затронет действие. */}
                      <div className="break-all font-mono text-[11px] text-muted">
                        {p.userId ?? '—'}
                      </div>
                    </td>
                    <td className="py-2 text-muted">{p.level ?? '—'}</td>
                    <td className="py-2 text-xs text-muted">
                      {p.position
                        ? `${Math.round(p.position.x)}, ${Math.round(p.position.y)}`
                        : '—'}
                    </td>
                    <td className="py-2 text-muted">
                      {p.ping !== null ? `${Math.round(p.ping)} мс` : '—'}
                    </td>
                    <td className="py-2 text-right">
                      <PlayerActions player={p} hasPermission={hasPermission} onPunish={setPunish} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <ul className="space-y-2 md:hidden">
              {data.players.map((p) => (
                <li key={p.userId ?? p.name} className="rounded-md border border-border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-medium">{p.name}</span>
                    <span className="shrink-0 text-xs text-muted">
                      {p.ping !== null ? `${Math.round(p.ping)} мс` : ''}
                    </span>
                  </div>
                  <p className="mt-1 break-all font-mono text-[11px] text-muted">
                    {p.userId ?? 'userId неизвестен'}
                  </p>
                  <p className="mt-1 text-xs text-muted">
                    Уровень {p.level ?? '—'}
                    {p.position &&
                      ` · ${Math.round(p.position.x)}, ${Math.round(p.position.y)}`}
                  </p>
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

/** Кик и бан. Обе команды работают по userId, поэтому без него они не нужны. */
function PlayerActions({
  player,
  hasPermission,
  onPunish,
}: {
  player: PalworldPlayerDto;
  hasPermission: (key: string) => boolean;
  onPunish: (value: { player: PalworldPlayerDto; kind: 'kick' | 'ban' }) => void;
}) {
  if (!player.userId) {
    return (
      <span className="text-xs text-muted" title="Сервер не отдал userId этого игрока">
        нет userId
      </span>
    );
  }
  return (
    <div className="flex flex-wrap justify-end gap-2">
      {hasPermission('palworld.kick') && (
        <Button size="sm" variant="outline" onClick={() => onPunish({ player, kind: 'kick' })}>
          Кик
        </Button>
      )}
      {hasPermission('palworld.ban') && (
        <Button size="sm" variant="destructive" onClick={() => onPunish({ player, kind: 'ban' })}>
          Бан
        </Button>
      )}
    </div>
  );
}

/**
 * Состояние сервера. Аналог полосы TPS/MSPT у Minecraft, но показатели свои:
 * Palworld рисует кадры, а не тики.
 */
function ServerState({ state }: { state: PalworldServerStateDto }) {
  if (!state.available) {
    return <Card className="text-xs text-muted">{state.reason}</Card>;
  }
  const cells: [string, string, string?][] = [
    [
      'FPS',
      state.fps !== null && state.fps !== undefined ? String(Math.round(state.fps)) : '—',
      // Ниже 30 кадров игроки замечают лагами.
      state.fps !== null && state.fps !== undefined && state.fps < 30 ? 'text-red-400' : '',
    ],
    [
      'Время кадра',
      state.frameTimeMs !== null && state.frameTimeMs !== undefined
        ? `${state.frameTimeMs.toFixed(1)} мс`
        : '—',
    ],
    ['Аптайм', formatUptime(state.uptimeSeconds ?? null)],
    ['Версия', state.version ?? '—'],
  ];
  return (
    <Card className="flex flex-wrap items-start gap-x-6 gap-y-3">
      {state.serverName && (
        <div className="min-w-0">
          <div className="text-[11px] uppercase tracking-wide text-muted">Сервер</div>
          <div className="truncate text-sm font-semibold">{state.serverName}</div>
        </div>
      )}
      {cells.map(([label, value, tone]) => (
        <div key={label}>
          <div className="text-[11px] uppercase tracking-wide text-muted">{label}</div>
          <div className={`text-sm font-semibold ${tone ?? ''}`}>{value}</div>
        </div>
      ))}
    </Card>
  );
}

function formatUptime(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds <= 0) return '—';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} д ${hours} ч`;
  if (hours > 0) return `${hours} ч ${minutes} м`;
  return `${minutes} м`;
}

/** Кик или бан с причиной. Срока нет: временных банов Palworld не умеет. */
function PunishModal({
  serverId,
  player,
  kind,
  onClose,
  onDone,
}: {
  serverId: string;
  player: PalworldPlayerDto;
  kind: 'kick' | 'ban';
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    try {
      await api(`${base(serverId)}/players/${kind}`, {
        method: 'POST',
        body: JSON.stringify({
          userId: player.userId,
          ...(kind === 'ban' ? { playerName: player.name } : {}),
          reason: reason.trim(),
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

  return (
    <Modal
      title={`${kind === 'kick' ? 'Кик' : 'Бан'} игрока ${player.name}`}
      onClose={onClose}
    >
      <div className="space-y-3">
        <div>
          <Label>Причина (увидит игрок)</Label>
          <Input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={kind === 'kick' ? 'Нарушение правил' : 'Гриферство на базе'}
            autoFocus
          />
        </div>
        {kind === 'ban' && (
          <p className="text-xs text-muted">
            Бан у Palworld бессрочный — временных банов сервер не поддерживает. Снять его можно
            на вкладке «Баны».
          </p>
        )}
        <ErrorText>{error}</ErrorText>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Отмена
          </Button>
          <Button variant="destructive" onClick={() => void submit()} disabled={busy}>
            {kind === 'kick' ? 'Кикнуть' : 'Забанить'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ Баны

export function PalworldBansTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [bans, setBans] = useState<PalworldBanDto[] | null>(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(
    (q = '') => {
      setError('');
      const qs = q ? `?search=${encodeURIComponent(q)}` : '';
      return api<PalworldBanDto[]>(`${base(serverId)}/bans${qs}`)
        .then(setBans)
        .catch((e: Error) => setError(e.message));
    },
    [serverId],
  );

  useEffect(() => {
    void load();
  }, [load]);

  async function pardon(banId: string) {
    try {
      await api(`${base(serverId)}/bans/${banId}/pardon`, { method: 'POST' });
      await load(search);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!bans && !error) return <Spinner />;

  return (
    <Card>
      <div className="mb-3 flex gap-2">
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void load(search)}
          placeholder="Поиск по имени или userId…"
        />
        <Button size="sm" variant="outline" onClick={() => void load(search)}>
          Найти
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      <p className="mb-3 text-xs text-muted">
        Список ведёт панель: сам сервер Palworld отдавать баны не умеет, он умеет только банить
        и разбанивать. Здесь видно, кто, когда и за что.
      </p>

      {bans && bans.length === 0 ? (
        <p className="text-muted">Банов нет.</p>
      ) : (
        <ul className="space-y-2">
          {bans?.map((b) => (
            <li key={b.id} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate font-medium">{b.playerName}</div>
                  <div className="break-all font-mono text-[11px] text-muted">{b.userId}</div>
                </div>
                {b.active ? (
                  <Badge variant="destructive">активен</Badge>
                ) : (
                  <Badge variant="outline">
                    {b.pardonedByName ? `снял ${b.pardonedByName}` : 'снят'}
                  </Badge>
                )}
              </div>
              <p className="mt-1 break-words text-sm text-muted">{b.reason}</p>
              <p className="mt-1 text-xs text-muted">
                {new Date(b.createdAt).toLocaleString('ru-RU')}
                {b.createdByName ? ` · ${b.createdByName}` : ''}
              </p>
              {b.active && hasPermission('palworld.ban.pardon') && (
                <Button
                  size="sm"
                  variant="outline"
                  className="mt-2 w-full sm:w-auto"
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

// ------------------------------------------------- Быстрые действия (виджет)

export function PalworldQuickActionsWidget({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [actions, setActions] = useState<PalworldQuickActionDto[] | null>(null);
  const [active, setActive] = useState<PalworldQuickActionDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [result, setResult] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api<{ actions: PalworldQuickActionDto[] }>(`${base(serverId)}/actions`)
      .then((r) => setActions(r.actions))
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  async function run(action: PalworldQuickActionDto, values: Record<string, string>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      // Остановка сервера — отдельный роут со своим правом, см. контроллер.
      const path =
        action.id === 'shutdown'
          ? `${base(serverId)}/shutdown`
          : `${base(serverId)}/actions/${action.id}`;
      const res = await api<PalworldCommandResultDto>(path, {
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
            variant={a.id === 'shutdown' ? 'destructive' : 'outline'}
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
            {active.args.map((arg, index) => (
              <div key={arg.name}>
                <Label>{arg.label}</Label>
                <Input
                  value={args[arg.name] ?? ''}
                  onChange={(e) => setArgs((prev) => ({ ...prev, [arg.name]: e.target.value }))}
                  placeholder={arg.placeholder}
                  inputMode={arg.kind === 'number' ? 'numeric' : undefined}
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
