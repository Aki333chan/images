import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { ServerDto } from '@aurum/shared';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Select, Spinner, Tabs } from '../components/ui';
import { MODULE_TAB_REGISTRY } from '../modules/registry';

export function ServerDetailPage() {
  const { serverId = '' } = useParams();
  const navigate = useNavigate();
  const { me, modules, hasPermission, canSeeServer } = useAuth();
  const [server, setServer] = useState<ServerDto | null>(null);
  const [activeTab, setActiveTab] = useState<string>('');
  const [error, setError] = useState('');

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
    const registry = MODULE_TAB_REGISTRY[manifest.id] ?? {};
    return manifest.capabilities.flatMap((cap) => {
      const tab = registry[cap];
      if (!tab) return [];
      if (tab.permission && !hasPermission(tab.permission)) return [];
      return [{ id: cap, label: tab.label, component: tab.component }];
    });
  }, [manifest, hasPermission]);

  useEffect(() => {
    if (tabs.length > 0 && !tabs.some((t) => t.id === activeTab)) {
      setActiveTab(tabs[0]?.id ?? '');
    }
  }, [tabs, activeTab]);

  if (error) return <p className="text-red-400">{error}</p>;
  if (!server) return <Spinner />;

  const ActiveComponent = tabs.find((t) => t.id === activeTab)?.component;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold">{server.name}</h1>
          <p className="text-xs text-muted">
            {server.pteroIdentifier} · нода {server.node ?? '—'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={server.status === 'active' ? 'success' : 'outline'}>
            {server.status ?? '—'}
          </Badge>
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

      {hasPermission('servers.manage') && (
        <Card className="flex items-center gap-3">
          <span className="text-sm text-muted">Игровой модуль:</span>
          <Select
            value={server.moduleId ?? ''}
            onChange={(v) => void setModule(v || null)}
            options={[
              { value: '', label: '— не назначен —' },
              ...(modules?.enabled.map((m) => ({ value: m.id, label: m.displayName })) ?? []),
            ]}
          />
        </Card>
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
          {ActiveComponent && <ActiveComponent serverId={server.id} moduleId={manifest.id} />}
        </>
      )}
    </div>
  );

  async function power(signal: 'start' | 'stop' | 'restart') {
    await api(`/api/servers/${server!.id}/power`, {
      method: 'POST',
      body: JSON.stringify({ signal }),
    });
  }

  async function setModule(moduleId: string | null) {
    const updated = await api<ServerDto>(`/api/servers/${server!.id}/module`, {
      method: 'PUT',
      body: JSON.stringify({ moduleId }),
    });
    setServer(updated);
  }
}
