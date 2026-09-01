import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  MINECRAFT_BONUS_TITLES,
  MINECRAFT_BONUS_TYPES,
  MINECRAFT_GUILD_RANK_TITLES,
  type MinecraftBonusType,
  type MinecraftGuildBonusDto,
  type MinecraftGuildDto,
  type MinecraftGuildMemberDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import {
  Badge,
  Button,
  Card,
  ErrorText,
  Input,
  Label,
  Select,
  Spinner,
} from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { Modal, PromptModal } from './PlayerModal';

/**
 * Вкладка «Гильдии».
 *
 * Данные идут через companion, который спрашивает у AurumGuilds по его Java
 * API. Плагина может не быть — тогда список приходит пустым, и вкладка
 * объясняет, чего не хватает, вместо того чтобы показать «гильдий нет».
 * Отличить одно от другого можно только по тому, установлен ли плагин, —
 * поэтому список известных плагинов сюда и передаётся.
 */
const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/** Ранг цветом: лидера должно быть видно, не читая подпись. */
function RankBadge({ rank }: { rank: MinecraftGuildMemberDto['rank'] }) {
  const variant = rank === 'leader' ? 'warn' : rank === 'officer' ? 'default' : 'outline';
  return <Badge variant={variant}>{MINECRAFT_GUILD_RANK_TITLES[rank]}</Badge>;
}

export function MinecraftGuildsTab({ serverId, moduleId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [guilds, setGuilds] = useState<MinecraftGuildDto[] | null>(null);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const [open, setOpen] = useState<MinecraftGuildDto | null>(null);

  const canManage = hasPermission('minecraft.guilds.manage');

  const load = useCallback(async () => {
    setError('');
    try {
      setGuilds(await api<MinecraftGuildDto[]>(`${base(moduleId, serverId)}/guilds`));
    } catch (e) {
      setGuilds([]);
      setError((e as Error).message);
    }
  }, [moduleId, serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * Поиск на клиенте, а не запросом на каждую букву.
   *
   * Гильдий на сервере десятки, а не тысячи: список уже целиком в памяти
   * браузера, и ходить за ним заново на каждое нажатие клавиши значило бы
   * гонять запросы к игровому серверу ради работы, которую фильтр делает
   * мгновенно. Серверный поиск при этом есть — он понадобится, когда гильдий
   * станет много.
   */
  const shown = useMemo(() => {
    const key = query.trim().toLowerCase();
    if (!key || !guilds) return guilds ?? [];
    return guilds.filter(
      (guild) =>
        guild.name.toLowerCase().includes(key) || guild.tag.toLowerCase().includes(key),
    );
  }, [guilds, query]);

  if (guilds === null) return <Spinner />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-[12rem] flex-1">
          <Label>Поиск по названию или тегу</Label>
          <Input
            value={query}
            placeholder="Драконы или DRG"
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <Button variant="outline" onClick={() => void load()}>
          Обновить
        </Button>
      </div>

      {error && <ErrorText>{error}</ErrorText>}

      {guilds.length === 0 && !error && (
        <Card>
          <p className="text-sm text-muted">
            Гильдий нет. Если вы их ожидали — проверьте, что на игровом сервере установлен плагин
            AurumGuilds, а companion-плагин отвечает: без них панели неоткуда взять список.
          </p>
        </Card>
      )}

      {shown.length === 0 && guilds.length > 0 && (
        <p className="text-sm text-muted">По запросу ничего не нашлось.</p>
      )}

      <div className="grid gap-2 sm:grid-cols-2">
        {shown.map((guild) => (
          <Card key={guild.id}>
            <button
              type="button"
              className="w-full text-left"
              onClick={() => void openGuild(guild)}
            >
              <div className="flex items-center gap-2">
                <Badge>{guild.tag}</Badge>
                <span className="font-medium">{guild.name}</span>
              </div>
              <p className="mt-1 text-xs text-muted">
                {guild.memberCount} чел. · лидер {guild.leaderName}
                {guild.bankBalance > 0 && ` · общак ${formatMoney(guild.bankBalance)}`}
              </p>
            </button>
          </Card>
        ))}
      </div>

      {open && (
        <GuildCard
          serverId={serverId}
          moduleId={moduleId}
          guild={open}
          canManage={canManage}
          onClose={() => setOpen(null)}
          onChanged={() => {
            setOpen(null);
            void load();
          }}
        />
      )}
    </div>
  );

  /**
   * Состав приходит только в карточке.
   *
   * В списке его нет намеренно: тянуть по сотне участников ради строчки
   * «Драконы, 12 человек» — лишняя работа на каждый показ списка.
   */
  async function openGuild(guild: MinecraftGuildDto) {
    setError('');
    try {
      setOpen(await api<MinecraftGuildDto>(`${base(moduleId, serverId)}/guilds/${guild.id}`));
    } catch (e) {
      setError((e as Error).message);
    }
  }
}

/** Карточка гильдии: состав, общак и вмешательство администрации. */
function GuildCard({
  serverId,
  moduleId,
  guild,
  canManage,
  onClose,
  onChanged,
}: {
  serverId: string;
  moduleId: string;
  guild: MinecraftGuildDto;
  canManage: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  /** Какое действие ждёт ввода ника. null — ничего не ждёт. */
  const [prompt, setPrompt] = useState<'transfer' | 'remove' | null>(null);
  const [confirmDisband, setConfirmDisband] = useState(false);

  const act = useCallback(
    async (path: string, body: Record<string, unknown>, refresh: boolean) => {
      setBusy(true);
      setError('');
      setResult('');
      try {
        const response = await api<{ output: string }>(`${base(moduleId, serverId)}${path}`, {
          method: 'POST',
          body: JSON.stringify(body),
        });
        setResult(response.output);
        if (refresh) onChanged();
      } catch (e) {
        setError((e as Error).message);
      } finally {
        setBusy(false);
      }
    },
    [moduleId, serverId, onChanged],
  );

  return (
    <Modal title={`${guild.name} [${guild.tag}]`} onClose={onClose}>
      <div className="space-y-4">
        <div className="flex flex-wrap gap-2 text-xs text-muted">
          <span>Лидер: {guild.leaderName}</span>
          <span>·</span>
          <span>Создана {new Date(guild.createdAt).toLocaleDateString('ru-RU')}</span>
          {/* Общак показываем, только если он есть: строка «0» на сервере без
              Vault выглядит как пропавшие деньги, а не как «банка нет». */}
          {guild.bankBalance > 0 && (
            <>
              <span>·</span>
              <span>Общак: {formatMoney(guild.bankBalance)}</span>
            </>
          )}
        </div>

        <div>
          <Label>Состав ({guild.members.length})</Label>
          <ul className="mt-1 divide-y divide-border rounded border border-border">
            {guild.members.map((member) => (
              <li key={member.uuid} className="flex items-center justify-between gap-2 px-3 py-2">
                <span className="truncate text-sm">{member.name}</span>
                <RankBadge rank={member.rank} />
              </li>
            ))}
          </ul>
        </div>

        <GuildBonuses
          serverId={serverId}
          moduleId={moduleId}
          guildId={guild.id}
          canManage={canManage}
        />

        {canManage && (
          <div className="space-y-2 border-t border-border pt-4">
            <Label>Вмешательство администрации</Label>
            <p className="text-xs text-muted">
              Те же действия, что и командами <code>/guild admin</code> в игре. Каждое попадает в
              журнал панели и в лог игрового сервера.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => setPrompt('transfer')}
              >
                Передать лидерство
              </Button>
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setPrompt('remove')}>
                Исключить участника
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={busy}
                onClick={() => setConfirmDisband(true)}
              >
                Распустить
              </Button>
            </div>
          </div>
        )}

        {error && <ErrorText>{error}</ErrorText>}
        {result && <p className="text-xs text-emerald-400">{result}</p>}
      </div>

      {prompt && (
        <PromptModal
          title={prompt === 'transfer' ? 'Кого назначить лидером' : 'Кого исключить'}
          label="Ник участника"
          placeholder="Steve"
          onClose={() => setPrompt(null)}
          onSubmit={async (target) => {
            const action = prompt;
            if (action === 'transfer') {
              await act(`/guilds/${guild.id}/transfer`, { target: target.trim() }, true);
            } else {
              await act('/guilds/members/remove', { target: target.trim() }, true);
            }
          }}
        />
      )}

      {confirmDisband && (
        <Modal title="Распустить гильдию?" onClose={() => setConfirmDisband(false)}>
          <div className="space-y-3">
            {/* Подтверждение отдельным окном, а не одной кнопкой: роспуск
                необратим и уносит состав вместе с общаком, а кнопка стоит
                рядом с безобидными. */}
            <p className="text-sm">
              Гильдия «{guild.name}» будет удалена вместе с составом ({guild.members.length} чел.)
              и общаком. Отменить это нельзя.
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setConfirmDisband(false)}>
                Отмена
              </Button>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => {
                  setConfirmDisband(false);
                  void act(`/guilds/${guild.id}/disband`, {}, true);
                }}
              >
                Распустить
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Modal>
  );
}

/** Сумма без хвоста из нулей: «1200» читается быстрее, чем «1200.00». */
function formatMoney(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/**
 * Сколько действует бонус.
 *
 * Готовые сроки, а не поле для секунд: администратор думает «на неделю», а не
 * «на 604800», и любая арифметика в голове тут лишний повод ошибиться на
 * порядок. Постоянный бонус стоит первым — это то, что выдают за заслуги, а
 * временный обычно приходит от NPC-торговца, а не из панели.
 */
const BONUS_DURATIONS: { value: string; label: string; seconds: number }[] = [
  { value: 'forever', label: 'Навсегда', seconds: 0 },
  { value: '1h', label: 'На час', seconds: 3600 },
  { value: '1d', label: 'На сутки', seconds: 24 * 3600 },
  { value: '7d', label: 'На неделю', seconds: 7 * 24 * 3600 },
  { value: '30d', label: 'На месяц', seconds: 30 * 24 * 3600 },
];

/**
 * Бонусы гильдии: что действует и выдача новых.
 *
 * Грузятся отдельным запросом при открытии карточки, а не вместе со списком
 * гильдий: бонусы есть далеко не у всех, а спрашивать их для каждой строки
 * списка — лишний поход на игровой сервер ради данных, которые почти всегда
 * пустые.
 *
 * Плагина гильдий может не быть — тогда роут отвечает 404, и блок честно
 * говорит, что бонусы недоступны, вместо того чтобы показать пустой список,
 * неотличимый от «бонусов нет».
 */
function GuildBonuses({
  serverId,
  moduleId,
  guildId,
  canManage,
}: {
  serverId: string;
  moduleId: string;
  guildId: number;
  canManage: boolean;
}) {
  const path = `${base(moduleId, serverId)}/guilds/${guildId}/bonuses`;
  const [bonuses, setBonuses] = useState<MinecraftGuildBonusDto[] | null>(null);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [busy, setBusy] = useState(false);
  const [type, setType] = useState<MinecraftBonusType>('mining_speed');
  const [magnitude, setMagnitude] = useState('1.5');
  const [duration, setDuration] = useState('forever');

  const load = useCallback(async () => {
    setError('');
    try {
      setBonuses(await api<MinecraftGuildBonusDto[]>(path));
    } catch (e) {
      setBonuses([]);
      setError((e as Error).message);
    }
  }, [path]);

  useEffect(() => {
    void load();
  }, [load]);

  if (bonuses === null) return <Spinner />;

  return (
    <div className="space-y-2 border-t border-border pt-4">
      <Label>Бонусы гильдии</Label>

      {bonuses.length === 0 ? (
        <p className="text-xs text-muted">Бонусов нет.</p>
      ) : (
        <ul className="divide-y divide-border rounded border border-border">
          {bonuses.map((bonus) => (
            <li key={bonus.type} className="flex items-center justify-between gap-2 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm">
                  {bonus.title} · {describeMagnitude(bonus)}
                </p>
                <p className="text-xs text-muted">
                  {describeExpiry(bonus.expiresAt)} · выдал {bonus.grantedBy}
                </p>
              </div>
              {canManage && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => void revoke(bonus)}
                >
                  Снять
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div className="space-y-2">
          <p className="text-xs text-muted">
            Один бонус каждого вида: выдача поверх действующего заменяет его, а не складывается с
            ним. Множитель 1.5 значит «в полтора раза», для скорости и добычи — уровень эффекта
            (1, 2, 3). С зачарованиями бонус <strong className="font-medium">складывается</strong>:
            множитель считается от того, что выпало бы и так, уже с «Удачей» и «Добычей», — то есть
            чем лучше инструмент, тем больше даёт бонус.
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <div className="min-w-[10rem] flex-1">
              <Label>Вид</Label>
              <Select
                value={type}
                onChange={(v) => setType(v as MinecraftBonusType)}
                options={MINECRAFT_BONUS_TYPES.map((id) => ({
                  value: id,
                  label: MINECRAFT_BONUS_TITLES[id],
                }))}
              />
            </div>
            <div className="w-24">
              <Label>Величина</Label>
              <Input
                value={magnitude}
                inputMode="decimal"
                onChange={(e) => setMagnitude(e.target.value)}
              />
            </div>
            <div className="min-w-[8rem]">
              <Label>Срок</Label>
              <Select
                value={duration}
                onChange={setDuration}
                options={BONUS_DURATIONS.map((d) => ({ value: d.value, label: d.label }))}
              />
            </div>
            <Button size="sm" disabled={busy} onClick={() => void grant()}>
              Выдать
            </Button>
          </div>
        </div>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="text-xs text-emerald-400">{result}</p>}
    </div>
  );

  async function grant() {
    // Запятая вместо точки — обычная опечатка русской раскладки, и отказывать
    // из-за неё незачем: понятно же, что имелось в виду.
    const value = Number(magnitude.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) {
      setError('Величина — положительное число, например 1.5 или 2');
      return;
    }
    const seconds = BONUS_DURATIONS.find((d) => d.value === duration)?.seconds ?? 0;
    await send(() =>
      api<{ output: string }>(path, {
        method: 'POST',
        body: JSON.stringify({ type, magnitude: value, seconds }),
      }),
    );
  }

  async function revoke(bonus: MinecraftGuildBonusDto) {
    await send(() =>
      api<{ output: string }>(`${path}/${encodeURIComponent(bonus.type)}`, { method: 'DELETE' }),
    );
  }

  /**
   * Общая обвязка: и выдача, и снятие заканчиваются одинаково — сообщением от
   * плагина и перечитанным списком, потому что величину он мог подрезать до
   * своего потолка, и показать надо то, что действует на самом деле, а не то,
   * что мы попросили.
   */
  async function send(action: () => Promise<{ output: string }>) {
    setBusy(true);
    setError('');
    setResult('');
    try {
      setResult((await action()).output);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
}

/** «×1.5» или «уровень 2» — смотря что за величина. */
function describeMagnitude(bonus: MinecraftGuildBonusDto): string {
  if (bonus.multiplier) return `×${formatMultiplier(bonus.magnitude)}`;
  return `уровень ${Math.round(bonus.magnitude)}`;
}

/**
 * Множитель без хвоста из нулей.
 *
 * Отдельно от денег: «×1.50» выглядит как сумма, а не как «в полтора раза», —
 * у множителя нет копеек, и второй знак после запятой здесь только шумит.
 */
function formatMultiplier(value: number): string {
  return String(Number(value.toFixed(2)));
}

/**
 * Срок словами.
 *
 * Дата истечения сама по себе мало что говорит — «до 14.09» требует посмотреть
 * на календарь. Поэтому рядом остаётся и сама дата, и сколько до неё осталось.
 */
function describeExpiry(expiresAt: string | null): string {
  if (!expiresAt) return 'постоянный';
  const left = new Date(expiresAt).getTime() - Date.now();
  if (left <= 0) return 'истёк';

  // Округление к ближайшему, а не вниз. Выданный на неделю бонус живёт
  // 167 часов с копейками, и округление вниз показало бы «осталось 6 дн.»
  // сразу после выдачи — человек решил бы, что его обсчитали.
  const hours = left / 3600_000;
  const human =
    hours >= 48
      ? `${Math.round(hours / 24)} дн.`
      : hours >= 1
        ? `${Math.round(hours)} ч.`
        : `${Math.max(1, Math.round(left / 60_000))} мин.`;
  return `осталось ${human} (до ${new Date(expiresAt).toLocaleString('ru-RU')})`;
}
