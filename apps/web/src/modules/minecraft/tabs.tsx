import { useCallback, useEffect, useState } from 'react';
import type {
  MinecraftBanDto,
  MinecraftInventoryItemDto,
  MinecraftInventoryResponse,
  MinecraftInventoryStatusDto,
  MinecraftPlayersResponse,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { PromptModal, PunishModal } from './PlayerModal';

const base = (serverId: string) => `/api/modules/minecraft/servers/${serverId}`;

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
  const { hasPermission } = useAuth();
  const [data, setData] = useState<MinecraftPlayersResponse | null>(null);
  const [error, setError] = useState('');
  const [punish, setPunish] = useState<{ player: string; kind: 'kick' | 'ban' } | null>(null);

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
              <tr key={p.name} className="border-t border-border">
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
                  <div className="flex justify-end gap-2">
                    {hasPermission('minecraft.kick') && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setPunish({ player: p.name, kind: 'kick' })}
                      >
                        Кик
                      </Button>
                    )}
                    {hasPermission('minecraft.ban') && (
                      <Button
                        size="sm"
                        variant="destructive"
                        onClick={() => setPunish({ player: p.name, kind: 'ban' })}
                      >
                        Бан
                      </Button>
                    )}
                  </div>
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
                punish.kind === 'kick' ? { reason } : { reason, ...(expiresAt ? { expiresAt } : {}) },
              ),
            });
            await load();
          }}
        />
      )}
    </Card>
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

// ------------------------------------------------------------- Инвентарь

/** Подсказка при наведении: имя, количество, зачарования и описание. */
function describeItem(item: MinecraftInventoryItemDto): string {
  const lines = [`${item.displayName ?? item.id} ×${item.count}`];
  const enchantments = Object.entries(item.enchantments ?? {});
  for (const [key, level] of enchantments) {
    lines.push(`${key.replace(/^minecraft:/, '')} ${level}`);
  }
  for (const line of item.lore ?? []) lines.push(line);
  return lines.join('\n');
}

function InventoryGrid({
  items,
  size,
  cols,
  slotOffset = 0,
}: {
  items: MinecraftInventoryResponse['items'];
  size: number;
  cols: number;
  /** Номер слота первой ячейки: основной инвентарь начинается с 9, а не с 0. */
  slotOffset?: number;
}) {
  const bySlot = new Map((items ?? []).map((i) => [i.slot, i]));
  return (
    <div className="grid gap-1" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {Array.from({ length: size }, (_, cell) => {
        const slot = cell + slotOffset;
        const item = bySlot.get(slot);
        const enchantCount = item ? Object.keys(item.enchantments ?? {}).length : 0;
        return (
          <div
            key={slot}
            title={item ? describeItem(item) : `Слот ${slot}`}
            className={`relative flex aspect-square items-center justify-center rounded border text-[10px] ${
              item
                ? enchantCount > 0
                  ? 'border-fuchsia-400/50 bg-fuchsia-500/10'
                  : 'border-primary/40 bg-primary/10'
                : 'border-border bg-black/30'
            }`}
          >
            {item && (
              <>
                <span className="truncate px-1 text-center leading-tight">
                  {(item.displayName ?? item.id).replace(/^minecraft:/, '')}
                </span>
                {item.count > 1 && (
                  <span className="absolute bottom-0 right-1 font-bold">{item.count}</span>
                )}
                {/* Зачарованные предметы помечаем — как блеск в самой игре. */}
                {enchantCount > 0 && (
                  <span className="absolute left-0.5 top-0.5 text-fuchsia-300">✦</span>
                )}
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function MinecraftInventoryTab({ serverId }: ModuleTabProps) {
  const [player, setPlayer] = useState('');
  const [status, setStatus] = useState<MinecraftInventoryStatusDto | null>(null);
  const [data, setData] = useState<MinecraftInventoryResponse | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Отдельный статус-роут: по ответу на конкретного игрока нельзя отличить
  // «плагина нет» от «этот игрок сейчас офлайн».
  useEffect(() => {
    void api<MinecraftInventoryStatusDto>(`${base(serverId)}/inventory-status`)
      .then(setStatus)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  async function load() {
    if (!player.trim()) return;
    setBusy(true);
    setError('');
    try {
      setData(await api<MinecraftInventoryResponse>(`${base(serverId)}/inventory/${player.trim()}`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return <Spinner />;

  // Плагина нет — показываем инструкцию, а не пустую сетку.
  if (!status.companionConfigured) {
    return (
      <Card className="space-y-3">
        <h3 className="font-semibold">Нужен companion-плагин</h3>
        <p className="text-sm text-muted">
          Инвентарь нельзя получить по RCON: ванильный сервер не отдаёт содержимое
          инвентаря в текстовом виде. Установите companion-плагин на игровой сервер
          и укажите его адрес в настройках модуля.
        </p>
        <a
          className="inline-block text-sm text-primary underline"
          href={status.docsUrl}
          target="_blank"
          rel="noreferrer"
        >
          Инструкция по установке плагина
        </a>
      </Card>
    );
  }

  return (
    <Card className="space-y-4">
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <Label>Ник игрока</Label>
          <Input
            value={player}
            onChange={(e) => setPlayer(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void load()}
            placeholder="Steve"
          />
        </div>
        <Button onClick={() => void load()} disabled={busy || !player.trim()}>
          Показать
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      {/* Игрок офлайн или плагин не ответил — обычное сообщение, без инструкции. */}
      {data && !data.available && <p className="text-sm text-muted">{data.reason}</p>}

      {data?.available && (
        <div className="space-y-4">
          <div>
            <p className="mb-1 text-xs text-muted">Инвентарь (слоты 9-35)</p>
            <InventoryGrid
              items={data.items?.filter((i) => i.slot >= 9)}
              size={27}
              cols={9}
              slotOffset={9}
            />
          </div>
          <div>
            <p className="mb-1 text-xs text-muted">Хотбар (слоты 0-8)</p>
            <InventoryGrid items={data.items?.filter((i) => i.slot < 9)} size={9} cols={9} />
          </div>
          <div className="flex gap-6">
            <div className="w-32">
              <p className="mb-1 text-xs text-muted">Броня</p>
              <InventoryGrid items={data.armor} size={4} cols={4} />
            </div>
            <div className="w-8">
              <p className="mb-1 text-xs text-muted">Рука</p>
              <InventoryGrid items={data.offhand ? [{ ...data.offhand, slot: 0 }] : []} size={1} cols={1} />
            </div>
          </div>
        </div>
      )}
    </Card>
  );
}

// ------------------------------------------------- Быстрые команды (дашборд)

export function MinecraftQuickCommandsWidget({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [commands, setCommands] = useState<MinecraftQuickCommandDto[] | null>(null);
  const [active, setActive] = useState<MinecraftQuickCommandDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [result, setResult] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [raw, setRaw] = useState('');

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

  async function runRaw() {
    if (!raw.trim()) return;
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<{ output: string }>(`${base(serverId)}/command`, {
        method: 'POST',
        body: JSON.stringify({ command: raw.trim() }),
      });
      setResult(res.output || 'Команда выполнена (сервер ничего не ответил)');
      setRaw('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!commands) return null;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-sm text-muted">Быстрые команды:</span>
        {commands.map((c) => (
          <Button
            key={c.id}
            size="sm"
            variant="outline"
            title={c.description}
            disabled={busy}
            onClick={() => (c.args.length === 0 ? void run(c, {}) : setActive(c))}
          >
            {c.label}
          </Button>
        ))}
      </div>

      {hasPermission('minecraft.command.raw') && (
        <div className="flex gap-2 border-t border-border pt-3">
          <Input
            value={raw}
            onChange={(e) => setRaw(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void runRaw()}
            placeholder="Произвольная RCON-команда, напр. difficulty hard"
          />
          <Button variant="outline" onClick={() => void runRaw()} disabled={busy || !raw.trim()}>
            Выполнить
          </Button>
        </div>
      )}

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
