import { useCallback, useEffect, useState } from 'react';
import type { PteroBackupDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { IconDownload, IconPlus, IconTrash } from '../components/icons';
import type { ServerTabProps } from './registry';
import { useI18n } from '../i18n';

const base = (serverId: string) => `/api/servers/${serverId}/backups`;

/**
 * Бэкапы сервера.
 *
 * Список открыт и Модератору: убедиться, что ночной бэкап сделался, — обычная
 * дежурная проверка. Всё остальное — Админ: восстановление затирает сервер.
 */
export function BackupsTab({ serverId }: ServerTabProps) {
  const { t, formatDateTime } = useI18n();
  const { hasPermission } = useAuth();
  const canManage = hasPermission('backups.manage');

  const [list, setList] = useState<PteroBackupDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [ignored, setIgnored] = useState('');
  const [restoring, setRestoring] = useState<PteroBackupDto | null>(null);

  const load = useCallback(() => {
    setError('');
    return api<PteroBackupDto[]>(base(serverId))
      .then(setList)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
    // Свежесозданный бэкап делается в фоне — обновляем список, чтобы
    // «в работе» само сменилось на готовый размер.
    const timer = setInterval(() => void load(), 20000);
    return () => clearInterval(timer);
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError('');
    try {
      await action();
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function download(backup: PteroBackupDto) {
    setBusy(true);
    setError('');
    try {
      // Бэкап весит гигабайты, поэтому через панель он не идёт: панель
      // выдаёт подписанную ссылку на ноду, живущую пятнадцать минут, и
      // браузер качает напрямую.
      const { url } = await api<{ url: string }>(`${base(serverId)}/${backup.uuid}/download`, {
        method: 'POST',
      });
      window.open(url, '_blank', 'noopener');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!list && !error) return <Spinner />;

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">{t('backups.title')}</h2>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => void load()} disabled={busy}>
              {t('backups.refresh')}
            </Button>
            {canManage && (
              <Button size="sm" onClick={() => setCreating(true)} disabled={busy}>
                <IconPlus size={14} /> {t('backups.create')}
              </Button>
            )}
          </div>
        </div>

        <ErrorText>{error}</ErrorText>

        {list && list.length === 0 ? (
          <p className="text-muted">{t('backups.empty')}</p>
        ) : (
          <ul className="space-y-2">
            {list?.map((b) => (
              <li key={b.uuid} className="rounded-md border border-border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate font-medium">{b.name}</div>
                    <div className="text-[11px] text-muted">
                      {new Date(b.createdAt).toLocaleString('ru-RU')}
                      {b.completedAt ? ` · ${formatSize(b.bytes, t)}` : ''}
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {!b.completedAt ? (
                      <Badge variant="outline">{t('backups.inProgress')}</Badge>
                    ) : !b.isSuccessful ? (
                      // Неудавшийся бэкап опаснее отсутствующего: на него
                      // рассчитывают, а восстановить из него нечего.
                      <Badge variant="destructive">{t('backups.failed')}</Badge>
                    ) : (
                      <Badge variant="success">{t('backups.ready')}</Badge>
                    )}
                    {b.isLocked && <Badge variant="outline">{t('backups.locked')}</Badge>}

                    {b.isSuccessful && b.completedAt && (
                      <Button size="sm" variant="ghost" title={t('backups.download')} disabled={busy} onClick={() => void download(b)}>
                        <IconDownload size={14} />
                      </Button>
                    )}
                    {canManage && (
                      <>
                        <Button
                          size="sm"
                          variant="ghost"
                          title={t(b.isLocked ? 'backups.unlock' : 'backups.lock')}
                          disabled={busy}
                          onClick={() =>
                            void run(() => api(`${base(serverId)}/${b.uuid}/lock`, { method: 'POST' }))
                          }
                        >
                          {b.isLocked ? '🔓' : '🔒'}
                        </Button>
                        {b.isSuccessful && b.completedAt && (
                          <Button size="sm" variant="outline" disabled={busy} onClick={() => setRestoring(b)}>
                            {t('backups.restore')}
                          </Button>
                        )}
                        <Button
                          size="sm"
                          variant="destructive"
                          // Заблокированный бэкап Pterodactyl удалить не даст:
                          // кнопка выключена, а не ведёт в отказ.
                          disabled={busy || b.isLocked}
                          title={t(b.isLocked ? 'backups.unlockFirst' : 'backups.delete')}
                          onClick={() => {
                            if (!confirm(t('backups.confirmDelete', { name: b.name }))) return;
                            void run(() => api(`${base(serverId)}/${b.uuid}`, { method: 'DELETE' }));
                          }}
                        >
                          <IconTrash size={14} />
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating && (
        <Modal title={t('backups.new')} onClose={() => setCreating(false)}>
          <div className="space-y-3">
            <div>
              <Label>{t('backups.nameOptional')}</Label>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t('backups.namePlaceholder')}
                autoFocus
              />
              <p className="mt-1 text-[11px] text-muted">
                {t('backups.nameHint')}
              </p>
            </div>
            <div>
              <Label>{t('backups.ignore')}</Label>
              <Input
                value={ignored}
                onChange={(e) => setIgnored(e.target.value)}
                placeholder="*.log"
              />
              <p className="mt-1 text-[11px] text-muted">
                {t('backups.ignoreHint')}
              </p>
            </div>
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button variant="ghost" onClick={() => setCreating(false)} disabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button
                disabled={busy}
                onClick={() => {
                  setCreating(false);
                  void run(() =>
                    api(base(serverId), {
                      method: 'POST',
                      body: JSON.stringify({ name: name.trim(), ignored: ignored.trim() }),
                    }),
                  ).then(() => setName(''));
                }}
              >
                {t('backups.create')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {restoring && (
        <Modal title={t('backups.restoreTitle', { name: restoring.name })} onClose={() => setRestoring(null)}>
          <div className="space-y-3">
            <p className="rounded-md bg-red-500/10 px-3 py-2 text-xs text-red-400">
              {t('backups.restoreWarning', { date: formatDateTime(restoring.createdAt) })}
            </p>
            <div className="flex flex-col gap-2">
              {/* Два разных исхода, и выбрать за человека нельзя: с очисткой
                  теряется всё новое, без очистки файлы смешиваются. */}
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => {
                  setRestoring(null);
                  void run(() =>
                    api(`${base(serverId)}/${restoring.uuid}/restore`, {
                      method: 'POST',
                      body: JSON.stringify({ truncate: true }),
                    }),
                  );
                }}
              >
                {t('backups.wipeAndRestore')}
              </Button>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => {
                  setRestoring(null);
                  void run(() =>
                    api(`${base(serverId)}/${restoring.uuid}/restore`, {
                      method: 'POST',
                      body: JSON.stringify({ truncate: false }),
                    }),
                  );
                }}
              >
                {t('backups.mergeRestore')}
              </Button>
              <Button variant="ghost" onClick={() => setRestoring(null)}>
                {t('common.cancel')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

/**
 * Размер в понятных единицах.
 *
 * Переводчик приходит аргументом: функция вне компонента, а хук снаружи
 * компонента звать нельзя. Единицы переводятся — «КБ» и «KB» это разные
 * подписи, и польскому админу вторая понятнее.
 */
function formatSize(bytes: number, unit: (key: string) => string): string {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} ${unit('size.kb')}`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} ${unit('size.mb')}`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ${unit('size.gb')}`;
}
