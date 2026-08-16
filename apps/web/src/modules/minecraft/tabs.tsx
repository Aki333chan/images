import { useCallback, useEffect, useState } from 'react';
import type {
  MinecraftBanDto,
  MinecraftPlayersResponse,
  MinecraftPluginsDto,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { Modal, PromptModal, PunishModal } from './PlayerModal';
import { PlayerDetail } from './PlayerDetail';
import { ActivityHeatmap } from '../../components/ActivityHeatmap';

const base = (serverId: string) => `/api/modules/minecraft/servers/${serverId}`;

/**
 * Подписи групп действий. Ключ — bukkit-имя плагина, значение — то, как его
 * знают люди: «Essentials» на кнопке выглядел бы опечаткой.
 */
const PLUGIN_LABELS: Record<string, string> = {
  Essentials: 'EssentialsX',
};

/** Аватар игрока через Crafatar. Без UUID сервис не работает — рисуем заглушку. */
function PlayerAvatar({ uuid, name }: { uuid: string | null; name: string }) {
  if (!uuid) {
    return (
      <div className="flex h-8 w-8 items-center justify-center rounded bg-white/10 text-xs font-bold uppercase">
        {name.slice(0, 2)}
      </div>
    );
  }
  return (
    <img
      src={`https://crafatar.com/avatars/${uuid}?size=32&overlay`}
      alt={name}
      width={32}
      height={32}
      className="rounded"
      loading="lazy"
    />
  );
}

/** Полоска здоровья: числа в «сердцах» понятнее, чем 18.5 очков. */
function HealthBar({ health, maxHealth }: { health: number; maxHealth: number }) {
  const ratio = maxHealth > 0 ? Math.max(0, Math.min(1, health / maxHealth)) : 0;
  const color = ratio > 0.5 ? 'bg-emerald-500' : ratio > 0.25 ? 'bg-amber-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-16 overflow-hidden rounded-full bg-white/10">
        <div className={`h-full ${color}`} style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="text-xs">{(health / 2).toFixed(1)} ♥</span>
    </div>
  );
}

// ---------------------------------------------------------------- Игроки

export function MinecraftPlayersTab({ serverId }: ModuleTabProps) {
  const [data, setData] = useState<MinecraftPlayersResponse | null>(null);
  const [error, setError] = useState('');
  const [punish, setPunish] = useState<{ player: string; kind: 'kick' | 'ban' } | null>(null);
  /** Открытая карточка игрока. */
  const [selected, setSelected] = useState<string | null>(null);
  /**
   * Список плагинов сервера. Грузится один раз на вкладку и передаётся в
   * карточку: по нему решается, какие действия доступны, а какие показать
   * серыми с подсказкой. null — выяснить не удалось.
   */
  const [plugins, setPlugins] = useState<MinecraftPluginsDto | null>(null);

  useEffect(() => {
    api<MinecraftPluginsDto>(`${base(serverId)}/plugins`)
      .then(setPlugins)
      .catch(() => setPlugins(null));
  }, [serverId]);

  const load = useCallback(() => {
    setError('');
    return api<MinecraftPlayersResponse>(`${base(serverId)}/players`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
    // Список онлайн меняется часто — обновляем раз в 15 секунд.
    const timer = setInterval(() => void load(), 15000);
    return () => clearInterval(timer);
  }, [load]);

  if (error) {
    return (
      <div className="space-y-4">
        <Card>
          <ErrorText>{error}</ErrorText>
          <Button size="sm" variant="outline" className="mt-3" onClick={() => void load()}>
            Повторить
          </Button>
        </Card>
        {/* История онлайна лежит в нашей БД, поэтому график остаётся
            доступен, даже когда живой список получить не удалось. */}
        <ActivityHeatmap serverId={serverId} />
      </div>
    );
  }
  if (!data) return <Spinner />;

  // Именно поиск по имени, а не сохранённый объект: список перезапрашивается
  // каждые 15 секунд, и статистика в карточке должна обновляться вместе с ним.
  const selectedPlayer = selected ? (data.players.find((p) => p.name === selected) ?? null) : null;

  return (
    <div className="space-y-4">
      <Card>
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm text-muted">
            Онлайн: {data.online}
            {data.max !== null && ` / ${data.max}`}
            {data.source === 'rcon' && (
              <span className="ml-2 text-xs">
                (данные по RCON — UUID и пинг доступны с companion-плагином)
              </span>
            )}
          </div>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            Обновить
          </Button>
        </div>

        {data.players.length === 0 ? (
          <p className="text-muted">Сейчас никого нет онлайн.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="pb-2">Игрок</th>
                <th className="pb-2">Здоровье</th>
                <th className="pb-2">Положение</th>
                <th className="pb-2">Пинг</th>
                <th className="pb-2 text-right">Действия</th>
              </tr>
            </thead>
            <tbody>
              {data.players.map((p) => (
                <tr
                  key={p.name}
                  className="cursor-pointer border-t border-border hover:bg-white/5"
                  onClick={() => setSelected(p.name)}
                >
                  <td className="py-2">
                    <div className="flex items-center gap-2">
                      <PlayerAvatar uuid={p.uuid} name={p.name} />
                      <span className="font-medium">{p.name}</span>
                    </div>
                  </td>
                  <td className="py-2 text-muted">
                    {p.health !== null ? (
                      <HealthBar health={p.health} maxHealth={p.maxHealth ?? 20} />
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="py-2 text-xs text-muted">
                    {p.position
                      ? `${p.world ?? '?'} · ${Math.round(p.position.x)}, ${Math.round(p.position.y)}, ${Math.round(p.position.z)}`
                      : '—'}
                  </td>
                  <td className="py-2 text-muted">{p.ping !== null ? `${p.ping} мс` : '—'}</td>
                  <td className="py-2 text-right">
                    <span className="text-xs text-muted">подробнее →</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {punish && (
          <PunishModal
            player={punish.player}
            kind={punish.kind}
            onClose={() => setPunish(null)}
            onSubmit={async (reason, expiresAt) => {
              const path =
                punish.kind === 'kick'
                  ? `${base(serverId)}/players/${punish.player}/kick`
                  : `${base(serverId)}/players/${punish.player}/ban`;
              await api(path, {
                method: 'POST',
                body: JSON.stringify(
                  punish.kind === 'kick'
                    ? { reason }
                    : { reason, ...(expiresAt ? { expiresAt } : {}) },
                ),
              });
              await load();
            }}
          />
        )}

        {selectedPlayer && (
          <Modal title={`Игрок ${selectedPlayer.name}`} onClose={() => setSelected(null)}>
            <PlayerDetail
              serverId={serverId}
              player={selectedPlayer}
              plugins={plugins}
              onChanged={() => void load()}
              onPunish={(kind) => setPunish({ player: selectedPlayer.name, kind })}
            />
          </Modal>
        )}
      </Card>

      <ActivityHeatmap serverId={serverId} />
    </div>
  );
}

// ------------------------------------------------------------------ Баны

export function MinecraftBansTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [bans, setBans] = useState<MinecraftBanDto[] | null>(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(
    (q = '') => {
      setError('');
      const qs = q ? `?search=${encodeURIComponent(q)}` : '';
      return api<MinecraftBanDto[]>(`${base(serverId)}/bans${qs}`)
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
          placeholder="Поиск по нику…"
        />
        <Button size="sm" variant="outline" onClick={() => void load(search)}>
          Найти
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      {bans && bans.length === 0 ? (
        <p className="text-muted">Банов нет.</p>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="pb-2">Игрок</th>
              <th className="pb-2">Причина</th>
              <th className="pb-2">Срок</th>
              <th className="pb-2">Кем</th>
              <th className="pb-2 text-right">Статус</th>
            </tr>
          </thead>
          <tbody>
            {bans?.map((b) => (
              <tr key={b.id} className="border-t border-border align-top">
                <td className="py-2 font-medium">{b.playerName}</td>
                <td className="py-2 text-muted">{b.reason}</td>
                <td className="py-2 text-xs text-muted">
                  {b.expiresAt ? new Date(b.expiresAt).toLocaleString('ru-RU') : 'навсегда'}
                </td>
                <td className="py-2 text-xs text-muted">{b.createdByName ?? '—'}</td>
                <td className="py-2 text-right">
                  {b.active ? (
                    <div className="flex items-center justify-end gap-2">
                      <Badge variant="destructive">активен</Badge>
                      {hasPermission('minecraft.ban.pardon') && (
                        <Button size="sm" variant="outline" onClick={() => void pardon(b.id)}>
                          Снять
                        </Button>
                      )}
                    </div>
                  ) : (
                    <Badge variant="outline">
                      {b.pardonedByName ? `снял ${b.pardonedByName}` : 'снят'}
                    </Badge>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

// ------------------------------------------------------------- Whitelist

export function MinecraftWhitelistTab({ serverId }: ModuleTabProps) {
  const [players, setPlayers] = useState<string[] | null>(null);
  const [error, setError] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(() => {
    setError('');
    return api<MinecraftWhitelistResponse>(`${base(serverId)}/whitelist`)
      .then((r) => setPlayers(r.players))
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function remove(name: string) {
    try {
      const r = await api<MinecraftWhitelistResponse>(`${base(serverId)}/whitelist/${name}`, {
        method: 'DELETE',
      });
      setPlayers(r.players);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!players && !error) return <Spinner />;

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <span className="text-sm text-muted">В белом списке: {players?.length ?? 0}</span>
        <Button size="sm" onClick={() => setAdding(true)}>
          Добавить игрока
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      {players && players.length === 0 ? (
        <p className="text-muted">Белый список пуст.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {players?.map((name) => (
            <span
              key={name}
              className="flex items-center gap-2 rounded-md border border-border px-3 py-1 text-sm"
            >
              {name}
              <button
                className="text-muted hover:text-red-400"
                onClick={() => void remove(name)}
                title="Убрать из whitelist"
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}

      {adding && (
        <PromptModal
          title="Добавить в whitelist"
          label="Ник игрока"
          placeholder="Steve"
          onClose={() => setAdding(false)}
          onSubmit={async (name) => {
            const r = await api<MinecraftWhitelistResponse>(`${base(serverId)}/whitelist`, {
              method: 'POST',
              body: JSON.stringify({ name }),
            });
            setPlayers(r.players);
          }}
        />
      )}
    </Card>
  );
}

export function MinecraftQuickCommandsWidget({ serverId }: ModuleTabProps) {
  const [commands, setCommands] = useState<MinecraftQuickCommandDto[] | null>(null);
  const [active, setActive] = useState<MinecraftQuickCommandDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [result, setResult] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api<{ commands: MinecraftQuickCommandDto[] }>(`${base(serverId)}/quick-commands`)
      .then((r) => setCommands(r.commands))
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  async function run(command: MinecraftQuickCommandDto, values: Record<string, string>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<{ output: string }>(`${base(serverId)}/quick-commands/${command.id}`, {
        method: 'POST',
        body: JSON.stringify({ args: values }),
      });
      setResult(res.output || `Команда «${command.label}» выполнена`);
      setActive(null);
      setArgs({});
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!commands) return null;

  // Группируем по плагину: ванильные первыми, дальше по алфавиту.
  // Сервер уже отфильтровал действия плагинов, которых на нём нет.
  const groups = new Map<string, MinecraftQuickCommandDto[]>();
  for (const command of commands) {
    const key = command.plugin ?? '';
    const list = groups.get(key);
    if (list) list.push(command);
    else groups.set(key, [command]);
  }
  const ordered = [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));

  return (
    <Card className="space-y-3">
      {ordered.map(([plugin, list]) => (
        <div key={plugin || 'vanilla'} className="flex flex-wrap items-center gap-2">
          <span className="mr-1 text-sm text-muted">
            {plugin ? (PLUGIN_LABELS[plugin] ?? plugin) : 'Быстрые команды'}:
          </span>
          {list.map((c) => (
            <Button
              key={c.id}
              size="sm"
              variant="outline"
              title={c.description}
              disabled={busy}
              onClick={() => {
                // Действие с аргументами и так открывает форму — там
                // человек видит, что именно запускает. Подтверждение нужно
                // только для заметных действий без аргументов.
                if (c.args.length > 0) {
                  setActive(c);
                  return;
                }
                if (c.destructive && !confirm(`${c.description}\n\nВыполнить?`)) return;
                void run(c, {});
              }}
            >
              {c.label}
            </Button>
          ))}
        </div>
      ))}

      {result && (
        <pre className="max-h-32 overflow-y-auto whitespace-pre-wrap rounded bg-black/40 p-2 font-mono text-xs text-emerald-300">
          {result}
        </pre>
      )}
      <ErrorText>{error}</ErrorText>

      {active && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
          onClick={() => setActive(null)}
        >
          <div className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <Card className="space-y-3">
              <h3 className="font-semibold">{active.label}</h3>
              <p className="text-xs text-muted">{active.description}</p>
              {active.args.map((arg) => (
                <div key={arg.name}>
                  <Label>{arg.label}</Label>
                  <Input
                    value={args[arg.name] ?? ''}
                    onChange={(e) => setArgs((prev) => ({ ...prev, [arg.name]: e.target.value }))}
                    placeholder={arg.placeholder}
                    autoFocus
                  />
                </div>
              ))}
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setActive(null)} disabled={busy}>
                  Отмена
                </Button>
                <Button onClick={() => void run(active, args)} disabled={busy}>
                  Выполнить
                </Button>
              </div>
            </Card>
          </div>
        </div>
      )}
    </Card>
  );
}
