import { useCallback, useEffect, useState } from 'react';
import type { PteroDatabaseDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Button, Card, ErrorText, Input, Label, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { IconCopy, IconPlus, IconTrash } from '../components/icons';
import type { ServerTabProps } from './registry';
import { useT } from '../i18n';

const base = (serverId: string) => `/api/servers/${serverId}/databases`;

/**
 * Базы данных сервера.
 *
 * Пароль в списке НЕ приходит — только по явному запросу и в ответе на
 * создание. Так он не оседает в браузере при каждом открытии вкладки, а в
 * аудите видно отдельно, кто именно смотрел креденшлы.
 */
export function DatabasesTab({ serverId }: ServerTabProps) {
  const t = useT();
  const [list, setList] = useState<PteroDatabaseDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [remote, setRemote] = useState('%');
  const [credentials, setCredentials] = useState<PteroDatabaseDto | null>(null);

  const load = useCallback(() => {
    setError('');
    return api<PteroDatabaseDto[]>(base(serverId))
      .then(setList)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
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

  async function showCredentials(db: PteroDatabaseDto) {
    setBusy(true);
    setError('');
    try {
      setCredentials(await api<PteroDatabaseDto>(`${base(serverId)}/${db.id}/credentials`));
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
          <h2 className="font-semibold">{t('databases.title')}</h2>
          <Button size="sm" onClick={() => setCreating(true)} disabled={busy}>
            <IconPlus size={14} /> {t('databases.create')}
          </Button>
        </div>

        <ErrorText>{error}</ErrorText>

        {list && list.length === 0 ? (
          <p className="text-muted">{t('databases.empty')}</p>
        ) : (
          <ul className="space-y-2">
            {list?.map((db) => (
              <li key={db.id} className="rounded-md border border-border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate font-medium">{db.name}</div>
                    <div className="break-all font-mono text-[11px] text-muted">
                      {db.host.address}:{db.host.port} · {db.username} · {t('databases.from', { hosts: db.connectionsFrom })}
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" variant="outline" disabled={busy} onClick={() => void showCredentials(db)}>
                      {t('databases.credentials')}
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={busy}
                      onClick={() => {
                        if (!confirm(t('databases.confirmDelete', { name: db.name }))) return;
                        void run(() => api(`${base(serverId)}/${db.id}`, { method: 'DELETE' }));
                      }}
                    >
                      <IconTrash size={14} />
                    </Button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating && (
        <Modal title={t('databases.new')} onClose={() => setCreating(false)}>
          <div className="space-y-3">
            <div>
              <Label>{t('databases.name')}</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="survival" autoFocus />
              <p className="mt-1 text-[11px] text-muted">
                {t('databases.name.hint')}
              </p>
            </div>
            <div>
              <Label>{t('databases.allowed')}</Label>
              <Input value={remote} onChange={(e) => setRemote(e.target.value)} placeholder="%" />
              <p className="mt-1 text-[11px] text-muted">
                {t('databases.allowed.hint')}
              </p>
            </div>
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button variant="ghost" onClick={() => setCreating(false)} disabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button
                disabled={busy || name.trim().length < 3}
                onClick={async () => {
                  setBusy(true);
                  setError('');
                  try {
                    // Пароль приходит только в ответе на создание — сразу
                    // показываем его, второго такого случая не будет.
                    const created = await api<PteroDatabaseDto>(base(serverId), {
                      method: 'POST',
                      body: JSON.stringify({ name: name.trim(), remote: remote.trim() || '%' }),
                    });
                    setCreating(false);
                    setName('');
                    setCredentials(created);
                    await load();
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                {t('databases.create')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {credentials && (
        <Modal title={t('databases.credentialsOf', { name: credentials.name })} onClose={() => setCredentials(null)}>
          <div className="space-y-3">
            <Field label={t('databases.host')} value={`${credentials.host.address}:${credentials.host.port}`} />
            <Field label={t('databases.db')} value={credentials.name} />
            <Field label={t('databases.user')} value={credentials.username} />
            <Field label={t('databases.password')} value={credentials.password ?? '—'} />
            <Button
              variant="outline"
              onClick={() =>
                void api(`${base(serverId)}/${credentials.id}/rotate-password`, { method: 'POST' })
                  .then((db) => setCredentials(db as PteroDatabaseDto))
                  .catch((e: Error) => setError(e.message))
              }
            >
              {t('databases.rotate')}
            </Button>
          </div>
        </Modal>
      )}
    </div>
  );
}

/** Поле креденшлов с копированием: перепечатывать пароль руками — так себе занятие. */
function Field({ label, value }: { label: string; value: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <div>
      <Label>{label}</Label>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded-md border border-border bg-surface px-2 py-2 font-mono text-xs">
          {value}
        </code>
        <Button
          size="sm"
          variant="ghost"
          title={t('common.copy')}
          onClick={() => {
            void navigator.clipboard.writeText(value);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
        >
          {copied ? '✓' : <IconCopy size={14} />}
        </Button>
      </div>
    </div>
  );
}
