import { useEffect, useState } from 'react';
import { ROLE_LABELS, ROLES, type Role, type ServerDto, type UserAdminDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, Select, Spinner } from '../components/ui';

/**
 * Экран управления доступом (только ГМ): смена ролей и привязка серверов.
 * Изменения мгновенно доезжают до затронутого пользователя по WS
 * (permissions.updated) — без релогина.
 */
export function AccessControlPage() {
  const [users, setUsers] = useState<UserAdminDto[] | null>(null);
  const [servers, setServers] = useState<ServerDto[] | null>(null);

  useEffect(() => {
    void api<UserAdminDto[]>('/api/users').then(setUsers);
    void api<ServerDto[]>('/api/servers').then(setServers);
  }, []);

  async function setRole(user: UserAdminDto, role: Role) {
    const updated = await api<UserAdminDto>(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ role }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  async function toggleActive(user: UserAdminDto) {
    const updated = await api<UserAdminDto>(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ isActive: !user.isActive }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  async function toggleServer(user: UserAdminDto, serverId: string) {
    const next = user.serverIds.includes(serverId)
      ? user.serverIds.filter((id) => id !== serverId)
      : [...user.serverIds, serverId];
    const updated = await api<UserAdminDto>(`/api/users/${user.id}/servers`, {
      method: 'PUT',
      body: JSON.stringify({ serverIds: next }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  if (!users || !servers) return <Spinner />;

  return (
    <div>
      <h1 className="mb-4 text-xl font-bold">Управление доступом</h1>
      <div className="space-y-4">
        {users.map((user) => (
          <Card key={user.id}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-semibold">
                  {user.displayName}{' '}
                  {!user.isActive && <Badge variant="destructive">деактивирован</Badge>}{' '}
                  {user.totpEnabled && <Badge variant="success">2FA</Badge>}
                </div>
                <div className="text-xs text-muted">{user.email}</div>
              </div>
              <div className="flex items-center gap-2">
                <Select
                  value={user.role}
                  onChange={(v) => void setRole(user, v as Role)}
                  options={ROLES.map((r) => ({ value: r, label: ROLE_LABELS[r] }))}
                />
                <Button size="sm" variant="outline" onClick={() => void toggleActive(user)}>
                  {user.isActive ? 'Деактивировать' : 'Активировать'}
                </Button>
              </div>
            </div>

            {user.role !== 'OWNER' && (
              <div className="mt-3 border-t border-border pt-3">
                <p className="mb-2 text-xs text-muted">
                  Доступные сервера ({user.serverIds.length === 0 ? 'нет доступа' : user.serverIds.length}):
                </p>
                <div className="flex flex-wrap gap-2">
                  {servers.map((s) => {
                    const attached = user.serverIds.includes(s.id);
                    return (
                      <button key={s.id} onClick={() => void toggleServer(user, s.id)}>
                        <Badge variant={attached ? 'default' : 'outline'}>
                          {attached ? '✓ ' : ''}
                          {s.name}
                        </Badge>
                      </button>
                    );
                  })}
                  {servers.length === 0 && (
                    <span className="text-xs text-muted">Сервера ещё не синхронизированы</span>
                  )}
                </div>
              </div>
            )}
          </Card>
        ))}
      </div>
    </div>
  );
}
