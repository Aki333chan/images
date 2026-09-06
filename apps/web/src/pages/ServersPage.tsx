import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  DEFAULT_SERVER_LIST_PREFS,
  SERVER_SORTS,
  SERVER_SORT_KEYS,
  cpuUsage,
  formatBytesUsage,
  formatCpu,
  resourceTone,
  type ServerDto,
  type ServerListPrefsDto,
  type ServerMetricsDto,
  type ServerSort,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useT } from '../i18n';
import { useAuth } from '../lib/auth';
import { filterServers, isOnline, reorder, sortServers, type ServerRow } from '../lib/server-list';
import { Badge, Button, Card, Input, Select, Spinner } from '../components/ui';
import { IconSync } from '../components/icons';

/** Как часто обновляем метрики карточек. Снимки собирает крон раз в полминуты. */
const METRICS_POLL_MS = 20_000;

export function ServersPage() {
  const t = useT();
  const { me, modules, hasPermission } = useAuth();
  const [servers, setServers] = useState<ServerDto[] | null>(null);
  const [metrics, setMetrics] = useState<ServerMetricsDto[]>([]);
  const [prefs, setPrefs] = useState<ServerListPrefsDto>(DEFAULT_SERVER_LIST_PREFS);
  const [query, setQuery] = useState('');
  const [syncing, setSyncing] = useState(false);

  const load = useCallback(() => void api<ServerDto[]>('/api/servers').then(setServers), []);

  // me в зависимостях: при live-изменении привязок список перезагружается
  // и пропавший сервер исчезает без релогина.
  useEffect(load, [load, me]);

  useEffect(() => {
    api<ServerListPrefsDto>('/api/servers/list-prefs')
      .then(setPrefs)
      .catch(() => setPrefs(DEFAULT_SERVER_LIST_PREFS));
  }, [me]);

  // Метрики отдельно от списка: список меняется редко, цифры — постоянно.
  useEffect(() => {
    const tick = () => {
      if (document.hidden) return;
      api<ServerMetricsDto[]>('/api/servers/metrics')
        .then(setMetrics)
        .catch(() => undefined);
    };
    tick();
    const timer = setInterval(tick, METRICS_POLL_MS);
    return () => clearInterval(timer);
  }, [me]);

  const savePrefs = useCallback((next: ServerListPrefsDto) => {
    setPrefs(next);
    // Сохраняем в фоне: список уже переставился, и ждать ответ, чтобы
    // показать результат собственного перетаскивания, незачем.
    void api<ServerListPrefsDto>('/api/servers/list-prefs', {
      method: 'PUT',
      body: JSON.stringify(next),
    }).catch(() => undefined);
  }, []);

  async function sync() {
    setSyncing(true);
    try {
      await api('/api/servers/sync', { method: 'POST' });
      load();
    } finally {
      setSyncing(false);
    }
  }

  const rows: ServerRow[] = useMemo(() => {
    const byId = new Map(metrics.map((m) => [m.serverId, m]));
    return (servers ?? []).map((server) => ({ server, metrics: byId.get(server.id) ?? null }));
  }, [servers, metrics]);

  const visible = useMemo(
    () => sortServers(filterServers(rows, query), prefs.sort, prefs.order),
    [rows, query, prefs],
  );

  /**
   * Перетаскивание работает ТОЛЬКО в режиме «Свой порядок».
   *
   * В остальных порядок задан критерием: перетащенная карточка вернулась бы
   * на место при первом же обновлении метрик, и жест выглядел бы сломанным.
   */
  const manual = prefs.sort === 'manual';

  function onDrop(fromId: string, toId: string) {
    if (!manual || fromId === toId) return;
    const ids = visible.map((r) => r.server.id);
    const next = reorder(ids, ids.indexOf(fromId), ids.indexOf(toId));
    savePrefs({ ...prefs, order: next });
  }

  if (!servers) return <Spinner />;

  const moduleName = (id: string | null) =>
    modules?.enabled.find((m) => m.id === id)?.displayName ?? null;

  return (
    <div>
      {/* flex-wrap: «Синхронизировать с Pterodactyl» — 228 px, и вместе с
          заголовком в 375 px не влезает; без переноса кнопка вылезала за
          край экрана. */}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-bold">{t('servers.title')}</h1>
        {hasPermission('servers.manage') && (
          <Button size="sm" variant="outline" onClick={() => void sync()} disabled={syncing}>
            <IconSync size={14} className={syncing ? 'animate-spin' : undefined} />
            {t(syncing ? 'servers.syncing' : 'servers.sync')}
          </Button>
        )}
      </div>

      {servers.length > 0 && (
        <div className="mb-4 flex flex-col gap-2 sm:flex-row">
          <Input
            className="flex-1"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('servers.search')}
          />
          <Select
            className="sm:w-56"
            value={prefs.sort}
            onChange={(v) => savePrefs({ ...prefs, sort: v as ServerSort })}
            options={SERVER_SORTS.map((s) => ({ value: s, label: t(SERVER_SORT_KEYS[s]) }))}
          />
        </div>
      )}

      {manual && servers.length > 1 && (
        <p className="mb-3 text-xs text-muted">
          {t('servers.reorderHint')}
        </p>
      )}

      {servers.length === 0 ? (
        <p className="text-muted">
          {t('servers.empty')}
        </p>
      ) : visible.length === 0 ? (
        <p className="text-sm text-muted">{t('servers.notFound')}</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {visible.map((row) => (
            <ServerCard
              key={row.server.id}
              row={row}
              moduleName={moduleName(row.server.moduleId)}
              draggable={manual}
              onDrop={onDrop}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Карточка сервера со всем, ради чего раньше приходилось на него заходить:
 * загрузка ЦПУ, память «занято / лимит» и игроки онлайн.
 */
function ServerCard({
  row,
  moduleName,
  draggable,
  onDrop,
}: {
  row: ServerRow;
  moduleName: string | null;
  draggable: boolean;
  onDrop: (fromId: string, toId: string) => void;
}) {
  const t = useT();
  const { server, metrics } = row;
  const [over, setOver] = useState(false);
  // Перетаскивание карточки не должно открывать сервер: клик и перетаскивание
  // начинаются одинаково, и без этой отметки любой перенос заканчивался бы
  // переходом на страницу.
  const dragging = useRef(false);

  const cpu = metrics ? cpuUsage(metrics.cpuAbsolutePercent ?? 0, metrics.cpuLimitPercent) : null;
  const online = isOnline(row);

  const card = (
    <Card
      className={`h-full transition-[box-shadow,transform,border-color] duration-300 ease-panel ${
        over ? 'border-primary' : 'group-hover:border-transparent group-hover:shadow-lift'
      } ${draggable ? '' : 'group-hover:-translate-y-[3px]'}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-semibold">{server.name}</div>
          <div className="mt-1 truncate text-xs text-muted">
            {server.description || server.pteroIdentifier}
          </div>
        </div>
        <Badge
          variant={
            server.status === 'active' ? 'success' : server.status === 'missing' ? 'destructive' : 'outline'
          }
        >
          {server.status ?? '—'}
        </Badge>
      </div>

      {/* Адрес в карточке: чаще всего сервер ищут именно по нему,
          когда серверов больше одного. */}
      {server.address && (
        <div className="mt-2 break-all font-mono text-xs text-neutral-300">{server.address}</div>
      )}

      {/* Метрики. Пока снимка нет — строка не рисуется вовсе: три прочерка
          выглядели бы как «сервер сломан», хотя это просто первый заход. */}
      {metrics && cpu && (
        <div className="mt-3 grid grid-cols-3 gap-2">
          <CardMetric
            label={t('servers.cpu')}
            value={
              !online
                ? '—'
                : cpu.unlimited
                  ? `${cpu.absolutePercent.toFixed(0)}%`
                  : `${Math.round(cpu.percentOfLimit ?? 0)}%`
            }
            hint={online ? formatCpu(cpu, t) : t('servers.offline')}
            tone={online ? resourceTone(cpu.percentOfLimit) : 'unknown'}
          />
          <CardMetric
            label={t('servers.memory')}
            value={
              online && metrics.memoryBytes !== null
                ? formatBytesUsage(metrics.memoryBytes, metrics.memoryLimitBytes, t)
                : '—'
            }
          />
          <CardMetric
            label={t('servers.players')}
            value={
              metrics.playersOnline === null
                ? '—'
                : metrics.playersMax === null
                  ? String(metrics.playersOnline)
                  : `${metrics.playersOnline}/${metrics.playersMax}`
            }
          />
        </div>
      )}

      <div className="mt-3 text-xs text-muted">
        {t('servers.moduleLabel')} {moduleName ?? <span className="italic">{t('servers.module.none')}</span>}
      </div>
    </Card>
  );

  if (!draggable) {
    return (
      <Link to={`/servers/${server.id}`} className="group">
        {card}
      </Link>
    );
  }

  return (
    <div
      draggable
      onDragStart={(e) => {
        dragging.current = true;
        e.dataTransfer.setData('text/plain', server.id);
        e.dataTransfer.effectAllowed = 'move';
      }}
      onDragEnd={() => {
        // Через кадр: click приходит после dragend, и сбросив флаг сразу,
        // мы бы всё равно открыли сервер.
        setTimeout(() => (dragging.current = false), 0);
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        onDrop(e.dataTransfer.getData('text/plain'), server.id);
      }}
      className="group cursor-grab active:cursor-grabbing"
    >
      <Link
        to={`/servers/${server.id}`}
        onClick={(e) => {
          if (dragging.current) e.preventDefault();
        }}
      >
        {card}
      </Link>
    </div>
  );
}

function CardMetric({
  label,
  value,
  hint,
  tone = 'normal',
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: 'normal' | 'warn' | 'bad' | 'unknown';
}) {
  const color =
    tone === 'bad' ? 'text-red-400' : tone === 'warn' ? 'text-amber-400' : 'text-neutral-100';
  return (
    <div className="min-w-0">
      <div className="text-[10px] uppercase tracking-wide text-muted">{label}</div>
      <div className={`truncate text-sm font-semibold ${color}`}>{value}</div>
      {hint && <div className="truncate text-[10px] text-muted">{hint}</div>}
    </div>
  );
}
