import { useCallback, useEffect, useState } from 'react';
import type { TicketDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Spinner, Textarea } from '../components/ui';
import { cn } from '../lib/cn';

export function TicketsPage() {
  const { me, hasPermission, ticketsVersion } = useAuth();
  const [tickets, setTickets] = useState<TicketDto[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api<TicketDto[]>('/api/tickets?status=OPEN').then(setTickets), []);

  // ticketsVersion растёт на каждом WS-событии tickets.updated — список живой.
  useEffect(() => {
    void load();
  }, [ticketsVersion, me, load]);

  const selected = tickets?.find((t) => t.id === selectedId) ?? null;

  async function respond() {
    if (!selected || !reply.trim()) return;
    setBusy(true);
    try {
      await api(`/api/tickets/${selected.id}/respond`, {
        method: 'POST',
        body: JSON.stringify({ text: reply.trim() }),
      });
      setReply('');
      // Не ждём WS-эха на собственное действие — обновляемся сразу.
      await load();
    } finally {
      setBusy(false);
    }
  }

  async function close() {
    if (!selected) return;
    await api(`/api/tickets/${selected.id}/close`, { method: 'POST' });
    setSelectedId(null);
    await load();
  }

  if (!tickets) return <Spinner />;

  return (
    <div>
      <h1 className="mb-4 text-xl font-bold">Тикеты</h1>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[320px_1fr]">
        <div className="space-y-2">
          {tickets.length === 0 && <p className="text-muted">Открытых тикетов нет 🎉</p>}
          {tickets.map((t) => (
            <button key={t.id} className="w-full text-left" onClick={() => setSelectedId(t.id)}>
              <Card
                className={cn(
                  'py-3 transition-colors hover:border-primary/50',
                  selectedId === t.id && 'border-primary/70',
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium">{t.playerNameCached}</span>
                  <Badge variant="outline">{t.serverName ?? t.serverId.slice(0, 8)}</Badge>
                </div>
                <p className="mt-1 truncate text-xs text-muted">
                  {t.messages[t.messages.length - 1]?.text}
                </p>
              </Card>
            </button>
          ))}
        </div>

        {selected ? (
          <Card className="flex flex-col">
            <div className="mb-3 flex items-center justify-between border-b border-border pb-3">
              <div>
                <div className="font-semibold">{selected.playerNameCached}</div>
                <div className="text-xs text-muted">
                  {selected.serverName} · {selected.playerUuid}
                </div>
              </div>
              {hasPermission('tickets.close') && (
                <Button size="sm" variant="destructive" onClick={() => void close()}>
                  Закрыть тикет
                </Button>
              )}
            </div>
            <div className="flex-1 space-y-2 overflow-y-auto">
              {selected.messages.map((m, i) => (
                <div
                  key={i}
                  className={cn(
                    'max-w-[80%] rounded-lg px-3 py-2 text-sm',
                    m.from === 'player'
                      ? 'bg-white/5'
                      : 'ml-auto bg-primary/15 text-primary-foreground text-neutral-100',
                  )}
                >
                  <p>{m.text}</p>
                  <p className="mt-1 text-[10px] text-muted">
                    {m.from === 'player' ? selected.playerNameCached : 'Ответ панели'} ·{' '}
                    {new Date(m.created_at).toLocaleString('ru-RU')}
                  </p>
                </div>
              ))}
            </div>
            {hasPermission('tickets.respond') && (
              <div className="mt-3 flex gap-2 border-t border-border pt-3">
                <Textarea
                  value={reply}
                  onChange={(e) => setReply(e.target.value)}
                  placeholder="Ответ игроку…"
                />
                <Button onClick={() => void respond()} disabled={busy || !reply.trim()}>
                  Отправить
                </Button>
              </div>
            )}
          </Card>
        ) : (
          <p className="text-muted">Выберите тикет слева.</p>
        )}
      </div>
    </div>
  );
}
