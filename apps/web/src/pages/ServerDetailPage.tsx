import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { ServerDto } from '@aurum/shared';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Dot, Select, Spinner, Tabs } from '../components/ui';
import { IconBack, IconPlay, IconRestart, IconStop } from '../components/icons';
import { MODULE_REGISTRY, resolveSettings, resolveTab } from '../modules/registry';
import { ServerStats } from '../components/ServerStats';
import { PluginsPanel } from '../modules/minecraft/PluginsPanel';
import { ServerAddress } from '../components/ServerAddress';
import { refreshServerRuntime, useServerRuntime } from '../lib/server-runtime';
import { SERVER_TABS } from '../server-tabs/registry';
import { listCapabilities } from '@aurum/shared';
import { useT } from '../i18n';

/** Не пересекается с id capability: те приходят из манифеста модуля. */
const SETTINGS_TAB_ID = '__settings';

export function ServerDetailPage() {
  const t = useT();
  const { serverId = '' } = useParams();
  const navigate = useNavigate();
  const { me, modules, hasPermission, canSeeServer } = useAuth();
  const runtime = useServerRuntime(serverId);
  const [server, setServer] = useState<ServerDto | null>(null);
  const [activeTab, setActiveTab] = useState<string>('');
  /** Человек уже сам выбрал вкладку — автовыбор ниже больше не вмешивается. */
  const [tabPickedByUser, setTabPickedByUser] = useState(false);
  const [error, setError] = useState('');
  /** Отказ Pterodactyl по кнопке питания: молча его терять нельзя. */
  const [powerError, setPowerError] = useState('');

  // canSeeServer в зависимостях: если ГМ отвяжет этот сервер, доступ пропадёт
  // на лету и пользователя вернёт к списку.
  useEffect(() => {
    if (me && !canSeeServer(serverId)) {
      navigate('/servers', { replace: true });
      return;
    }
    // Другой сервер — снова открываем консоль: выбор вкладки относился к
    // предыдущему серверу, а не к этому.
    setTabPickedByUser(false);
    api<ServerDto>(`/api/servers/${serverId}`)
      .then(setServer)
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 403) navigate('/servers', { replace: true });
        else setError((e as Error).message);
      });
  }, [serverId, me]); // eslint-disable-line react-hooks/exhaustive-deps

  const manifest = useMemo(
    () => modules?.enabled.find((m) => m.id === server?.moduleId) ?? null,
    [modules, server?.moduleId],
  );

  /**
   * Вкладки страницы сервера.
   *
   * Их два источника, и смешивать их нельзя:
   *
   *   вкладки МОДУЛЯ — capabilities манифеста ∩ реестр компонентов ∩ права.
   *   Их состав зависит от игры: у Minecraft есть whitelist, у Palworld нет;
   *
   *   вкладки ЯДРА — общие возможности Pterodactyl: файлы, бэкапы, сеть,
   *   запуск, базы, расписания. Они не зависят от игры вообще и показываются
   *   даже когда модуль серверу не назначен: файл есть файл, а бэкап есть
   *   бэкап.
   *
   * Порядок: сначала игровое, потом общее. Модератор заходит смотреть
   * игроков, а не аллокации.
   */
  const tabs = useMemo(() => {
    const moduleTabs = !manifest
      ? []
      : listCapabilities(manifest).flatMap(({ capability, state }) => {
          const tab = resolveTab(manifest.id, capability);
          if (!tab) return [];
          if (tab.permission && !hasPermission(tab.permission)) return [];
          // id как string: ниже к списку добавляются вкладки, которых в
          // перечислении capability нет.
          return [{ id: capability as string, label: t(tab.labelKey), component: tab.component, state }];
        });

    // Настройки модуля — последними среди модульных: пользуются ими редко.
    const settings = manifest ? resolveSettings(manifest.id) : null;
    if (settings && hasPermission(settings.permission)) {
      moduleTabs.push({
        id: SETTINGS_TAB_ID,
        label: t(settings.labelKey),
        component: settings.component,
        state: true,
      });
    }

    const coreTabs = SERVER_TABS.filter((tab) => hasPermission(tab.permission)).map((tab) => ({
      id: `core:${tab.id}`,
      // Вкладки ядра переведены; у модульных подписи пока приходят из их
      // собственных реестров как есть — они в следующем заходе.
      label: t(tab.labelKey),
      component: tab.component,
      state: true as const,
    }));

    return [...moduleTabs, ...coreTabs];
  }, [manifest, hasPermission, t]);

  /** Виджет модуля на дашборде сервера (напр. быстрые команды Minecraft). */
  const dashboard = useMemo(() => {
    if (!manifest) return null;
    const widget = MODULE_REGISTRY[manifest.id]?.dashboard;
    if (!widget) return null;
    if (widget.permission && !hasPermission(widget.permission)) return null;
    return widget.component;
  }, [manifest, hasPermission]);

  /**
   * Какая вкладка открывается сама.
   *
   * КОНСОЛЬ, А НЕ ПЕРВАЯ ПОПАВШАЯСЯ. Открывая сервер, смотрят прежде всего на
   * то, что он пишет; файлы открывают, когда уже знают зачем.
   *
   * Здесь же чинится вот что: список вкладок собирается из манифеста модуля, а
   * манифест приезжает вместе с данными сервера, то есть ПОЗЖЕ первого
   * рендера. До его прихода список состоит из одних общих вкладок, первая из
   * которых — «Файлы». Прежний код выбирал tabs[0] и потом уже не трогал
   * выбор, потому что «Файлы» никуда не девались и оставались допустимыми, —
   * и панель стабильно открывалась на файлах. Поэтому автовыбор повторяется,
   * пока человек сам не переключил вкладку.
   */
  useEffect(() => {
    if (tabs.length === 0) return;
    const known = tabs.some((t) => t.id === activeTab);
    if (known && tabPickedByUser) return;
    const preferred = tabs.find((t) => t.id === 'console') ?? tabs[0];
    if (preferred && preferred.id !== activeTab) setActiveTab(preferred.id);
  }, [tabs, activeTab, tabPickedByUser]);

  if (error) return <p className="text-red-400">{error}</p>;
  if (!server) return <Spinner />;

  const active = tabs.find((t) => t.id === activeTab);
  const ActiveComponent = active?.component;
  const DashboardWidget = dashboard;

  return (
    <div className="space-y-4">
      {/* Возврат к списку. В боковом меню «Серверы» тоже ведут сюда, но на
          телефоне меню спрятано за гамбургером, и без этой ссылки уйти со
          страницы сервера можно только кнопкой «назад» браузера. */}
      <button
        type="button"
        onClick={() => navigate('/servers')}
        // min-h-11 на телефоне — та же нижняя граница тач-таргета, что и у
        // кнопок: на узком экране эта ссылка единственный способ уйти со
        // страницы сервера, меню спрятано за гамбургером.
        className="-ml-1.5 -mt-1 flex min-h-11 items-center gap-1.5 rounded-sm px-1.5 text-[11.5px] font-medium text-muted transition-colors hover:text-primary-200 sm:min-h-9"
      >
        <IconBack size={14} />
        {t('server.allServers')}
      </button>

      {/* Название и кнопки питания в столбик на телефоне: три кнопки плюс
          значок статуса в одну строку с заголовком не помещаются. */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0 space-y-1">
          <h1 className="truncate text-xl font-bold">{server.name}</h1>
          <p className="truncate text-xs text-muted">{server.pteroIdentifier}</p>
          {/* Адрес — крупно и отдельной строкой: это то, что спрашивают
              игроки, и то, что чаще всего приходится диктовать вслух. */}
          <ServerAddress address={server.address} />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* Значок показывает питание, а не запись в Pterodactyl: «active»
              там означает лишь «не заблокирован», и после остановки сервера
              он оставался бы зелёным. */}
          <PowerBadge state={runtime.state} />
          {server.status && server.status !== 'active' && (
            <Badge variant={server.status === 'missing' ? 'destructive' : 'outline'}>
              {server.status}
            </Badge>
          )}
          {hasPermission('servers.power') && (
            <>
              <Button
                size="sm"
                variant="outline"
                className="hover:border-ok/60 hover:bg-ok/10 hover:text-ok"
                onClick={() => void power('start')}
              >
                <IconPlay size={13} />
                {t('server.start')}
              </Button>
              <Button size="sm" variant="outline" onClick={() => void power('restart')}>
                <IconRestart size={13} />
                {t('server.restart')}
              </Button>
              <Button size="sm" variant="destructive" onClick={() => void power('stop')}>
                <IconStop size={13} />
                {t('server.stop')}
              </Button>
            </>
          )}
        </div>
      </div>

      {powerError && <p className="text-sm text-red-400">{powerError}</p>}

      {hasPermission('servers.manage') && (
        <Card className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <span className="text-sm text-muted">{t('server.module')}</span>
          <Select
            className="min-w-0 flex-1 sm:flex-none"
            value={server.moduleId ?? ''}
            onChange={(v) => void setModule(v || null)}
            options={[
              { value: '', label: t('server.module.none') },
              ...(modules?.enabled.map((m) => ({ value: m.id, label: m.displayName })) ?? []),
            ]}
          />
        </Card>
      )}

      <ServerStats
        serverId={server.id}
        moduleId={server.moduleId ?? null}
        canSeePerformance={hasPermission('minecraft.players.view')}
      />

      {manifest && DashboardWidget && (
        <DashboardWidget serverId={server.id} moduleId={manifest.id} capabilityState={true} />
      )}

      {!manifest ? (
        <p className="text-muted">
          {t('server.module.missing')}
          {hasPermission('servers.manage') && t('server.module.assignHint')}
        </p>
      ) : tabs.length === 0 ? (
        <p className="text-muted">{t('server.noTabs')}</p>
      ) : (
        <>
          <Tabs
            tabs={tabs}
            active={activeTab}
            onChange={(id) => {
              setTabPickedByUser(true);
              setActiveTab(id);
            }}
          />
          {ActiveComponent && active && (
            <ActiveComponent
              serverId={server.id}
              moduleId={manifest.id}
              capabilityState={active.state}
            />
          )}
          {/* Под вкладками, а не внутри одной из них: список отвечает на
              вопрос «почему у меня нет такой-то кнопки», который возникает
              на любой вкладке. */}
          {/* Управление установленными плагинами живёт во вкладке настроек:
              здесь достаточно списка поддерживаемых, а все файлы сервера
              открываются в нём по кнопке «Показать все плагины сервера». */}
          {manifest.id === 'minecraft' && <PluginsPanel serverId={server.id} />}
        </>
      )}
    </div>
  );

  async function power(signal: 'start' | 'stop' | 'restart') {
    setPowerError('');
    try {
      await api(`/api/servers/${server!.id}/power`, {
        method: 'POST',
        body: JSON.stringify({ signal }),
      });
    } catch (e) {
      setPowerError((e as Error).message);
      return;
    }
    // Спрашиваем состояние сразу и ещё раз через три секунды: сигнал уходит
    // мгновенно, а Wings переводит сервер в stopping/starting не в тот же миг,
    // и один только немедленный опрос показал бы ещё старое состояние.
    refreshServerRuntime(server!.id);
    setTimeout(() => refreshServerRuntime(server!.id), 3_000);
  }

  async function setModule(moduleId: string | null) {
    const updated = await api<ServerDto>(`/api/servers/${server!.id}/module`, {
      method: 'PUT',
      body: JSON.stringify({ moduleId }),
    });
    setServer(updated);
  }
}

/**
 * Состояние питания сервера.
 *
 * Именно питания: в Pterodactyl у сервера есть ещё и статус записи
 * (active / suspended / installing), и он остаётся «active» у выключенного
 * сервера. Показывать его как единственный значок значило бы светить зелёным
 * ровно тогда, когда сервер только что остановили.
 */
function PowerBadge({ state }: { state: string | null }) {
  const t = useT();
  if (state === null) return <Badge variant="outline">…</Badge>;
  const label: Record<string, string> = {
    running: t('server.state.running'),
    offline: t('server.state.offline'),
    starting: t('server.state.starting'),
    stopping: t('server.state.stopping'),
  };
  return (
    <Badge
      variant={state === 'running' ? 'success' : state === 'offline' ? 'outline' : 'warn'}
      className="text-[11.5px]"
    >
      {/* Огонёк рядом со словом: состояние сервера читают мельком, и цветная
          точка доносит его быстрее, чем слово успевает прочитаться. */}
      <Dot className={state === 'starting' || state === 'stopping' ? 'aurum-pulse' : undefined} />
      {label[state] ?? state}
    </Badge>
  );
}
