import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  MINECRAFT_GUILD_RANK_TITLES,
  type MinecraftGuildDto,
  type MinecraftGuildMemberDto,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
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
