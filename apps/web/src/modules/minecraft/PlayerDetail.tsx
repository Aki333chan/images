import { useCallback, useEffect, useState } from 'react';
import { MINECRAFT_GUILD_RANK_KEYS } from '@aurum/shared';
import type {
  MinecraftGiveResponse,
  MinecraftGiveResultDto,
  MinecraftGuildMembershipDto,
  MinecraftInventoryClearDto,
  MinecraftInventoryResponse,
  MinecraftKnownPlayerDto,
  MinecraftPasswordResetDto,
  MinecraftPlayerDto,
  MinecraftPluginsDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Label, Select, Spinner, Tabs, Textarea } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { BalancePanel } from './BalancePanel';
import { InventoryGrid } from './InventoryGrid';
import { parseGiveList } from './give-list';
import { PermissionsPanel } from './PermissionsPanel';
import { PlayerPicker, useOnlinePlayers } from './PlayerPicker';
import { PlayerIpsPanel } from './PlayerIpsPanel';
import { PlayerName } from './PlayerName';
import { useApiText, useT } from '../../i18n';

/** Тот же принцип, что и во вкладках: префикс берётся из модуля сервера. */
const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/**
 * Что должно стоять на сервере, чтобы действие работало.
 * null — ванильная команда, есть всегда.
 */
type Requirement = null | 'Essentials' | 'LuckPerms' | 'companion' | 'AurumAuth' | 'AurumGuilds';

/** Ключи подсказок: сам список — константа, язык известен только в интерфейсе. */
const REQUIREMENT_HINT: Record<Exclude<Requirement, null>, string> = {
  Essentials: 'mc.need.Essentials',
  LuckPerms: 'mc.need.LuckPerms',
  companion: 'mc.need.companion',
  AurumAuth: 'mc.need.AurumAuth',
  AurumGuilds: 'mc.need.AurumGuilds',
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
  const t = useT();
  const blocked = requirement !== null && !available;
  return (
    <Button
      size="sm"
      variant={variant ?? 'outline'}
      // Серая с подсказкой, а не спрятанная: человек должен понимать, что
      // возможность есть, но её нужно доустановить на игровой сервер.
      disabled={blocked || disabled}
      title={blocked ? `${hint} — ${t(REQUIREMENT_HINT[requirement])}` : hint}
      onClick={onClick}
    >
      {label}
      {blocked && <span className="ml-1 opacity-60">·{t(REQUIREMENT_HINT[requirement])}</span>}
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
  moduleId,
  player,
  known,
  plugins,
  onChanged,
  onPunish,
}: {
  serverId: string;
  /** Модуль сервера: от него зависят и адрес API, и набор вкладок карточки. */
  moduleId: string;
  player: MinecraftPlayerDto;
  /**
   * Запись из исторического списка, если она есть.
   *
   * Отсюда берутся звёздочка оператора и ник EssentialsX. Сам список
   * онлайна их не знает: он приходит по RCON, где ничего кроме ников нет.
   * null — записи не нашлось (например, companion-плагина нет вовсе), и
   * карточка просто показывает имя без украшений.
   */
  known?: MinecraftKnownPlayerDto | null;
  /** Уже загруженный список плагинов сервера; null — выяснить не удалось. */
  plugins: MinecraftPluginsDto | null;
  /** Дёргается после действия, меняющего состояние игрока. */
  onChanged: () => void;
  onPunish: (kind: 'kick' | 'ban') => void;
}) {
  const t = useT();
  const { hasPermission } = useAuth();
  const [tab, setTab] = useState<'actions' | 'inventory' | 'rights'>('actions');

  /**
   * Инвентарь, группа прав и валюта — работа companion-плагина Bukkit.
   * На Forge и NeoForge его не существует, поэтому эти разделы не просто
   * пустуют, а не показываются вовсе: вкладка, которая всегда отвечает
   * «недоступно», хуже её отсутствия.
   */
  const bukkit = moduleId === 'minecraft';

  const has = useCallback(
    (id: string) => !!plugins?.known.find((p) => p.id === id)?.installed,
    [plugins],
  );

  // Право быстрых действий у каждого модуля своё: на Forge-сервере кнопки
  // открывает `minecraft-forge.quick-commands`, а не право от Paper.
  const canAct = hasPermission(`${moduleId}.quick-commands`);

  return (
    <div className="space-y-4">
      <PlayerStats player={player} known={known ?? null} />

      {/* Тот же компонент, что и у вкладок сервера: и вид, и едущая подложка
          общие. Отдельная реализация здесь означала бы, что одинаковое на вид
          переключение ведёт себя по-разному в двух местах одного экрана. */}
      {bukkit && (
        <Tabs
          fill
          active={tab}
          onChange={(id) => setTab(id as typeof tab)}
          tabs={[
            { id: 'actions', label: t('mc.pd.tab.actions') },
            { id: 'inventory', label: t('mc.pd.tab.inventory') },
            { id: 'rights', label: t('mc.pd.tab.rights') },
          ]}
        />
      )}

      {tab === 'actions' && (
        <div className="space-y-4">
          <PlayerActions
            serverId={serverId}
            moduleId={moduleId}
            player={player}
            has={has}
            canAct={canAct}
            onChanged={onChanged}
            onPunish={onPunish}
          />

          {/* Гильдия — одной строкой, а не блоком: это справка «кто он на
              сервере», и разворачивать под неё отдельный раздел незачем.
              Полный состав и действия администрации живут во вкладке
              «Гильдии», куда эта строка и отсылает. */}
          {bukkit && hasPermission('minecraft.guilds.view') && player.uuid && (
            <PlayerGuild serverId={serverId} moduleId={moduleId} uuid={player.uuid} />
          )}

          {/* Известные IP — только тем, у кого есть отдельное право. У
              модератора его по умолчанию нет: адрес — личные данные, и для
              кика, бана и разбора жалоб он не нужен. */}
          {bukkit && hasPermission('minecraft.players.ips') && player.uuid && (
            <PlayerIpsPanel serverId={serverId} moduleId={moduleId} uuid={player.uuid} />
          )}

          {/* Валюта — отдельным блоком, а не четвёртой вкладкой: на телефоне
              вкладки и так делят ширину поровну, и четвёртая сделала бы
              подписи нечитаемыми. */}
          {bukkit && hasPermission('minecraft.economy.view') && (
            <div className="space-y-2 border-t border-border pt-4">
              <Label>{t('mc.pd.balance')}</Label>
              {player.uuid ? (
                <BalancePanel serverId={serverId} uuid={player.uuid} />
              ) : (
                <p className="text-sm text-muted">
                  {t('mc.pd.needUuid.balance')}
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
      {bukkit && tab === 'inventory' &&
        (plugins === null || plugins.available ? (
          <PlayerInventory serverId={serverId} name={player.name} />
        ) : (
          <p className="text-sm text-muted">
            {t('mc.pd.invUnavailable')}
          </p>
        ))}

      {bukkit && tab === 'rights' &&
        (player.uuid ? (
          <PermissionsPanel serverId={serverId} uuid={player.uuid} />
        ) : (
          <p className="text-sm text-muted">
            {t('mc.pd.needUuid.rights')}
          </p>
        ))}
    </div>
  );
}

/** Статистика: то, что уже отдаёт companion-плагин. */
function PlayerStats({
  player,
  known,
}: {
  player: MinecraftPlayerDto;
  known: MinecraftKnownPlayerDto | null;
}) {
  const t = useT();
  const cells: [string, string][] = [
    [t('mc.th.health'), player.health !== null ? `${(player.health / 2).toFixed(1)} ♥` : '—'],
    [t('mc.th.ping'), player.ping !== null ? t('mc.ms', { value: player.ping }) : '—'],
    [t('mc.pd.world'), player.world ?? '—'],
    [
      t('mc.pd.coords'),
      player.position
        ? `${Math.round(player.position.x)}, ${Math.round(player.position.y)}, ${Math.round(player.position.z)}`
        : '—',
    ],
  ];
  return (
    <Card className="flex flex-wrap gap-x-6 gap-y-2">
      <div className="min-w-0 basis-full">
        <div className="text-[11px] uppercase tracking-wide text-muted">{t('mc.pd.name')}</div>
        <PlayerName name={player.name} alias={known?.alias ?? null} op={known?.op ?? false} />
      </div>
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
  moduleId,
  has,
  canAct,
  onChanged,
  onPunish,
}: {
  serverId: string;
  moduleId: string;
  player: MinecraftPlayerDto;
  has: (id: string) => boolean;
  canAct: boolean;
  onChanged: () => void;
  onPunish: (kind: 'kick' | 'ban') => void;
}) {
  const t = useT();
  const { hasPermission } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [mode, setMode] = useState('survival');
  const [target, setTarget] = useState('');
  // Кого предлагать в поле телепорта. Себя из списка убираем: «телепорт
  // Steve к Steve» — не действие, а недоразумение.
  const online = useOnlinePlayers(serverId, true, moduleId).filter((n) => n !== player.name);

  async function runQuick(id: string, args: Record<string, string>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<{ output: string }>(`${base(moduleId, serverId)}/quick-commands/${id}`, {
        method: 'POST',
        body: JSON.stringify({ args }),
      });
      setResult(res.output || t('mc.pd.done'));
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
          {t('mc.pd.noQuick')}
        </p>
      )}

      <div className="space-y-2">
        <Label>{t('mc.pd.gamemode')}</Label>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={mode}
            onChange={setMode}
            options={[
              { value: 'survival', label: t('mc.gm.survival') },
              { value: 'creative', label: t('mc.gm.creative') },
              { value: 'adventure', label: t('mc.gm.adventure') },
              { value: 'spectator', label: t('mc.gm.spectator') },
            ]}
          />
          <ActionButton
            label={t('mc.pd.apply')}
            hint={t('mc.pd.applyHint')}
            requirement={null}
            available
            disabled={busy || !canAct}
            onClick={() => void runQuick('vanilla-gamemode', { mode, player: name })}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>{t('mc.pd.state')}</Label>
        <div className="flex flex-wrap gap-2">
          <ActionButton
            label={t('mc.pd.heal')}
            hint={t('mc.pd.healHint')}
            requirement="Essentials"
            available={has('Essentials')}
            disabled={busy || !canAct}
            onClick={() => void runQuick('ess-heal', { player: name })}
          />
          <ActionButton
            label={t('mc.pd.kill')}
            hint={t('mc.pd.killHint')}
            requirement={null}
            available
            variant="destructive"
            disabled={busy || !canAct}
            onClick={() => {
              if (!confirm(t('mc.pd.killConfirm', { name }))) return;
              void runQuick('vanilla-kill', { player: name });
            }}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>{t('mc.pd.teleport')}</Label>
        <div className="flex flex-wrap items-center gap-2">
          <PlayerPicker
            value={target}
            onChange={setTarget}
            players={online}
            placeholder={t('mc.pd.teleportTo')}
            // На телефоне поле занимает строку целиком, на десктопе —
            // прежняя узкая колонка рядом с кнопкой.
            className="min-w-0 flex-1 sm:w-[220px] sm:flex-none"
          />
          <ActionButton
            label={t('mc.pd.move')}
            hint={t('mc.pd.moveHint')}
            requirement={null}
            available
            disabled={busy || !canAct || !target.trim()}
            onClick={() => void runQuick('vanilla-tp', { player: name, target: target.trim() })}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label>{t('mc.pd.punish')}</Label>
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

      {hasPermission('minecraft.password.reset') && (
        <PasswordReset
          serverId={serverId}
          moduleId={moduleId}
          player={name}
          installed={has('AurumAuth')}
        />
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="whitespace-pre-wrap text-xs text-emerald-400">{result}</p>}
    </div>
  );
}

/**
 * Выбранные ячейки — в тело запроса на очистку.
 *
 * Три раздельных поля, а не один список номеров: основной инвентарь, броня и
 * вторая рука приходят разными массивами и нумеруются каждый со своего нуля,
 * так что «слот 3» без указания раздела означал бы сразу три разных места.
 */
function selectionToRequest(selected: Set<string>): MinecraftInventoryClearDto {
  const slots: number[] = [];
  const armor: number[] = [];
  let offhand = false;

  for (const key of selected) {
    const [area, raw] = key.split(':');
    if (area === 'main') slots.push(Number(raw));
    else if (area === 'armor') armor.push(Number(raw));
    else if (area === 'offhand') offhand = true;
  }
  return { slots, armor, offhand };
}

/**
 * Инвентарь конкретного игрока — ник уже известен, вводить его не нужно.
 *
 * Модуль здесь фиксированный, и это не недосмотр: инвентарь показывает
 * companion-плагин Bukkit, а он бывает только на Paper. Компонент и
 * рендерится только оттуда — см. флаг `bukkit` в PlayerDetail.
 *
 * Правка инвентаря отделена от просмотра отдельным правом: посмотреть чужой
 * инвентарь — рутина модерации, а полная очистка необратима.
 */
function PlayerInventory({ serverId, name }: { serverId: string; name: string }) {
  const t = useT();
  const apiText = useApiText();
  const { hasPermission } = useAuth();
  const canEdit = hasPermission('minecraft.inventory.edit');

  const [data, setData] = useState<MinecraftInventoryResponse | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  /** Ключи выбранных ячеек — вида `main:5`, `armor:3`, `offhand:0`. */
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [giveText, setGiveText] = useState('');
  const [giveResults, setGiveResults] = useState<MinecraftGiveResultDto[] | null>(null);
  const [giveErrors, setGiveErrors] = useState<string[]>([]);
  const [confirmWipe, setConfirmWipe] = useState(false);

  const load = useCallback(async () => {
    setError('');
    try {
      const fresh = await api<MinecraftInventoryResponse>(
        `${base('minecraft', serverId)}/inventory/${encodeURIComponent(name)}`,
      );
      setData(fresh);
      // Выбор сбрасываем вместе с данными: слот, который был выбран, после
      // перезагрузки может держать уже другой предмет.
      setSelected(new Set());
    } catch (e) {
      setError((e as Error).message);
    }
  }, [serverId, name]);

  useEffect(() => {
    setData(null);
    void load();
  }, [load]);

  if (error) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  if (!data.available) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted">{apiText(data.reason, { player: name })}</p>
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

  const toggle = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  const selectionProps = canEdit ? { selected, onToggle: toggle } : {};

  return (
    <div className="space-y-4">
      <div>
        <Label>{t('mc.pd.hotbar')}</Label>
        <div className="mt-1 max-w-[420px]">
          <InventoryGrid items={data.items} size={9} cols={9} area="main" {...selectionProps} />
        </div>
      </div>
      <div>
        <Label>{t('mc.pd.inventory')}</Label>
        <div className="mt-1 max-w-[420px]">
          <InventoryGrid
            items={data.items}
            size={27}
            cols={9}
            slotOffset={9}
            area="main"
            {...selectionProps}
          />
        </div>
      </div>
      <div className="flex gap-6">
        <div>
          <Label>{t('mc.pd.armor')}</Label>
          <div className="mt-1 w-[52px]">
            <InventoryGrid items={data.armor} size={4} cols={1} area="armor" {...selectionProps} />
          </div>
        </div>
        <div>
          <Label>{t('mc.pd.offhand')}</Label>
          <div className="mt-1 w-[52px]">
            <InventoryGrid
              items={data.offhand ? [data.offhand] : []}
              size={1}
              cols={1}
              area="offhand"
              {...selectionProps}
            />
          </div>
        </div>
      </div>

      {canEdit && (
        <div className="space-y-3 border-t border-border pt-3">
          {/* Выдача. Список, а не поле «предмет + количество»: набор выдают
              целиком, и десять отдельных отправок — это десять шансов
              ошибиться и десять записей в журнале вместо одной. */}
          <div className="space-y-1">
            <Label>{t('mc.pd.give')}</Label>
            <Textarea
              rows={3}
              className="font-mono text-xs"
              placeholder={'minecraft:stone 64\nminecraft:golden_apple 3\ndiamond'}
              value={giveText}
              onChange={(e) => setGiveText(e.target.value)}
            />
            <p className="text-[11px] text-muted">
              {t('mc.pd.giveHint')}
              выдастся один. Существование предмета проверяет игровой сервер, поэтому работают и
              предметы модов.
            </p>
            <Button size="sm" disabled={busy || !giveText.trim()} onClick={() => void give()}>
              {t('mc.pd.giveBtn')}
            </Button>
          </div>

          {giveErrors.length > 0 && (
            <ul className="space-y-0.5 text-xs text-red-400">
              {giveErrors.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          )}

          {/* Итог построчно: что-то могло не поместиться или оказаться
              опечаткой, и человеку нужно видеть, какая именно строка. */}
          {giveResults && (
            <ul className="space-y-0.5 text-xs">
              {giveResults.map((r, i) => (
                <li key={`${r.id}-${i}`} className={r.error ? 'text-amber-400' : 'text-emerald-400'}>
                  {r.id} ×{r.requested}
                  {r.error ? ` — ${r.error}` : t('mc.pd.given')}
                  {r.error && r.given > 0 ? t('mc.pd.givePartly', { count: r.given }) : ''}
                </li>
              ))}
            </ul>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={busy || selected.size === 0}
              onClick={() => void removeSelected()}
            >
              {t('mc.pd.clearSelected')}{selected.size > 0 ? ` (${selected.size})` : ''}
            </Button>
            <Button size="sm" variant="destructive" disabled={busy} onClick={() => setConfirmWipe(true)}>
              {t('mc.pd.clearAll')}
            </Button>
            <span className="text-[11px] text-muted">
              {selected.size === 0
                ? t('mc.pd.selectHint')
                : t('mc.pd.selectHint2')}
            </span>
          </div>

          {notice && <p className="text-xs text-emerald-400">{notice}</p>}
        </div>
      )}

      {/* Полная очистка — единственное здесь необратимое действие: вернуть
          стёртое панель не умеет. Поэтому она спрашивает подтверждение и
          называет игрока по нику, чтобы нельзя было очистить не того. */}
      {confirmWipe && (
        <Modal title={t('mc.pd.wipeTitle')} onClose={() => setConfirmWipe(false)}>
          <div className="space-y-3">
            <p className="text-sm">
              У игрока <b>{name}</b> будет стёрт весь инвентарь: хотбар, основные слоты, броня и
              вторая рука.
            </p>
            <p className="text-sm text-red-400">
              {t('mc.pd.wipeWarn')}
            </p>
            <div className="flex justify-end gap-2">
              <Button size="sm" variant="outline" onClick={() => setConfirmWipe(false)}>
                {t('common.cancel')}
              </Button>
              <Button size="sm" variant="destructive" disabled={busy} onClick={() => void wipe()}>
                {t('mc.pd.wipeYes')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );

  async function give() {
    const parsed = parseGiveList(giveText);
    setGiveErrors(parsed.errors);
    setGiveResults(null);
    setNotice('');
    if (parsed.items.length === 0) return;

    setBusy(true);
    try {
      const response = await api<MinecraftGiveResponse>(
        `${base('minecraft', serverId)}/inventory/${encodeURIComponent(name)}/give`,
        { method: 'POST', body: JSON.stringify({ items: parsed.items }) },
      );
      setGiveResults(response.results);
      // Поле очищаем только когда всё легло: иначе человеку придётся заново
      // набирать строки, которые не прошли.
      if (response.results.every((r) => !r.error)) setGiveText('');
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function removeSelected() {
    setBusy(true);
    setNotice('');
    setGiveResults(null);
    try {
      await api(`${base('minecraft', serverId)}/inventory/${encodeURIComponent(name)}/clear`, {
        method: 'POST',
        body: JSON.stringify(selectionToRequest(selected)),
      });
      setNotice(t('mc.pd.cleared', { count: selected.size }));
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function wipe() {
    setBusy(true);
    setNotice('');
    setGiveResults(null);
    try {
      await api(`${base('minecraft', serverId)}/inventory/${encodeURIComponent(name)}/clear`, {
        method: 'POST',
        body: JSON.stringify({ all: true }),
      });
      setConfirmWipe(false);
      setNotice(t('mc.pd.wiped'));
      await load();
    } catch (e) {
      setConfirmWipe(false);
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
}


/**
 * Сброс пароля игрока (плагин AurumAuth).
 *
 * Токен показывается ОДИН РАЗ: сервер хранит только его хеш и повторить не
 * сможет. Поэтому здесь есть кнопка «копировать» и явная подпись про срок —
 * человек должен унести токен отсюда сразу, а не возвращаться за ним.
 *
 * Без установленного AurumAuth кнопка не прячется, а гаснет с подсказкой —
 * как и остальные действия, зависящие от плагинов. Спрятанная кнопка не
 * объясняет, чего не хватает.
 */
function PasswordReset({
  serverId,
  moduleId,
  player,
  installed,
}: {
  serverId: string;
  moduleId: string;
  player: string;
  installed: boolean;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [issued, setIssued] = useState<MinecraftPasswordResetDto | null>(null);
  const [copied, setCopied] = useState(false);

  async function issue() {
    setBusy(true);
    setError('');
    setCopied(false);
    try {
      setIssued(
        await api<MinecraftPasswordResetDto>(
          `${base(moduleId, serverId)}/players/${encodeURIComponent(player)}/password-reset`,
          { method: 'POST' },
        ),
      );
    } catch (e) {
      setIssued(null);
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const minutes = issued
    ? Math.max(1, Math.round((new Date(issued.expiresAt).getTime() - Date.now()) / 60000))
    : 0;

  return (
    <div className="space-y-2">
      <Label>{t('mc.pd.password')}</Label>
      <div className="flex flex-wrap items-center gap-2">
        <ActionButton
          label={t(busy ? 'mc.pd.issuing' : issued ? 'mc.pd.issueNew' : 'mc.pd.resetPassword')}
          hint={t('mc.pd.resetHint')}
          requirement="AurumAuth"
          available={installed}
          disabled={busy}
          onClick={() => void issue()}
        />
        <span className="text-xs text-muted">
          Игрок вводит токен в игре: <code>/reset &lt;токен&gt;</code>
        </span>
      </div>

      {issued && (
        <Card className="border-primary/40">
          <div className="flex flex-wrap items-center gap-2">
            <code className="select-all break-all font-mono text-lg tracking-widest text-primary">
              {issued.token}
            </code>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                // clipboard может быть недоступен (не-HTTPS, отказ в правах) —
                // токен всё равно виден и выделяется целиком по клику.
                void navigator.clipboard
                  ?.writeText(issued.token)
                  .then(() => setCopied(true))
                  .catch(() => undefined);
              }}
            >
              {t(copied ? 'mc.pd.copied' : 'mc.pd.copy')}
            </Button>
          </div>
          <p className="mt-2 text-xs text-muted">
            {t('mc.pd.tokenLife', { minutes })}
          </p>
        </Card>
      )}

      {error && <ErrorText>{error}</ErrorText>}
    </div>
  );
}

/** Значок «плагина нет» для строки списка — чтобы это было видно заранее. */
export function MissingBadge({ label }: { label: string }) {
  return <Badge variant="outline">{label}</Badge>;
}

/**
 * Гильдия игрока — одна строка в карточке.
 *
 * Молчит, если игрок ни в какой гильдии не состоит ИЛИ плагина гильдий на
 * сервере нет: и то, и другое отдаётся одинаково пустым ответом, а для этой
 * строки разницы нет — показывать всё равно нечего. Отдельная надпись «плагин
 * не установлен» здесь была бы шумом в карточке каждого игрока.
 */
function PlayerGuild({
  serverId,
  moduleId,
  uuid,
}: {
  serverId: string;
  moduleId: string;
  uuid: string;
}) {
  const t = useT();
  const [membership, setMembership] = useState<MinecraftGuildMembershipDto | null>(null);

  useEffect(() => {
    let alive = true;
    api<MinecraftGuildMembershipDto | null>(
      `/api/modules/${moduleId}/servers/${serverId}/players/${uuid}/guild`,
    )
      .then((data) => {
        if (alive) setMembership(data);
      })
      .catch(() => {
        // Молча: раздел гильдий необязателен, и ошибка здесь не должна
        // портить карточку игрока красной строкой.
        if (alive) setMembership(null);
      });
    return () => {
      alive = false;
    };
  }, [serverId, moduleId, uuid]);

  if (!membership) return null;

  return (
    <div className="space-y-1 border-t border-border pt-4">
      <Label>{t('mc.pd.guild')}</Label>
      <p className="text-sm">
        <span className="font-medium">
          [{membership.guildTag}] {membership.guildName}
        </span>
        <span className="text-muted"> — {t(MINECRAFT_GUILD_RANK_KEYS[membership.rank])}</span>
      </p>
    </div>
  );
}
