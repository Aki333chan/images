import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { ServerDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Spinner } from '../components/ui';

export function ServersPage() {
  const { me, modules, hasPermission } = useAuth();
  const [servers, setServers] = useState<ServerDto[] | null>(null);
  const [syncing, setSyncing] = useState(false);

  const load = () => void api<ServerDto[]>('/api/servers').then(setServers);

  // me в зависимостях: при live-изменении привязок список перезагружается
  // и пропавший сервер исчезает без релогина.
  useEffect(load, [me]);

  async function sync() {
    setSyncing(true);
    try {
      await api('/api/servers/sync', { method: 'POST' });
      load();
    } finally {
      setSyncing(false);
    }
  }

  if (!servers) return <Spinner />;

  const moduleName = (id: string | null) =>
    modules?.enabled.find((m) => m.id === id)?.displayName ?? null;

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-bold">Серверы</h1>
        {hasPermission('servers.manage') && (
          <Button size="sm" variant="outline" onClick={() => void sync()} disabled={syncing}>
            {syncing ? 'Синхронизация…' : 'Синхронизировать с Pterodactyl'}
          </Button>
        )}
      </div>
      {servers.length === 0 ? (
        <p className="text-muted">
          Нет доступных серверов. Запустите синхронизацию или попросите ГМ выдать доступ.
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {servers.map((s) => (
            <Link key={s.id} to={`/servers/${s.id}`}>
              <Card className="transition-colors hover:border-primary/50">
                <div className="flex items-start justify-between">
                  <div>
                    <div className="font-semibold">{s.name}</div>
                    <div className="mt-1 text-xs text-muted">{s.description || s.pteroIdentifier}</div>
                  </div>
                  <Badge
                    variant={
                      s.status === 'active' ? 'success' : s.status === 'missing' ? 'destructive' : 'outline'
                    }
                  >
                    {s.status ?? '—'}
                  </Badge>
                </div>
                <div className="mt-3 text-xs text-muted">
                  Модуль: {moduleName(s.moduleId) ?? <span className="italic">не назначен</span>}
                </div>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
