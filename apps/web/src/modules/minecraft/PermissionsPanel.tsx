import { useCallback, useEffect, useState } from 'react';
import type { MinecraftPermissionChangeDto, MinecraftPermissionsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, ErrorText, Input, Label, Spinner } from '../../components/ui';

/**
 * Права игрока через LuckPerms.
 *
 * Показывается вкладкой в карточке игрока и только если LuckPerms обнаружен:
 * панель не изображает работоспособность там, где её нет.
 *
 * Каждое действие — одно изменение (добавить группу, снять право). Сервер в
 * ответ присылает актуальное состояние целиком, поэтому список не нужно
 * перезапрашивать и он не может разойтись с реальностью.
 */
export function PermissionsPanel({ serverId, uuid }: { serverId: string; uuid: string }) {
  const { hasPermission } = useAuth();
  const [data, setData] = useState<MinecraftPermissionsDto | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [newGroup, setNewGroup] = useState('');
  const [newPermission, setNewPermission] = useState('');

  const canEdit = hasPermission('minecraft.permissions.edit');
  const base = `/api/modules/minecraft/servers/${serverId}/players/${uuid}/permissions`;

  const load = useCallback(() => {
    setError('');
    api<MinecraftPermissionsDto>(base)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [base]);

  useEffect(load, [load]);

  async function change(body: MinecraftPermissionChangeDto) {
    setBusy(true);
    setError('');
    try {
      const next = await api<MinecraftPermissionsDto>(base, {
        method: 'POST',
        body: JSON.stringify(body),
      });
      if (!next.available) {
        setError(next.reason ?? 'Не удалось изменить права');
        return;
      }
      setData(next);
      setNewGroup('');
      setNewPermission('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!data) return <Spinner />;

  if (!data.available) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted">{data.reason}</p>
        {data.code === 'requires-luckperms' && (
          <p className="text-xs text-muted">
            Установите LuckPerms на игровой сервер и перезапустите его — вкладка появится сама.
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && <ErrorText>{error}</ErrorText>}

      <div>
        <div className="mb-1 text-xs text-muted">
          Основная группа: <span className="font-medium text-neutral-100">{data.primaryGroup}</span>
        </div>
        <Label>Группы</Label>
        <div className="mt-1 flex flex-wrap items-center gap-1.5">
          {data.groups?.length === 0 && <span className="text-xs text-muted">групп нет</span>}
          {data.groups?.map((group) => (
            <Badge key={group} variant="default">
              {group}
              {canEdit && (
                <button
                  className="ml-1.5 opacity-70 hover:opacity-100"
                  title={`Убрать из группы ${group}`}
                  disabled={busy}
                  onClick={() => void change({ kind: 'group', key: group, remove: true })}
                >
                  ×
                </button>
              )}
            </Badge>
          ))}
        </div>
        {canEdit && (
          <div className="mt-2 flex gap-2">
            <Input
              value={newGroup}
              onChange={(e) => setNewGroup(e.target.value)}
              placeholder="Имя группы, напр. vip"
            />
            <Button
              size="sm"
              disabled={busy || !newGroup.trim()}
              onClick={() => void change({ kind: 'group', key: newGroup.trim(), value: true })}
            >
              Добавить
            </Button>
          </div>
        )}
      </div>

      <div>
        <Label>Права</Label>
        <div className="mt-1 space-y-1">
          {data.permissions?.length === 0 && (
            <span className="text-xs text-muted">отдельных прав нет — всё приходит из групп</span>
          )}
          {data.permissions?.map((node) => (
            <div
              key={node.permission}
              className="flex items-center justify-between gap-2 rounded border border-border px-2 py-1"
            >
              <span className="truncate font-mono text-xs">{node.permission}</span>
              <div className="flex items-center gap-2">
                {/* Явный запрет выглядит иначе, чем выдача: у LuckPerms это
                    разные состояния, и путать их нельзя. */}
                <Badge variant={node.value ? 'success' : 'destructive'}>
                  {node.value ? 'выдано' : 'запрещено'}
                </Badge>
                {canEdit && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() =>
                      void change({ kind: 'permission', key: node.permission, remove: true })
                    }
                  >
                    Снять
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
        {canEdit && (
          <div className="mt-2 flex gap-2">
            <Input
              value={newPermission}
              onChange={(e) => setNewPermission(e.target.value)}
              placeholder="Право, напр. essentials.fly"
            />
            <Button
              size="sm"
              disabled={busy || !newPermission.trim()}
              onClick={() =>
                void change({ kind: 'permission', key: newPermission.trim(), value: true })
              }
            >
              Выдать
            </Button>
            <Button
              size="sm"
              variant="destructive"
              disabled={busy || !newPermission.trim()}
              title="Явный запрет: перебивает право, полученное из группы"
              onClick={() =>
                void change({ kind: 'permission', key: newPermission.trim(), value: false })
              }
            >
              Запретить
            </Button>
          </div>
        )}
      </div>

      {!canEdit && (
        <p className="text-xs text-muted">
          У вашей роли нет права менять доступы — список показан только для просмотра.
        </p>
      )}
    </div>
  );
}
