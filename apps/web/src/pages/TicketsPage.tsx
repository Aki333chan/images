import { useCallback, useEffect, useState } from 'react';
import type { TicketDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, Spinner, Textarea } from '../components/ui';
import { cn } from '../lib/cn';
import { useI18n } from '../i18n';

export function TicketsPage() {
  const { t, formatDateTime } = useI18n();
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
      <h1 className="mb-4 text-xl font-bold">{t('nav.tickets')}</h1>
      {/* Ниже lg показывается что-то одно: список тикетов либо открытый
          тикет с кнопкой «назад». Иначе до переписки пришлось бы каждый раз
          прокручивать весь список. */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[320px_1fr]">
        <div className={`space-y-2 ${selected ? 'hidden lg:block' : ''}`}>
          {tickets.length === 0 && <p className="text-muted">{t('tickets.empty')}</p>}
          {tickets.map((ticket) => (
            <button
              key={ticket.id}
              className="w-full text-left"
              onClick={() => setSelectedId(ticket.id)}
            >
              <Card
                className={cn(
                  'py-3 transition-colors hover:border-primary/50',
                  selectedId === ticket.id && 'border-primary/70',
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium">{ticket.playerNameCached}</span>
                  <Badge variant="outline">
                    {ticket.serverName ?? ticket.serverId.slice(0, 8)}
                  </Badge>
                </div>
                <p className="mt-1 truncate text-xs text-muted">
                  {ticket.messages[ticket.messages.length - 1]?.text}
                </p>
              </Card>
            </button>
          ))}
        </div>

        {selected ? (
          <Card className="flex flex-col">
            <div className="mb-3 flex items-start justify-between gap-2 border-b border-border pb-3">
              <button
                type="button"
                onClick={() => setSelectedId(null)}
                aria-label={t('tickets.back')}
                className="-ml-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted hover:bg-white/5 lg:hidden"
              >
                ←
              </button>
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{selected.playerNameCached}</div>
                {/* break-all: UUID в 36 символов иначе растягивает карточку
                    шире экрана телефона. */}
                <div className="break-all text-xs text-muted">
                  {selected.serverName} · {selected.playerUuid}
                </div>
              </div>
              {hasPermission('tickets.close') && (
                <Button size="sm" variant="destructive" onClick={() => void close()}>
                  {t('tickets.close')}
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
                    {m.from === 'player' ? selected.playerNameCached : t('tickets.panelReply')} ·{' '}
                    {formatDateTime(m.created_at)}
                  </p>
                </div>
              ))}
            </div>
            {hasPermission('tickets.respond') && (
              // На телефоне кнопка под полем и во всю ширину: рядом с
              // текстовой областью на неё остаётся полоска в полсотни пикселей.
              <div className="mt-3 flex flex-col gap-2 border-t border-border pt-3 sm:flex-row">
                <Textarea
                  value={reply}
                  onChange={(e) => setReply(e.target.value)}
                  placeholder={t('tickets.replyPlaceholder')}
                />
                <Button
                  className="w-full sm:w-auto"
                  onClick={() => void respond()}
                  disabled={busy || !reply.trim()}
                >
                  {t('tickets.send')}
                </Button>
              </div>
            )}
          </Card>
        ) : (
          <p className="text-muted">{t('tickets.pick')}</p>
        )}
      </div>
    </div>
  );
}
