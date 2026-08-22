import { useCallback, useEffect, useState } from 'react';
import type {
  MinecraftInventoryResponse,
  MinecraftPlayerDto,
  MinecraftPluginsDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Label, Select, Spinner, Tabs } from '../../components/ui';
import { BalancePanel } from './BalancePanel';
import { InventoryGrid } from './InventoryGrid';
import { PermissionsPanel } from './PermissionsPanel';
import { PlayerPicker, useOnlinePlayers } from './PlayerPicker';

const base = (serverId: string) => `/api/modules/minecraft/servers/${serverId}`;

/**
 * Что должно стоять на сервере, чтобы действие работало.
 * null — ванильная команда, есть всегда.
 */
type Requirement = null | 'Essentials' | 'LuckPerms' | 'companion';

const REQUIREMENT_HINT: Record<Exclude<Requirement, null>, string> = {
  Essentials: 'нужен EssentialsX',
  LuckPerms: 'нужен LuckPerms',
  companion: 'нужен companion-плагин',
};

/** Кнопка действия, которая честно объясняет, почему она неактивна. */
function ActionButton({
  label,
  hint,
  requirement,
  available,
  disabled,
  variant,
  onClick,
}: {
  label: string;
  hint: string;
  requirement: Requirement;
  available: boolean;
  disabled?: boolean;
  variant?: 'outline' | 'destructive';
  onClick: () => void;
}) {
  const blocked = requirement !== null && !available;
  return (
    <Button
      size="sm"
      variant={variant ?? 'outline'}
      // Серая с подсказкой, а не спрятанная: человек должен понимать, что
      // возможность есть, но её нужно доустановить на игровой сервер.
      disabled={blocked || disabled}
      title={blocked ? `${hint} — ${REQUIREMENT_HINT[requirement]}` : hint}
      onClick={onClick}
    >
      {label}
      {blocked && <span className="ml-1 opacity-60">·{REQUIREMENT_HINT[requirement]}</span>}
    </Button>
  );
}

/**
 * Карточка игрока: всё про одного человека в одном месте.
 *
 * Раньше это были три разрозненных места — список игроков, отдельная вкладка
 * инвентаря с вводом ника руками и общие быстрые команды, куда ник тоже
 * вбивался вручную. Теперь клик по строке открывает окно, где уже известно,
 * о ком речь.
 *
 * Действия помечены источником: gamemode/kill/teleport — vanilla и работают
 * всегда; heal — EssentialsX; группа прав — LuckPerms; инвентарь — companion.
 * Чего нет на сервере, то показано серым с подсказкой.
 */
export function PlayerDetail({
  serverId,
  player,
  plugins,
  onChanged,
  onPunish,
}: {
  serverId: string;
  player: MinecraftPlayerDto;
  /** Уже загруженный список плагинов сервера; null — выяснить не удалось. */
  plugins: MinecraftPluginsDto | null;
  /** Дёргается после действия, меняющего состояние игрока. */
  onChanged: () => void;
  onPunish: (kind: 'kick' | 'ban') => void;
}) {
  const { hasPermission } = useAuth();
  const [tab, setTab] = useState<'actions' | 'inventory' | 'rights'>('actions');

  const has = useCallback(
    (id: string) => !!plugins?.known.find((p) => p.id === id)?.installed,
    [plugins],
  );

  const canAct = hasPermission('minecraft.quick-commands');

  return (
    <div className="space-y-4">
      <PlayerStats player={player} />

      {/* Тот же компонент, что и у вкладок сервера: и вид, и едущая подложка
          общие. Отдельная реализация здесь означала бы, что одинаковое на вид
          переключение ведёт себя по-разному в двух местах одного экрана. */}
      <Tabs
        fill
        active={tab}
        onChange={(id) => setTab(id as typeof tab)}
        tabs={[
          { id: 'actions', label: 'Действия' },
          { id: 'inventory', label: 'Инвентарь' },
          { id: 'rights', label: 'Группа прав' },
        ]}
      />

      {tab === 'actions' && (
        <div className="space-y-4">
          <PlayerActions
            serverId={serverId}
            player={player}
            has={has}
            canAct={canAct}
            onChanged={onChanged}
            onPunish={onPunish}
          />

          {/* Валюта — отдельным блоком, а не четвёртой вкладкой: на телефоне
              вкладки и так делят ширину поровну, и четвёртая сделала бы
              подписи нечитаемыми. */}
          {hasPermission('minecraft.economy.view') && (
            <div className="space-y-2 border-t border-border pt-4">
              <Label>Валюта</Label>
              {player.uuid ? (
                <BalancePanel serverId={serverId} uuid={player.uuid} />
              ) : (
                <p className="text-sm text-muted">
                  Для работы с валютой нужен UUID игрока, а его отдаёт только companion-плагин.
                </p>
              )}
            </div>
          )}
        </div>
      )}

      {/* Признак того, что companion на связи, — это plugins.available: он и
          означает «companion ответил на запрос списка». Раньше здесь стояло
          has('AurumCompanion'), но has() ищет в plugins.known, то есть в
          KNOWN_PLUGINS — списке СТОРОННИХ плагинов, которые панель умеет
          распознавать. Самого себя панель туда не вносит, и проверка была
          ложной всегда: вкладка инвентаря пропадала ровно тогда, когда
          companion работал, и появлялась, когда список вовсе не удалось
          получить. */}
      {tab === 'inventory' &&
        (plugins === null || plugins.available ? (
          <PlayerInventory serverId={serverId} name={player.name} />
        ) : (
          <p className="text-sm text-muted">
            Инвентарь показывает companion-плагин — сейчас он не отвечает. Проверьте адрес и
            токен во вкладке «Настройки» сервера.
          </p>
        ))}

      {tab === 'rights' &&
        (player.uuid ? (
          <PermissionsPanel serverId={serverId} uuid={player.uuid} />
        ) : (
          <p className="text-sm text-muted">
            Для работы с группой прав нужен UUID игрока, а его отдаёт только companion-плагин.
          </p>
        ))}
    </div>
  );
}

/** Статистика: то, что уже отдаёт companion-плагин. */
function PlayerStats({ player }: { player: MinecraftPlayerDto }) {
  const cells: [string, string][] = [
    ['Здоровье', player.health !== null ? `${(player.health / 2).toFixed(1)} ♥` : '—'],
    ['Пинг', player.ping !== null ? `${player.ping} мс` : '—'],
    ['Мир', player.world ?? '—'],
    [
      'Координаты',
      player.position
        ? `${Math.round(player.position.x)}, ${Math.round(player.position.y)}, ${Math.round(player.position.z)}`
        : '—',
    ],
  ];
  return (
    <Card className="flex flex-wrap gap-x-6 gap-y-2">
      {cells.map(([label, value]) => (
        <div key={label}>
          <div className="text-[11px] uppercase tracking-wide text-muted">{label}</div>
          <div className="text-sm font-semibold">{value}</div>
        </div>
      ))}
      {player.uuid && (
        <div className="min-w-0">
          <div className="text-[11px] uppercase tracking-wide text-muted">UUID</div>
          <div className="truncate font-mono text-xs">{player.uuid}</div>
        </div>
      )}
    </Card>
  );
}

/** Быстрые действия по конкретному игроку. */
function PlayerActions({
  serverId,
  player,
  has,
  canAct,
  onChanged,
  onPunish,
}: {
  serverId: string;
  player: MinecraftPlayerDto;
  has: (id: string) => boolean;
  canAct: boolean;
  onChanged: () => void;
  onPunish: (kind: 'kick' | 'ban') => void;
}) {
  const { hasPermission } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [mode, setMode] = useState('survival');
  const [target, setTarget] = useState('');
  // Кого предлагать в поле телепорта. Себя из списка убираем: «телепорт
  // Steve к Steve» — не действие, а недоразумение.
  const online = useOnlinePlayers(serverId, true).filter((n) => n !== player.name);

  async function runQuick(id: string, args: Record<string, string>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<{ output: string }>(`${base(serverId)}/quick-commands/${id}`, {
        method: 'POST',
        body: JSON.stringify({ args }),
      });
      setResult(res.output || 'Выполнено');
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const name = player.name;

  return (
    <div className="space-y-4">
      {!canAct && (
        <p className="text-xs text-muted">
          У вашей роли нет права на быстрые действия — доступны только кик и бан.
        </p>
      )}

      <div className="space-y-2">
        <Label>Режим игры</Label>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={mode}
            onChange={setMode}
            options={[
              { value: 'survival', label: 'Выживание' },
              { value: 'creative', label: 'Творческий' },
              { value: 'adventure', label: 'Приключение' },
              { value: 'spectator', label: 'Наблюдатель' },
            ]}
          />
          <ActionButton
            label="Применить"
            hint="Ванильная команда gamemode — работает без плагинов"
            requirement={null}
            available
            disabled={busy || !canAct}
            onClick={() => void runQuick('vanilla-gamemode', { mode, player: name })}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>Состояние</Label>
        <div className="flex flex-wrap gap-2">
          <ActionButton
            label="Вылечить"
            hint="Восстанавливает здоровье и сытость"
            requirement="Essentials"
            available={has('Essentials')}
            disabled={busy || !canAct}
            onClick={() => void runQuick('ess-heal', { player: name })}
          />
          <ActionButton
            label="Убить"
            hint="Ванильная команда kill"
            requirement={null}
            available
            variant="destructive"
            disabled={busy || !canAct}
            onClick={() => {
              if (!confirm(`Убить игрока ${name}?`)) return;
              void runQuick('vanilla-kill', { player: name });
            }}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>Телепорт</Label>
        <div className="flex flex-wrap items-center gap-2">
          <PlayerPicker
            value={target}
            onChange={setTarget}
            players={online}
            placeholder="Ник, к кому телепортировать"
            // На телефоне поле занимает строку целиком, на десктопе —
            // прежняя узкая колонка рядом с кнопкой.
            className="min-w-0 flex-1 sm:w-[220px] sm:flex-none"
          />
          <ActionButton
            label="Переместить"
            hint="Ванильная команда tp — работает без плагинов"
            requirement={null}
            available
            disabled={busy || !canAct || !target.trim()}
            onClick={() => void runQuick('vanilla-tp', { player: name, target: target.trim() })}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>Наказания</Label>
        <div className="flex flex-wrap gap-2">
          {hasPermission('minecraft.kick') && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => onPunish('kick')}>
              Кик
            </Button>
          )}
          {hasPermission('minecraft.ban') && (
            <Button size="sm" variant="destructive" disabled={busy} onClick={() => onPunish('ban')}>
              Бан
            </Button>
          )}
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="whitespace-pre-wrap text-xs text-emerald-400">{result}</p>}
    </div>
  );
}

/** Инвентарь конкретного игрока — ник уже известен, вводить его не нужно. */
function PlayerInventory({ serverId, name }: { serverId: string; name: string }) {
  const [data, setData] = useState<MinecraftInventoryResponse | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setData(null);
    setError('');
    api<MinecraftInventoryResponse>(`${base(serverId)}/inventory/${encodeURIComponent(name)}`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [serverId, name]);

  if (error) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  if (!data.available) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted">{data.reason}</p>
        {data.docsUrl && (
          <a
            className="text-xs text-primary underline"
            href={data.docsUrl}
            target="_blank"
            rel="noreferrer"
          >
            Как установить companion-плагин
          </a>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <Label>Хотбар</Label>
        <div className="mt-1 max-w-[420px]">
          <InventoryGrid items={data.items} size={9} cols={9} />
        </div>
      </div>
      <div>
        <Label>Инвентарь</Label>
        <div className="mt-1 max-w-[420px]">
          <InventoryGrid items={data.items} size={27} cols={9} slotOffset={9} />
        </div>
      </div>
      <div className="flex gap-6">
        <div>
          <Label>Броня</Label>
          <div className="mt-1 w-[52px]">
            <InventoryGrid items={data.armor} size={4} cols={1} />
          </div>
        </div>
        <div>
          <Label>Вторая рука</Label>
          <div className="mt-1 w-[52px]">
            <InventoryGrid items={data.offhand ? [data.offhand] : []} size={1} cols={1} />
          </div>
        </div>
      </div>
    </div>
  );
}

/** Значок «плагина нет» для строки списка — чтобы это было видно заранее. */
export function MissingBadge({ label }: { label: string }) {
  return <Badge variant="outline">{label}</Badge>;
}
