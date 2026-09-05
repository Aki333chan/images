import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  MINECRAFT_BONUS_KEYS,
  MINECRAFT_BONUS_TYPES,
  MINECRAFT_GUILD_RANK_KEYS,
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
import { useApiText, useI18n, useT } from '../../i18n';
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

/**
 * Переводчик, который принимают функции вне компонентов.
 *
 * Хук в них не позовёшь, а зашивать русский текст — значит получить польскую
 * панель с русскими подписями бонусов.
 */
type Translate = (key: string, values?: Record<string, string | number>) => string;

/** Ранг цветом: лидера должно быть видно, не читая подпись. */
function RankBadge({ rank }: { rank: MinecraftGuildMemberDto['rank'] }) {
  const t = useT();
  const variant = rank === 'leader' ? 'warn' : rank === 'officer' ? 'default' : 'outline';
  return <Badge variant={variant}>{t(MINECRAFT_GUILD_RANK_KEYS[rank])}</Badge>;
}

export function MinecraftGuildsTab({ serverId, moduleId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const t = useT();
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
          <Label>{t('mc.g.search')}</Label>
          <Input
            value={query}
            placeholder={t('mc.g.searchPlaceholder')}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <Button variant="outline" onClick={() => void load()}>
          {t('common.refresh')}
        </Button>
      </div>

      {error && <ErrorText>{error}</ErrorText>}

      {guilds.length === 0 && !error && (
        <Card>
          <p className="text-sm text-muted">{t('mc.g.emptyHint')}</p>
        </Card>
      )}

      {shown.length === 0 && guilds.length > 0 && (
        <p className="text-sm text-muted">{t('mc.g.notFound')}</p>
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
                {t('mc.g.members', { count: guild.memberCount })} · {t('mc.g.leader', { name: guild.leaderName })}
                {guild.bankBalance > 0 && ` · ${t('mc.g.bankShort', { value: formatMoney(guild.bankBalance) })}`}
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
  const { t, formatDate } = useI18n();
  const apiText = useApiText();
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
          <span>{t('mc.g.leaderLabel', { name: guild.leaderName })}</span>
          <span>·</span>
          <span>{t('mc.g.created', { date: formatDate(guild.createdAt) })}</span>
          {/* Общак показываем, только если он есть: строка «0» на сервере без
              Vault выглядит как пропавшие деньги, а не как «банка нет». */}
          {guild.bankBalance > 0 && (
            <>
              <span>·</span>
              <span>{t('mc.g.bank', { value: formatMoney(guild.bankBalance) })}</span>
            </>
          )}
        </div>

        <div>
          <Label>{t('mc.g.roster', { count: guild.members.length })}</Label>
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
            <Label>{t('mc.g.admin')}</Label>
            <p className="text-xs text-muted">
              {t('mc.g.adminHintA')} <code>/guild admin</code> {t('mc.g.adminHintB')}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => setPrompt('transfer')}
              >
                {t('mc.g.transferLead')}
              </Button>
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setPrompt('remove')}>
                {t('mc.g.kickMember')}
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={busy}
                onClick={() => setConfirmDisband(true)}
              >
                {t('mc.g.disband')}
              </Button>
            </div>
          </div>
        )}

        {error && <ErrorText>{error}</ErrorText>}
        {result && <p className="text-xs text-emerald-400">{apiText(result)}</p>}
      </div>

      {prompt && (
        <PromptModal
          title={t(prompt === 'transfer' ? 'mc.g.promptTransfer' : 'mc.g.promptKick')}
          label={t('mc.g.memberNick')}
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
        <Modal title={t('mc.g.disbandTitle')} onClose={() => setConfirmDisband(false)}>
          <div className="space-y-3">
            {/* Подтверждение отдельным окном, а не одной кнопкой: роспуск
                необратим и уносит состав вместе с общаком, а кнопка стоит
                рядом с безобидными. */}
            <p className="text-sm">
              {t('mc.g.disbandWarn', { name: guild.name, count: guild.members.length })}
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setConfirmDisband(false)}>
                {t('common.cancel')}
              </Button>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => {
                  setConfirmDisband(false);
                  void act(`/guilds/${guild.id}/disband`, {}, true);
                }}
              >
                {t('mc.g.disband')}
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
const BONUS_DURATIONS: { value: string; labelKey: string; seconds: number }[] = [
  { value: 'forever', labelKey: 'mc.g.bonusForever', seconds: 0 },
  { value: '1h', labelKey: 'mc.g.bonus1h', seconds: 3600 },
  { value: '1d', labelKey: 'mc.g.bonus1d', seconds: 24 * 3600 },
  { value: '7d', labelKey: 'mc.g.bonus7d', seconds: 7 * 24 * 3600 },
  { value: '30d', labelKey: 'mc.g.bonus30d', seconds: 30 * 24 * 3600 },
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
  const { t, formatDateTime } = useI18n();
  const apiText = useApiText();
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
      <Label>{t('mc.g.bonuses')}</Label>

      {bonuses.length === 0 ? (
        <p className="text-xs text-muted">{t('mc.g.noBonuses')}</p>
      ) : (
        <ul className="divide-y divide-border rounded border border-border">
          {bonuses.map((bonus) => (
            <li key={bonus.type} className="flex items-center justify-between gap-2 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm">
                  {apiText(bonus.title)} · {describeMagnitude(bonus, t)}
                </p>
                <p className="text-xs text-muted">
                  {describeExpiry(bonus.expiresAt, t, formatDateTime)} · {t('mc.g.grantedBy', { name: bonus.grantedBy })}
                </p>
              </div>
              {canManage && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => void revoke(bonus)}
                >
                  {t('mc.g.revoke')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div className="space-y-2">
          <p className="text-xs text-muted">
            {t('mc.g.bonusHintA')}{' '}
            <strong className="font-medium">{t('mc.g.bonusHintStacks')}</strong>
            {t('mc.g.bonusHintB')}
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <div className="min-w-[10rem] flex-1">
              <Label>{t('mc.g.bonusKind')}</Label>
              <Select
                value={type}
                onChange={(v) => setType(v as MinecraftBonusType)}
                options={MINECRAFT_BONUS_TYPES.map((id) => ({
                  value: id,
                  label: t(MINECRAFT_BONUS_KEYS[id]),
                }))}
              />
            </div>
            <div className="w-24">
              <Label>{t('mc.g.bonusValue')}</Label>
              <Input
                value={magnitude}
                inputMode="decimal"
                onChange={(e) => setMagnitude(e.target.value)}
              />
            </div>
            <div className="min-w-[8rem]">
              <Label>{t('mc.g.bonusTerm')}</Label>
              <Select
                value={duration}
                onChange={setDuration}
                options={BONUS_DURATIONS.map((d) => ({ value: d.value, label: t(d.labelKey) }))}
              />
            </div>
            <Button size="sm" disabled={busy} onClick={() => void grant()}>
              {t('mc.g.grant')}
            </Button>
          </div>
        </div>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="text-xs text-emerald-400">{apiText(result)}</p>}
    </div>
  );

  async function grant() {
    // Запятая вместо точки — обычная опечатка русской раскладки, и отказывать
    // из-за неё незачем: понятно же, что имелось в виду.
    const value = Number(magnitude.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) {
      setError(t('mc.g.badValue'));
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
function describeMagnitude(bonus: MinecraftGuildBonusDto, t: Translate): string {
  if (bonus.multiplier) return `×${formatMultiplier(bonus.magnitude)}`;
  return t('mc.g.level', { value: Math.round(bonus.magnitude) });
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
function describeExpiry(
  expiresAt: string | null,
  t: Translate,
  formatDateTime: (value: string) => string,
): string {
  if (!expiresAt) return t('mc.g.permanent');
  const left = new Date(expiresAt).getTime() - Date.now();
  if (left <= 0) return t('mc.g.expired');

  // Округление к ближайшему, а не вниз. Выданный на неделю бонус живёт
  // 167 часов с копейками, и округление вниз показало бы «осталось 6 дн.»
  // сразу после выдачи — человек решил бы, что его обсчитали.
  const hours = left / 3600_000;
  const human =
    hours >= 48
      ? t('mc.g.leftDays', { count: Math.round(hours / 24) })
      : hours >= 1
        ? t('mc.g.leftHours', { count: Math.round(hours) })
        : t('mc.g.leftMinutes', { count: Math.max(1, Math.round(left / 60_000)) });
  return t('mc.g.left', { human, date: formatDateTime(expiresAt) });
}
