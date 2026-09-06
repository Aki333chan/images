import { useCallback, useEffect, useState } from 'react';
import type { MinecraftPermissionChangeDto, MinecraftPermissionsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { useApiText, useT } from '../../i18n';

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
  const t = useT();
  const apiText = useApiText();
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
        setError(apiText(next.reason) || t('mc.perm.failed'));
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
        <p className="text-sm text-muted">{apiText(data.reason)}</p>
        {data.code === 'requires-luckperms' && (
          <p className="text-xs text-muted">{t('mc.perm.installLuckPerms')}</p>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && <ErrorText>{error}</ErrorText>}

      <div>
        <div className="mb-1 text-xs text-muted">
          {t('mc.perm.primary')} <span className="font-medium text-neutral-100">{data.primaryGroup}</span>
        </div>
        <Label>{t('mc.perm.groups')}</Label>
        <div className="mt-1 flex flex-wrap items-center gap-1.5">
          {data.groups?.length === 0 && <span className="text-xs text-muted">{t('mc.perm.noGroups')}</span>}
          {data.groups?.map((group) =>
            canEdit ? (
              // Своя «плашка» вместо Badge: крестик внутри Badge получался
              // 10×16 — в него не попасть пальцем. Здесь у него отдельная
              // область нажатия 40×40 на мобильном, а вид чипа сохранён.
              <span
                key={group}
                className="inline-flex items-center rounded-full bg-primary/15 py-0.5 pl-2.5 text-xs font-medium text-primary"
              >
                {group}
                <button
                  className="ml-0.5 flex h-10 w-10 items-center justify-center rounded-full text-base opacity-70 hover:bg-white/10 hover:opacity-100 sm:h-6 sm:w-6 sm:text-xs"
                  title={t('mc.perm.removeFrom', { group })}
                  aria-label={t('mc.perm.removeFrom', { group })}
                  disabled={busy}
                  onClick={() => void change({ kind: 'group', key: group, remove: true })}
                >
                  ×
                </button>
              </span>
            ) : (
              <Badge key={group} variant="default">
                {group}
              </Badge>
            ),
          )}
        </div>
        {canEdit && (
          <div className="mt-2 flex gap-2">
            <Input
              value={newGroup}
              onChange={(e) => setNewGroup(e.target.value)}
              placeholder={t('mc.perm.groupPlaceholder')}
            />
            <Button
              size="sm"
              disabled={busy || !newGroup.trim()}
              onClick={() => void change({ kind: 'group', key: newGroup.trim(), value: true })}
            >
              {t('common.add')}
            </Button>
          </div>
        )}
      </div>

      <div>
        <Label>{t('mc.perm.nodes')}</Label>
        <div className="mt-1 space-y-1">
          {data.permissions?.length === 0 && (
            <span className="text-xs text-muted">{t('mc.perm.noNodes')}</span>
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
                  {t(node.value ? 'mc.perm.granted' : 'mc.perm.denied')}
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
                    {t('mc.perm.revoke')}
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
              placeholder={t('mc.perm.nodePlaceholder')}
            />
            <Button
              size="sm"
              disabled={busy || !newPermission.trim()}
              onClick={() =>
                void change({ kind: 'permission', key: newPermission.trim(), value: true })
              }
            >
              {t('mc.perm.grant')}
            </Button>
            <Button
              size="sm"
              variant="destructive"
              disabled={busy || !newPermission.trim()}
              title={t('mc.perm.denyHint')}
              onClick={() =>
                void change({ kind: 'permission', key: newPermission.trim(), value: false })
              }
            >
              {t('mc.perm.deny')}
            </Button>
          </div>
        )}
      </div>

      {!canEdit && (
        <p className="text-xs text-muted">{t('mc.perm.readOnly')}</p>
      )}
    </div>
  );
}
