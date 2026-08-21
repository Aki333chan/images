import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { ServerDto } from '@aurum/shared';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Select, Spinner, Tabs } from '../components/ui';
import { MODULE_REGISTRY, resolveSettings, resolveTab } from '../modules/registry';
import { ServerStats } from '../components/ServerStats';
import { PluginsPanel } from '../modules/minecraft/PluginsPanel';
import { ServerAddress } from '../components/ServerAddress';
import { refreshServerRuntime, useServerRuntime } from '../lib/server-runtime';
import { listCapabilities } from '@aurum/shared';

/** Не пересекается с id capability: те приходят из манифеста модуля. */
const SETTINGS_TAB_ID = '__settings';

export function ServerDetailPage() {
  const { serverId = '' } = useParams();
  const navigate = useNavigate();
  const { me, modules, hasPermission, canSeeServer } = useAuth();
  const runtime = useServerRuntime(serverId);
  const [server, setServer] = useState<ServerDto | null>(null);
  const [activeTab, setActiveTab] = useState<string>('');
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

  /** Вкладки: capabilities активного модуля ∩ реестр компонентов ∩ права. */
  const tabs = useMemo(() => {
    if (!manifest) return [];
    const capabilityTabs = listCapabilities(manifest).flatMap(({ capability, state }) => {
      const tab = resolveTab(manifest.id, capability);
      if (!tab) return [];
      if (tab.permission && !hasPermission(tab.permission)) return [];
      // id как string: ниже к списку добавляется вкладка настроек,
      // которой в перечислении capability нет.
      return [{ id: capability as string, label: tab.label, component: tab.component, state }];
    });

    // Настройки идут последними: пользуются ими редко, а место в ряду
    // вкладок нужнее тем, с чем работают каждый день.
    const settings = resolveSettings(manifest.id);
    if (settings && hasPermission(settings.permission)) {
      capabilityTabs.push({
        id: SETTINGS_TAB_ID,
        label: settings.label,
        component: settings.component,
        state: true,
      });
    }
    return capabilityTabs;
  }, [manifest, hasPermission]);

  /** Виджет модуля на дашборде сервера (напр. быстрые команды Minecraft). */
  const dashboard = useMemo(() => {
    if (!manifest) return null;
    const widget = MODULE_REGISTRY[manifest.id]?.dashboard;
    if (!widget) return null;
    if (widget.permission && !hasPermission(widget.permission)) return null;
    return widget.component;
  }, [manifest, hasPermission]);

  useEffect(() => {
    if (tabs.length > 0 && !tabs.some((t) => t.id === activeTab)) {
      setActiveTab(tabs[0]?.id ?? '');
    }
  }, [tabs, activeTab]);

  if (error) return <p className="text-red-400">{error}</p>;
  if (!server) return <Spinner />;

  const active = tabs.find((t) => t.id === activeTab);
  const ActiveComponent = active?.component;
  const DashboardWidget = dashboard;

  return (
    <div className="space-y-4">
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
              <Button size="sm" variant="outline" onClick={() => void power('start')}>
                Старт
              </Button>
              <Button size="sm" variant="outline" onClick={() => void power('restart')}>
                Рестарт
              </Button>
              <Button size="sm" variant="destructive" onClick={() => void power('stop')}>
                Стоп
              </Button>
            </>
          )}
        </div>
      </div>

      {powerError && <p className="text-sm text-red-400">{powerError}</p>}

      {hasPermission('servers.manage') && (
        <Card className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <span className="text-sm text-muted">Игровой модуль:</span>
          <Select
            className="min-w-0 flex-1 sm:flex-none"
            value={server.moduleId ?? ''}
            onChange={(v) => void setModule(v || null)}
            options={[
              { value: '', label: '— не назначен —' },
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
          Модуль не назначен или выключен — вкладки недоступны.
          {hasPermission('servers.manage') && ' Назначьте модуль выше.'}
        </p>
      ) : tabs.length === 0 ? (
        <p className="text-muted">Нет вкладок, доступных вашей роли.</p>
      ) : (
        <>
          <Tabs tabs={tabs} active={activeTab} onChange={setActiveTab} />
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
  if (state === null) return <Badge variant="outline">…</Badge>;
  const label: Record<string, string> = {
    running: 'работает',
    offline: 'выключен',
    starting: 'запускается',
    stopping: 'останавливается',
  };
  return (
    <Badge
      variant={
        state === 'running' ? 'success' : state === 'offline' ? 'outline' : 'default'
      }
    >
      {label[state] ?? state}
    </Badge>
  );
}
