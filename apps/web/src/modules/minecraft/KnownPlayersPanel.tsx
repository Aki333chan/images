import { useCallback, useEffect, useState } from 'react';
import type { MinecraftKnownPlayerDto, MinecraftKnownPlayersResponse } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { PlayerName } from './PlayerName';
import { lastSeenText } from './player-name';
import { useApiText, useI18n } from '../../i18n';

const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/**
 * Сколько записей просить за раз.
 *
 * Не «всех сразу» намеренно: игровой сервер читает ник каждой записи из её
 * файла, и на сервере с многолетней историей полный список — это тысячи
 * обращений к диску в главном потоке, то есть заметная пауза в игре.
 */
const PAGE = 50;

/**
 * Все, кто когда-либо заходил на сервер.
 *
 * Деление на зарегистрированных и незарегистрированных появляется, только
 * если на сервере стоит плагин авторизации. Без него делить не по чему, и
 * список показывается одним куском — это ожидаемое поведение, а не урезанное.
 */
export function KnownPlayersPanel({
  serverId,
  moduleId,
  onOpen,
  onLoaded,
}: {
  serverId: string;
  moduleId: string;
  /** Клик по строке открывает карточку игрока. */
  onOpen: (player: MinecraftKnownPlayerDto) => void;
  /**
   * Отдаёт загруженную страницу наверх: из неё вкладка берёт звёздочку
   * оператора и ник EssentialsX для таблицы онлайна. Игроки в сети стоят в
   * начале списка, поэтому первая страница их и покрывает.
   */
  onLoaded?: (players: MinecraftKnownPlayerDto[]) => void;
}) {
  const { t, formatDate } = useI18n();
  const apiText = useApiText();
  const [data, setData] = useState<MinecraftKnownPlayersResponse | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  /** Что напечатано в поле поиска — уходит на сервер с задержкой. */
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);

  // Поиск с задержкой: без неё каждая буква уходила бы отдельным запросом
  // на игровой сервер, а он читает список с диска.
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(search.trim());
      setOffset(0);
    }, 350);
    return () => clearTimeout(timer);
  }, [search]);

  const load = useCallback(() => {
    setLoading(true);
    setError('');
    const params = new URLSearchParams({ offset: String(offset), limit: String(PAGE) });
    if (query) params.set('query', query);
    return api<MinecraftKnownPlayersResponse>(
      `${base(moduleId, serverId)}/players/known?${params}`,
    )
      .then((r) => {
        setData(r);
        onLoaded?.(r.players);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
    // onLoaded намеренно не в зависимостях: вкладка передаёт стрелку, которая
    // пересоздаётся на каждый рендер, и запрос уходил бы в бесконечный цикл.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, moduleId, query, offset]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!data && !error) return <Spinner />;

  if (error) {
    return (
      <Card>
        <ErrorText>{error}</ErrorText>
        <Button size="sm" variant="outline" className="mt-3" onClick={() => void load()}>
          {t('common.retry')}
        </Button>
      </Card>
    );
  }

  if (data && !data.available) {
    return (
      <Card>
        <p className="text-sm text-muted">{apiText(data.reason) || t('mc.known.unavailable')}</p>
        {data.docsUrl && (
          <a
            href={data.docsUrl}
            target="_blank"
            rel="noreferrer"
            className="mt-2 inline-block text-sm underline"
          >
            {t('mc.known.docs')}
          </a>
        )}
      </Card>
    );
  }

  const players = data?.players ?? [];
  const total = data?.total ?? 0;
  const authAvailable = data?.authAvailable ?? false;

  // Деление — только когда есть плагин авторизации. Без него registered
  // приходит null у всех, и любая группировка была бы выдуманной.
  const groups: [string, MinecraftKnownPlayerDto[]][] = authAvailable
    ? [
        [t('mc.known.registered'), players.filter((p) => p.registered === true)],
        [t('mc.known.unregistered'), players.filter((p) => p.registered !== true)],
      ]
    : [['', players]];

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0 flex-1">
          <Label>{t('mc.known.search')}</Label>
          <Input
            aria-label={t('mc.known.search')}
            value={search}
            placeholder="Steve"
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <div className="text-sm text-muted">
          {t('mc.known.total', { count: total })}
          {!authAvailable && (
            <span
              className="ml-2 text-xs"
              title={t('mc.known.noAuthHint')}
            >
              {t('mc.known.noAuth')}
            </span>
          )}
        </div>
      </div>

      {loading && <Spinner />}

      {!loading && players.length === 0 && (
        <p className="text-muted">
          {t(query ? 'mc.known.notFound' : 'mc.known.empty')}
        </p>
      )}

      {!loading &&
        groups.map(([title, list]) =>
          list.length === 0 ? null : (
            <div key={title || 'all'} className="mb-4 last:mb-0">
              {title && (
                <div className="mb-2 text-[11px] uppercase tracking-wide text-muted">
                  {title}
                  {/* Число — только когда весь список на одной странице. На
                      второй и дальше list.length — это «сколько таких на
                      ЭТОЙ странице», а рядом стоит «Всего: 65», и два числа
                      читались бы как одно и то же. */}
                  {total <= PAGE && ` · ${list.length}`}
                </div>
              )}
              <ul className="divide-y divide-border">
                {list.map((p) => (
                  <li key={p.uuid}>
                    <button
                      type="button"
                      onClick={() => onOpen(p)}
                      className="flex w-full items-center gap-3 py-2 text-left hover:bg-white/5"
                    >
                      <PlayerName name={p.name} alias={p.alias} op={p.op} className="min-w-0 truncate" />
                      {p.online && (
                        <span className="shrink-0 text-xs text-emerald-400">{t('mc.known.online')}</span>
                      )}
                      <span className="ml-auto shrink-0 text-xs text-muted">
                        {p.online ? '' : lastSeenText(p.lastSeen, t, formatDate)}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ),
        )}

      {total > PAGE && (
        <div className="mt-3 flex items-center justify-between gap-2 border-t border-border pt-3">
          <Button
            size="sm"
            variant="outline"
            disabled={offset === 0 || loading}
            onClick={() => setOffset(Math.max(0, offset - PAGE))}
          >
            {t('mc.known.prev')}
          </Button>
          <span className="text-xs text-muted">
            {t('mc.known.range', { from: offset + 1, to: Math.min(offset + PAGE, total), total })}
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={offset + PAGE >= total || loading}
            onClick={() => setOffset(offset + PAGE)}
          >
            {t('mc.known.next')}
          </Button>
        </div>
      )}
    </Card>
  );
}
