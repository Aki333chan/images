import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ConversationDto, StaffContactDto, StaffMessageDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Spinner } from '../components/ui';

function formatTime(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  const sameDay =
    date.getFullYear() === today.getFullYear() &&
    date.getMonth() === today.getMonth() &&
    date.getDate() === today.getDate();
  return sameDay
    ? date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' });
}

/**
 * Внутренняя переписка сотрудников.
 *
 * Приватная: сервер отдаёт только те диалоги, в которых участвует сам
 * запрашивающий. Прав для доступа не требуется — это не игровое действие.
 *
 * Обновления приходят по тому же WebSocket, что и бейдж тикетов: на событие
 * messages.updated перезапрашиваем список и открытый диалог.
 */
export function MessagesPage() {
  const { me, messagesVersion } = useAuth();
  const [conversations, setConversations] = useState<ConversationDto[] | null>(null);
  const [peerId, setPeerId] = useState<string | null>(null);
  const [thread, setThread] = useState<StaffMessageDto[]>([]);
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  const loadConversations = useCallback(() => {
    api<ConversationDto[]>('/api/messages/conversations')
      .then(setConversations)
      .catch((e: Error) => setError(e.message));
  }, []);

  const loadThread = useCallback((id: string) => {
    api<StaffMessageDto[]>(`/api/messages/thread/${id}`)
      .then(setThread)
      .catch((e: Error) => setError(e.message));
    // Открыли диалог — входящие в нём считаются прочитанными.
    void api(`/api/messages/thread/${id}/read`, { method: 'POST' }).catch(() => undefined);
  }, []);

  useEffect(loadConversations, [loadConversations, messagesVersion]);

  useEffect(() => {
    if (peerId) loadThread(peerId);
  }, [peerId, loadThread, messagesVersion]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [thread]);

  const peer = useMemo(
    () => conversations?.find((c) => c.peer.id === peerId)?.peer ?? null,
    [conversations, peerId],
  );

  async function send() {
    const value = text.trim();
    if (!value || !peer?.nickname) return;
    setBusy(true);
    setError('');
    try {
      await api<StaffMessageDto>('/api/messages', {
        method: 'POST',
        body: JSON.stringify({ nickname: peer.nickname, text: value }),
      });
      setText('');
      loadThread(peer.id);
      loadConversations();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!conversations) return <Spinner />;

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-2">
        <h1 className="text-xl font-bold">Сообщения</h1>
        <NewConversation
          onStarted={(contact) => {
            loadConversations();
            setPeerId(contact.id);
          }}
        />
      </div>

      {error && <ErrorText>{error}</ErrorText>}

      {/*
        На десктопе две колонки рядом. На телефоне так не помещается: список
        диалогов и переписка друг под другом означают, что до сообщений надо
        каждый раз прокручивать мимо списка. Поэтому ниже md показывается
        что-то одно — список либо открытый диалог с кнопкой «назад», как в
        обычном мессенджере.
      */}
      <div className="grid gap-4 md:grid-cols-[260px_1fr]">
        <Card className={`space-y-1 p-2 ${peer ? 'hidden md:block' : ''}`}>
          {conversations.length === 0 && (
            <p className="p-2 text-xs text-muted">
              Переписки пока нет. Нажмите «Написать» и выберите коллегу по нику.
            </p>
          )}
          {conversations.map((c) => (
            <button
              key={c.peer.id}
              onClick={() => setPeerId(c.peer.id)}
              className={`w-full rounded px-2 py-2.5 text-left ${
                c.peer.id === peerId ? 'bg-white/10' : 'hover:bg-white/5'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-sm font-medium">
                  {c.peer.nickname ?? c.peer.displayName}
                </span>
                {c.unread > 0 && <Badge variant="destructive">{c.unread}</Badge>}
              </div>
              <div className="truncate text-xs text-muted">
                {c.lastMessage.outgoing && 'вы: '}
                {c.lastMessage.text}
              </div>
            </button>
          ))}
        </Card>

        {/* Высота: на телефоне от высоты экрана, на десктопе — как было.
            Фиксированные 540 px на экране высотой 667 px не оставили бы
            места ни на заголовок, ни на список. */}
        <Card
          className={`flex h-[65vh] flex-col md:h-[540px] ${peer ? '' : 'hidden md:flex'}`}
        >
          {!peer ? (
            <p className="m-auto text-sm text-muted">Выберите диалог слева</p>
          ) : (
            <>
              <div className="flex items-center gap-2 border-b border-border pb-2 text-sm font-medium">
                <button
                  type="button"
                  onClick={() => setPeerId(null)}
                  aria-label="К списку диалогов"
                  className="-ml-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted hover:bg-white/5 md:hidden"
                >
                  ←
                </button>
                <span className="min-w-0 truncate">
                  {peer.nickname ?? peer.displayName}
                  <span className="ml-2 text-xs text-muted">{peer.displayName}</span>
                </span>
              </div>

              <div className="flex-1 space-y-2 overflow-y-auto py-3">
                {thread.map((m) => {
                  const outgoing = m.fromUserId === me?.user.id;
                  return (
                    <div key={m.id} className={`flex ${outgoing ? 'justify-end' : 'justify-start'}`}>
                      <div
                        className={`max-w-[75%] rounded-lg px-3 py-2 text-sm ${
                          outgoing ? 'bg-primary/25' : 'bg-white/10'
                        }`}
                      >
                        <div className="whitespace-pre-wrap break-words">{m.text}</div>
                        <div className="mt-1 text-right text-[10px] text-muted">
                          {formatTime(m.createdAt)}
                          {/* Галочка только у своих: знать, прочитал ли
                              собеседник ВАШЕ сообщение, осмысленно; обратное — нет. */}
                          {outgoing && (m.readAt ? ' ✓✓' : ' ✓')}
                        </div>
                      </div>
                    </div>
                  );
                })}
                <div ref={bottomRef} />
              </div>

              <div className="flex gap-2 border-t border-border pt-3">
                <Input
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && void send()}
                  placeholder="Сообщение…"
                  disabled={busy}
                />
                <Button onClick={() => void send()} disabled={busy || !text.trim()}>
                  Отправить
                </Button>
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

/** Начать диалог: поиск коллеги по нику с автодополнением. */
function NewConversation({ onStarted }: { onStarted: (contact: StaffContactDto) => void }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [contacts, setContacts] = useState<StaffContactDto[]>([]);

  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(() => {
      api<StaffContactDto[]>(`/api/messages/contacts?q=${encodeURIComponent(query)}`)
        .then(setContacts)
        .catch(() => setContacts([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [open, query]);

  if (!open) return <Button onClick={() => setOpen(true)}>Написать</Button>;

  return (
    <div className="relative">
      <Input
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Ник коллеги"
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {/* inset-x-0 вместо фиксированных 256 px: на узком экране список
          подсказок повторяет ширину поля и не вылезает за край. */}
      <div className="absolute inset-x-0 z-10 mt-1 min-w-[14rem] rounded border border-border bg-card p-1 shadow-lg sm:left-auto sm:right-0 sm:w-64">
        {contacts.length === 0 && (
          <div className="p-2 text-xs text-muted">
            Никого не нашлось. Ник появляется у сотрудника после первого входа.
          </div>
        )}
        {contacts.map((c) => (
          <button
            key={c.id}
            className="block w-full rounded px-2 py-2.5 text-left text-sm hover:bg-white/10 sm:py-1.5"
            onClick={() => {
              onStarted(c);
              setOpen(false);
              setQuery('');
            }}
          >
            {c.nickname}
            <span className="ml-2 text-xs text-muted">{c.displayName}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
