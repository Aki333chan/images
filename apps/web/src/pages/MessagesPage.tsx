import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ConversationDto, StaffContactDto, StaffMessageDto } from '@aurum/shared';
import { LOCALE_TAGS, hasAsciiArt, parseMessageSegments, type Locale } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Spinner, Textarea } from '../components/ui';
import { IconCompose } from '../components/icons';
import { useI18n, useT } from '../i18n';

/**
 * Время сообщения: сегодняшнее — часами, старое — днём и месяцем.
 *
 * Локаль приходит аргументом, а не берётся из браузера: язык панели человек
 * мог выбрать вручную, и порядок дня с месяцем должен следовать за этим
 * выбором, а не за настройками системы.
 */
function formatTime(iso: string, locale: Locale): string {
  const date = new Date(iso);
  const today = new Date();
  const sameDay =
    date.getFullYear() === today.getFullYear() &&
    date.getMonth() === today.getMonth() &&
    date.getDate() === today.getDate();
  const tag = LOCALE_TAGS[locale];
  return sameDay
    ? date.toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString(tag, { day: '2-digit', month: '2-digit' });
}

/**
 * Тело сообщения: обычный текст и моноширинные блоки.
 *
 * Блок рисуется <pre> с whitespace-pre и СОБСТВЕННОЙ горизонтальной
 * прокруткой. Именно так, а не переносом по ширине: в ASCII-арте пробелы —
 * это сам рисунок, и перенос строки превращает кота в кашу из скобок. Пусть
 * широкий арт лучше прокручивается вбок, чем разваливается.
 *
 * Размер шрифта мельче обычного намеренно: чем он меньше, тем больше рисунка
 * влезает без прокрутки. Правило про 16 px здесь не действует — оно про поля
 * ВВОДА и автозум iOS, а <pre> сфокусировать нельзя.
 */
function MessageBody({ text }: { text: string }) {
  const segments = parseMessageSegments(text);

  return (
    <>
      {segments.map((segment, i) =>
        segment.kind === 'art' ? (
          <pre
            key={i}
            className="my-1 overflow-x-auto rounded bg-black/30 p-2 font-mono text-[11px] leading-[1.15]"
            // whitespace-pre без break: ни переносов, ни схлопывания пробелов.
            style={{ whiteSpace: 'pre' }}
          >
            {segment.content}
          </pre>
        ) : (
          <div key={i} className="whitespace-pre-wrap break-words">
            {segment.content}
          </div>
        ),
      )}
    </>
  );
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
  const { t, locale } = useI18n();
  const { me, messagesVersion } = useAuth();
  const [conversations, setConversations] = useState<ConversationDto[] | null>(null);
  /**
   * Открытый собеседник целиком, а не только его id.
   *
   * Раньше здесь лежал id, а сам собеседник искался в списке переписок — и
   * для того, кому ещё ни разу не писали, поиск не находил ничего: клик по
   * нику в подсказках не открывал ровным счётом ничего. Список переписок
   * знает только тех, с кем обмен уже был, и опираться на него при выборе
   * НОВОГО адресата нельзя.
   */
  const [peer, setPeer] = useState<{ id: string; nickname: string | null } | null>(null);
  const peerId = peer?.id ?? null;
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

  // Ник собеседника мог смениться, пока диалог открыт: если он есть в свежем
  // списке переписок, берём оттуда.
  const peerFromList = useMemo(
    () => conversations?.find((c) => c.peer.id === peerId)?.peer ?? null,
    [conversations, peerId],
  );
  const openPeer = peerFromList ?? peer;

  async function send() {
    const value = text.trim();
    if (!value || !openPeer?.nickname) return;
    setBusy(true);
    setError('');
    try {
      await api<StaffMessageDto>('/api/messages', {
        method: 'POST',
        body: JSON.stringify({ nickname: openPeer.nickname, text: value }),
      });
      setText('');
      loadThread(openPeer.id);
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
        <h1 className="text-xl font-bold">{t('nav.messages')}</h1>
        <NewConversation
          onStarted={(contact) => {
            loadConversations();
            setPeer(contact);
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
        <Card className={`space-y-1 p-2 ${openPeer ? 'hidden md:block' : ''}`}>
          {conversations.length === 0 && (
            <p className="p-2 text-xs text-muted">{t('msg.empty')}</p>
          )}
          {conversations.map((c) => (
            <button
              key={c.peer.id}
              onClick={() => setPeer(c.peer)}
              className={`w-full rounded px-2 py-2.5 text-left ${
                c.peer.id === peerId ? 'bg-white/10' : 'hover:bg-white/5'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-sm font-medium">
                  {c.peer.nickname ?? t('msg.noNick')}
                </span>
                {c.unread > 0 && <Badge variant="destructive">{c.unread}</Badge>}
              </div>
              <div className="truncate text-xs text-muted">
                {c.lastMessage.outgoing && t('msg.youPrefix')}
                {previewOf(c.lastMessage.text, t)}
              </div>
            </button>
          ))}
        </Card>

        {/* Высота: на телефоне от высоты экрана, на десктопе — как было.
            Фиксированные 540 px на экране высотой 667 px не оставили бы
            места ни на заголовок, ни на список. */}
        <Card className={`flex h-[65vh] flex-col md:h-[540px] ${openPeer ? '' : 'hidden md:flex'}`}>
          {!openPeer ? (
            <p className="m-auto text-sm text-muted">{t('msg.pick')}</p>
          ) : (
            <>
              <div className="flex items-center gap-2 border-b border-border pb-2 text-sm font-medium">
                <button
                  type="button"
                  onClick={() => setPeer(null)}
                  aria-label={t('msg.back')}
                  className="-ml-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted hover:bg-white/5 md:hidden"
                >
                  ←
                </button>
                <span className="min-w-0 truncate">{openPeer.nickname ?? t('msg.noNick')}</span>
              </div>

              <div className="flex-1 space-y-2 overflow-y-auto py-3">
                {thread.map((m) => {
                  const outgoing = m.fromUserId === me?.user.id;
                  // Пузырь с артом шире обычного: 75 % ширины колонки режут
                  // рисунок пополам, и прокручивать пришлось бы каждую строку.
                  const wide = hasAsciiArt(m.text);
                  return (
                    <div
                      key={m.id}
                      className={`flex ${outgoing ? 'justify-end' : 'justify-start'}`}
                    >
                      <div
                        className={`min-w-0 rounded-lg px-3 py-2 text-sm ${
                          wide ? 'max-w-full' : 'max-w-[75%]'
                        } ${outgoing ? 'bg-primary/25' : 'bg-white/10'}`}
                      >
                        <MessageBody text={m.text} />
                        <div className="mt-1 text-right text-[10px] text-muted">
                          {formatTime(m.createdAt, locale)}
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

              <div className="flex items-end gap-2 border-t border-border pt-3">
                {/*
                  Textarea, а не Input: в однострочное поле не вставить
                  многострочный арт — браузер выбрасывает переводы строк, и
                  рисунок склеивается в одну строку ещё до отправки.
                  Enter отправляет, Shift+Enter — новая строка.
                */}
                <Textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      void send();
                    }
                  }}
                  // Подсказка про Shift+Enter — в title, а не в placeholder:
                  // на узком экране она не помещается в одну строку и обрезается
                  // по высоте поля, из-за чего поле выглядит сломанным.
                  placeholder={t('msg.placeholder')}
                  title={t('msg.enterHint')}
                  disabled={busy}
                  rows={1}
                  className="max-h-40 min-h-[44px] resize-y"
                />
                <Button onClick={() => void send()} disabled={busy || !text.trim()}>
                  {t('msg.send')}
                </Button>
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

/**
 * Строка предпросмотра в списке диалогов.
 *
 * Ограждения и сам рисунок там показывать нечего: в одну строку арт всё равно
 * не поместится, а «```» вместо текста выглядит как сбой.
 */
function previewOf(text: string, t: (key: string) => string): string {
  const segments = parseMessageSegments(text);
  const firstText = segments.find((s) => s.kind === 'text')?.content;
  if (firstText) return firstText;
  return segments.some((s) => s.kind === 'art') ? t('msg.asciiArt') : text;
}

/** Начать диалог: поиск коллеги по нику с автодополнением. */
function NewConversation({ onStarted }: { onStarted: (contact: StaffContactDto) => void }) {
  const t = useT();
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

  if (!open)
    return (
      <Button onClick={() => setOpen(true)}>
        <IconCompose size={14} />
        {t('msg.compose')}
      </Button>
    );

  return (
    <div className="relative">
      <Input
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t('msg.nickPlaceholder')}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {/* inset-x-0 вместо фиксированных 256 px: на узком экране список
          подсказок повторяет ширину поля и не вылезает за край. */}
      <div className="absolute inset-x-0 z-10 mt-1 min-w-[14rem] rounded border border-border bg-card p-1 shadow-lg sm:left-auto sm:right-0 sm:w-64">
        {contacts.length === 0 && (
          <div className="p-2 text-xs text-muted">{t('msg.noContacts')}</div>
        )}
        {contacts.map((c) => (
          <button
            key={c.id}
            className="block w-full rounded px-2 py-2.5 text-left text-sm hover:bg-white/10 sm:py-1.5"
            // onMouseDown, а не onClick: blur поля происходит между нажатием и
            // отпусканием кнопки, и закрытый по blur список успевает исчезнуть
            // раньше, чем click до него доберётся. preventDefault заодно не даёт
            // полю потерять фокус.
            onMouseDown={(e) => {
              e.preventDefault();
              onStarted(c);
              setOpen(false);
              setQuery('');
            }}
          >
            {c.nickname}
          </button>
        ))}
      </div>
    </div>
  );
}
