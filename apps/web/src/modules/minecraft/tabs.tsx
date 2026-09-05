import { useCallback, useEffect, useState } from 'react';
import type {
  MinecraftBanDto,
  MinecraftKnownPlayerDto,
  MinecraftPlayerDto,
  MinecraftPlayersResponse,
  MinecraftPluginsDto,
  MinecraftQuickCommandDto,
  MinecraftWhitelistResponse,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { Modal, PromptModal, PunishModal } from './PlayerModal';
import { PlayerDetail } from './PlayerDetail';
import { PlayerPicker, useOnlinePlayers } from './PlayerPicker';
import { ActivityHeatmap } from '../../components/ActivityHeatmap';
import { KnownPlayersPanel } from './KnownPlayersPanel';
import { PlayerName } from './PlayerName';
import { useI18n, useT } from '../../i18n';
import { knownByName } from './player-name';

/**
 * Базовый путь API берётся из moduleId, а не зашит строкой.
 *
 * Один и тот же набор вкладок обслуживает Paper, Forge и NeoForge: за ними
 * стоят одни и те же команды сервера Minecraft, только на разных префиксах.
 * Зашитый 'minecraft' означал бы три копии одинаковых компонентов, которые
 * разъедутся при первой же правке.
 */
const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/**
 * Плагины Bukkit есть только у Paper. На загрузчиках модов их не существует,
 * и спрашивать о них незачем: роута там нет, а 404 в консоли браузера
 * выглядит как поломка, хотя это норма.
 */
const hasBukkitPlugins = (moduleId: string) => moduleId === 'minecraft';

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

export function MinecraftPlayersTab({ serverId, moduleId }: ModuleTabProps) {
  const t = useT();

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
  /**
   * Записи исторического списка по нику.
   *
   * Из них берутся звёздочка оператора и ник EssentialsX для таблицы
   * онлайна: сам список онлайна их не знает — он приходит по RCON, где
   * кроме ников ничего нет. Игроки в сети стоят в начале исторического
   * списка, поэтому первой его страницы для этого хватает.
   */
  const [known, setKnown] = useState<Map<string, MinecraftKnownPlayerDto>>(new Map());
  /**
   * Игрок из истории, чью карточку открыли.
   *
   * Отдельно от `selected`: тот ищется в списке онлайна, а этого там нет.
   */
  const [selectedKnown, setSelectedKnown] = useState<MinecraftKnownPlayerDto | null>(null);

  useEffect(() => {
    if (!hasBukkitPlugins(moduleId)) return setPlugins(null);
    api<MinecraftPluginsDto>(`${base(moduleId, serverId)}/plugins`)
      .then(setPlugins)
      .catch(() => setPlugins(null));
  }, [serverId, moduleId]);

  const load = useCallback(() => {
    setError('');
    return api<MinecraftPlayersResponse>(`${base(moduleId, serverId)}/players`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [serverId, moduleId]);

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
            {t('common.retry')}
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
  const onlineSelected = selected ? (data.players.find((p) => p.name === selected) ?? null) : null;
  // Игрок из истории приходит без здоровья и координат — их у него и нет.
  // Карточка это уже умеет: те же прочерки, что и у списка по чистому RCON.
  const selectedPlayer: MinecraftPlayerDto | null =
    onlineSelected ??
    (selectedKnown
      ? {
          name: selectedKnown.name,
          uuid: selectedKnown.uuid,
          ping: null,
          health: null,
          maxHealth: null,
          world: null,
          position: null,
        }
      : null);
  const selectedKnownRecord =
    selectedKnown ?? (selectedPlayer ? (known.get(selectedPlayer.name.toLowerCase()) ?? null) : null);

  const closeCard = () => {
    setSelected(null);
    setSelectedKnown(null);
  };

  return (
    <div className="space-y-4">
      <Card>
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm text-muted">
            {t('mc.tab.online', { count: data.online })}
            {data.max !== null && ` / ${data.max}`}
            {data.source === 'rcon' && (
              <span className="ml-2 text-xs">
                {t('mc.tab.rconHint')}
              </span>
            )}
          </div>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            {t('mc.tab.refresh')}
          </Button>
        </div>

        {data.players.length === 0 ? (
          <p className="text-muted">{t('mc.tab.nobody')}</p>
        ) : (
          <>
            {/* Таблица — только с md. На узком экране пять колонок либо
                сжимаются до нечитаемого, либо уезжают в горизонтальную
                прокрутку, где половина данных всегда за краем. */}
            <table className="hidden w-full text-sm md:table">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="pb-2">{t('mc.th.player')}</th>
                  <th className="pb-2">{t('mc.th.health')}</th>
                  <th className="pb-2">{t('mc.th.position')}</th>
                  <th className="pb-2">{t('mc.th.ping')}</th>
                  <th className="pb-2 text-right">{t('mc.th.actions')}</th>
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
                        <PlayerName
                          name={p.name}
                          alias={known.get(p.name.toLowerCase())?.alias ?? null}
                          op={known.get(p.name.toLowerCase())?.op ?? false}
                        />
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
                    <td className="py-2 text-muted">{p.ping !== null ? t('mc.ms', { value: p.ping }) : '—'}</td>
                    <td className="py-2 text-right">
                      <span className="text-xs text-muted">{t('mc.more')}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* Тот же список карточками: те же данные, но в два ряда и без
                прокрутки вбок. Вся карточка — одна кнопка, открывающая игрока. */}
            <ul className="space-y-2 md:hidden">
              {data.players.map((p) => (
                <li key={p.name}>
                  <button
                    type="button"
                    onClick={() => setSelected(p.name)}
                    className="flex w-full items-center gap-3 rounded-md border border-border p-3 text-left hover:bg-white/5"
                  >
                    <PlayerAvatar uuid={p.uuid} name={p.name} />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <PlayerName
                          name={p.name}
                          alias={known.get(p.name.toLowerCase())?.alias ?? null}
                          op={known.get(p.name.toLowerCase())?.op ?? false}
                          className="min-w-0 truncate"
                        />
                        <span className="shrink-0 text-xs text-muted">
                          {p.ping !== null ? t('mc.ms', { value: p.ping }) : ''}
                        </span>
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
                        {p.health !== null && (
                          <HealthBar health={p.health} maxHealth={p.maxHealth ?? 20} />
                        )}
                        {p.position && (
                          <span className="truncate">
                            {p.world ?? '?'} · {Math.round(p.position.x)}, {Math.round(p.position.y)}
                            , {Math.round(p.position.z)}
                          </span>
                        )}
                      </div>
                    </div>
                    <span aria-hidden className="shrink-0 text-muted">
                      →
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}

        {punish && (
          <PunishModal
            player={punish.player}
            kind={punish.kind}
            onClose={() => setPunish(null)}
            onSubmit={async (reason, expiresAt) => {
              const path =
                punish.kind === 'kick'
                  ? `${base(moduleId, serverId)}/players/${punish.player}/kick`
                  : `${base(moduleId, serverId)}/players/${punish.player}/ban`;
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
          <Modal title={t('mc.playerTitle', { name: selectedPlayer.name })} onClose={closeCard}>
            <PlayerDetail
              serverId={serverId}
              moduleId={moduleId}
              player={selectedPlayer}
              known={selectedKnownRecord}
              plugins={plugins}
              onChanged={() => void load()}
              onPunish={(kind) => setPunish({ player: selectedPlayer.name, kind })}
            />
          </Modal>
        )}
      </Card>

      {/* Все, кто когда-либо заходил. Отдельным блоком под онлайном, а не
          вместо него: онлайн обновляется каждые 15 секунд и нужен сразу, а
          история читается с диска игрового сервера и листается по запросу. */}
      {hasBukkitPlugins(moduleId) && (
        <KnownPlayersPanel
          serverId={serverId}
          moduleId={moduleId}
          onOpen={(p) => {
            setSelected(null);
            setSelectedKnown(p);
          }}
          onLoaded={(players) => setKnown(knownByName(players))}
        />
      )}

      <ActivityHeatmap serverId={serverId} />
    </div>
  );
}

// ------------------------------------------------------------------ Баны

export function MinecraftBansTab({ serverId, moduleId }: ModuleTabProps) {
  const { t, formatDateTime } = useI18n();

  const { hasPermission } = useAuth();
  const [bans, setBans] = useState<MinecraftBanDto[] | null>(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(
    (q = '') => {
      setError('');
      const qs = q ? `?search=${encodeURIComponent(q)}` : '';
      return api<MinecraftBanDto[]>(`${base(moduleId, serverId)}/bans${qs}`)
        .then(setBans)
        .catch((e: Error) => setError(e.message));
    },
    [serverId, moduleId],
  );

  useEffect(() => {
    void load();
  }, [load]);

  async function pardon(banId: string) {
    try {
      await api(`${base(moduleId, serverId)}/bans/${banId}/pardon`, { method: 'POST' });
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
          placeholder={t('mc.bans.search')}
        />
        <Button size="sm" variant="outline" onClick={() => void load(search)}>
          Найти
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      {bans && bans.length === 0 ? (
        <p className="text-muted">{t('mc.bans.empty')}</p>
      ) : (
        <>
          <table className="hidden w-full text-sm md:table">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="pb-2">{t('mc.th.player')}</th>
                <th className="pb-2">{t('mc.th.reason')}</th>
                <th className="pb-2">{t('mc.th.until')}</th>
                <th className="pb-2">{t('mc.th.by')}</th>
                <th className="pb-2 text-right">{t('mc.th.status')}</th>
              </tr>
            </thead>
            <tbody>
              {bans?.map((b) => (
                <tr key={b.id} className="border-t border-border align-top">
                  <td className="py-2 font-medium">{b.playerName}</td>
                  <td className="py-2 text-muted">{b.reason}</td>
                  <td className="py-2 text-xs text-muted">
                    {b.expiresAt ? formatDateTime(b.expiresAt) : t('mc.bans.forever')}
                  </td>
                  <td className="py-2 text-xs text-muted">{b.createdByName ?? '—'}</td>
                  <td className="py-2 text-right">
                    {b.active ? (
                      <div className="flex items-center justify-end gap-2">
                        <Badge variant="destructive">{t('mc.bans.active')}</Badge>
                        {hasPermission('minecraft.ban.pardon') && (
                          <Button size="sm" variant="outline" onClick={() => void pardon(b.id)}>
                            {t('mc.bans.pardon')}
                          </Button>
                        )}
                      </div>
                    ) : (
                      <Badge variant="outline">
                        {b.pardonedByName ? t('mc.bans.pardonedBy', { name: b.pardonedByName }) : t('mc.bans.pardoned')}
                      </Badge>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Карточки вместо таблицы на узком экране. Причина бана —
              свободный текст произвольной длины, в колонке таблицы она
              на телефоне превращается в столбик по одному слову. */}
          <ul className="space-y-2 md:hidden">
            {bans?.map((b) => (
              <li key={b.id} className="rounded-md border border-border p-3">
                <div className="flex items-start justify-between gap-2">
                  <span className="min-w-0 truncate font-medium">{b.playerName}</span>
                  {b.active ? (
                    <Badge variant="destructive">{t('mc.bans.active')}</Badge>
                  ) : (
                    <Badge variant="outline">
                      {b.pardonedByName ? t('mc.bans.pardonedBy', { name: b.pardonedByName }) : t('mc.bans.pardoned')}
                    </Badge>
                  )}
                </div>
                <p className="mt-1 break-words text-sm text-muted">{b.reason}</p>
                <p className="mt-1 text-xs text-muted">
                  {t('mc.bans.until', { date: b.expiresAt ? formatDateTime(b.expiresAt) : t('mc.bans.forever') })}
                  {b.createdByName ? ` · ${b.createdByName}` : ''}
                </p>
                {b.active && hasPermission('minecraft.ban.pardon') && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="mt-2 w-full"
                    onClick={() => void pardon(b.id)}
                  >
                    Снять бан
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </Card>
  );
}

// ------------------------------------------------------------- Whitelist

export function MinecraftWhitelistTab({ serverId, moduleId }: ModuleTabProps) {
  const t = useT();

  const [players, setPlayers] = useState<string[] | null>(null);
  const [error, setError] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(() => {
    setError('');
    return api<MinecraftWhitelistResponse>(`${base(moduleId, serverId)}/whitelist`)
      .then((r) => setPlayers(r.players))
      .catch((e: Error) => setError(e.message));
  }, [serverId, moduleId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function remove(name: string) {
    try {
      const r = await api<MinecraftWhitelistResponse>(`${base(moduleId, serverId)}/whitelist/${name}`, {
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
        <span className="text-sm text-muted">{t('mc.whitelist.count', { count: players?.length ?? 0 })}</span>
        <Button size="sm" onClick={() => setAdding(true)}>
          Добавить игрока
        </Button>
      </div>
      <ErrorText>{error}</ErrorText>

      {players && players.length === 0 ? (
        <p className="text-muted">{t('mc.whitelist.empty')}</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {players?.map((name) => (
            <span
              key={name}
              className="flex items-center gap-1 rounded-md border border-border py-1 pl-3 text-sm"
            >
              {name}
              {/* Крестик был 12×20 — в него не попасть пальцем. Увеличен до
                  минимальных 40×40 за счёт отступов: сам символ остался
                  прежнего размера, выросла область нажатия. */}
              <button
                className="flex h-10 w-10 items-center justify-center rounded-md text-muted hover:bg-white/5 hover:text-red-400 sm:h-8 sm:w-8"
                onClick={() => void remove(name)}
                title={t('mc.whitelist.remove')}
                aria-label={t('mc.whitelist.removeOf', { name })}
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}

      {adding && (
        <PromptModal
          title={t('mc.whitelist.add')}
          label={t('mc.whitelist.nick')}
          placeholder="Steve"
          onClose={() => setAdding(false)}
          onSubmit={async (name) => {
            const r = await api<MinecraftWhitelistResponse>(`${base(moduleId, serverId)}/whitelist`, {
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

/** Значения по умолчанию: у аргумента со списком — его первый вариант. */
function defaultArgs(command: MinecraftQuickCommandDto): Record<string, string> {
  const values: Record<string, string> = {};
  for (const arg of command.args) {
    // Иначе выпадающий список показывал бы первый вариант, а на сервер
    // уходило бы пустое значение — «не заполнено поле «Режим»».
    if (arg.options?.[0]) values[arg.name] = arg.options[0].value;
  }
  return values;
}

export function MinecraftQuickCommandsWidget({ serverId, moduleId }: ModuleTabProps) {
  const t = useT();

  const [commands, setCommands] = useState<MinecraftQuickCommandDto[] | null>(null);
  const [active, setActive] = useState<MinecraftQuickCommandDto | null>(null);
  const [args, setArgs] = useState<Record<string, string>>({});
  const [result, setResult] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Список онлайна нужен, только когда открыто действие, спрашивающее ник:
  // лишний поход к игровому серверу на каждый показ дашборда ни к чему.
  const needsPlayers = !!active?.args.some((a) => a.suggest === 'online-players');
  const onlinePlayers = useOnlinePlayers(serverId, needsPlayers, moduleId);

  useEffect(() => {
    void api<{ commands: MinecraftQuickCommandDto[] }>(`${base(moduleId, serverId)}/quick-commands`)
      .then((r) => setCommands(r.commands))
      .catch((e: Error) => setError(e.message));
  }, [serverId, moduleId]);

  async function run(command: MinecraftQuickCommandDto, values: Record<string, string>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<{ output: string }>(`${base(moduleId, serverId)}/quick-commands/${command.id}`, {
        method: 'POST',
        body: JSON.stringify({ args: values }),
      });
      setResult(res.output || t('mc.quick.done', { label: command.label }));
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
            {plugin ? (PLUGIN_LABELS[plugin] ?? plugin) : t('mc.quick.title')}:
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
                  setArgs(defaultArgs(c));
                  setActive(c);
                  return;
                }
                if (c.destructive && !confirm(t('mc.quick.confirm', { description: c.description }))) return;
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
        // Та же модалка, что и у карточки игрока: на мобильном — на весь
        // экран, на десктопе — окно по центру.
        <Modal title={active.label} onClose={() => setActive(null)}>
          <div className="space-y-3">
            <p className="text-xs text-muted">{active.description}</p>
            {active.args.map((arg, index) => (
              <div key={arg.name}>
                <Label>{arg.label}</Label>
                {arg.options ? (
                  // Закрытый список значений — режим игры и подобное.
                  <Select
                    className="w-full"
                    value={args[arg.name] ?? arg.options[0]?.value ?? ''}
                    onChange={(v) => setArgs((prev) => ({ ...prev, [arg.name]: v }))}
                    options={arg.options}
                  />
                ) : arg.suggest === 'online-players' ? (
                  // Ник: подсказываем тех, кто в сети, но ввод не ограничиваем.
                  <PlayerPicker
                    value={args[arg.name] ?? ''}
                    onChange={(v) => setArgs((prev) => ({ ...prev, [arg.name]: v }))}
                    players={onlinePlayers}
                    placeholder={arg.placeholder}
                    autoFocus={index === 0}
                  />
                ) : (
                  <Input
                    value={args[arg.name] ?? ''}
                    onChange={(e) => setArgs((prev) => ({ ...prev, [arg.name]: e.target.value }))}
                    placeholder={arg.placeholder}
                    // Фокус только в первое поле: с autoFocus на всех
                    // курсор оказывался в последнем.
                    autoFocus={index === 0}
                  />
                )}
              </div>
            ))}
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button variant="ghost" onClick={() => setActive(null)} disabled={busy}>
                Отмена
              </Button>
              <Button onClick={() => void run(active, args)} disabled={busy}>
                {t('mc.quick.run')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Card>
  );
}
