import { useCallback, useEffect, useState } from 'react';
import type { PteroAllocationDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, ErrorText, Input, Spinner } from '../components/ui';
import { IconPlus, IconTrash } from '../components/icons';
import type { ServerTabProps } from './registry';

const base = (serverId: string) => `/api/servers/${serverId}/allocations`;

/**
 * Аллокации сервера — адреса и порты, по которым он доступен снаружи.
 *
 * Возможность самого Pterodactyl, к игровому модулю отношения не имеет.
 */
export function NetworkTab({ serverId }: ServerTabProps) {
  const [list, setList] = useState<PteroAllocationDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [notes, setNotes] = useState<Record<number, string>>({});

  const load = useCallback(() => {
    setError('');
    return api<PteroAllocationDto[]>(base(serverId))
      .then((data) => {
        setList(data);
        setNotes(Object.fromEntries(data.map((a) => [a.id, a.notes ?? ''])));
      })
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

  if (!list && !error) return <Spinner />;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Адреса сервера</h2>
        <Button
          size="sm"
          disabled={busy}
          onClick={() => void run(() => api(base(serverId), { method: 'POST' }))}
        >
          <IconPlus size={14} /> Добавить порт
        </Button>
      </div>

      {/* Порт выбирает сама панель: Client API не даёт указать конкретный.
          Сказать об этом заранее честнее, чем дать поле, которое ни на что
          не влияет. */}
      <p className="text-xs text-muted">
        Порт назначается автоматически из свободного пула ноды — выбрать конкретный
        Pterodactyl не позволяет. Основная аллокация та, которую сервер сообщает игрокам.
      </p>

      <ErrorText>{error}</ErrorText>

      {list && list.length === 0 ? (
        <p className="text-muted">У сервера нет ни одной аллокации.</p>
      ) : (
        <ul className="space-y-2">
          {list?.map((a) => (
            <li key={a.id} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <div className="font-mono text-sm">
                    {a.ipAlias || a.ip}:{a.port}
                  </div>
                  {/* Алиас показываем вместе с настоящим IP: подключаются по
                      имени, а разбираются с фаерволом по адресу. */}
                  {a.ipAlias && (
                    <div className="font-mono text-[11px] text-muted">
                      {a.ip}:{a.port}
                    </div>
                  )}
                </div>
                {a.isDefault ? (
                  <Badge variant="success">основная</Badge>
                ) : (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void run(() =>
                          api(`${base(serverId)}/${a.id}/primary`, { method: 'POST' }),
                        )
                      }
                    >
                      Сделать основной
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={busy}
                      onClick={() => {
                        if (!confirm(`Удалить ${a.ip}:${a.port}?`)) return;
                        void run(() => api(`${base(serverId)}/${a.id}`, { method: 'DELETE' }));
                      }}
                    >
                      <IconTrash size={14} />
                    </Button>
                  </div>
                )}
              </div>

              <div className="mt-2 flex flex-col gap-2 sm:flex-row">
                <Input
                  value={notes[a.id] ?? ''}
                  onChange={(e) => setNotes((prev) => ({ ...prev, [a.id]: e.target.value }))}
                  placeholder="Зачем этот порт: Dynmap, RCON, запасной…"
                />
                <Button
                  size="sm"
                  variant="outline"
                  className="sm:w-auto"
                  disabled={busy || (notes[a.id] ?? '') === (a.notes ?? '')}
                  onClick={() =>
                    void run(() =>
                      api(`${base(serverId)}/${a.id}/notes`, {
                        method: 'PUT',
                        body: JSON.stringify({ notes: notes[a.id] ?? '' }),
                      }),
                    )
                  }
                >
                  Сохранить
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
