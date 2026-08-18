import { useCallback, useEffect, useRef, useState } from 'react';
import type { AiChatMessage, AiPendingActionDto, AiStreamEvent, AiUsageDto } from '@aurum/shared';
import { api, getAccessToken } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, ErrorText, Input } from './ui';

/**
 * AI-ассистент: плавающая кнопка в углу и окно чата.
 *
 * Видна на всех экранах панели, поэтому живёт в Layout, а не на странице.
 *
 * История переписки хранится в браузере и уходит на сервер с каждым
 * обращением. Так сделано намеренно: ассистент не даёт никаких прав сверх
 * тех, что есть у самого человека, поэтому подделать историю бессмысленно —
 * всё, что он может, человек может и сам, теми же кнопками.
 */

/** Сообщение в ленте: реплики и следы работы ассистента. */
type FeedItem =
  | { kind: 'message'; role: 'user' | 'assistant'; text: string }
  | { kind: 'tool'; summary: string }
  | { kind: 'action'; action: AiPendingActionDto };

export function AiAssistant() {
  const { hasPermission } = useAuth();
  const [open, setOpen] = useState(false);
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [usage, setUsage] = useState<AiUsageDto | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  const loadUsage = useCallback(() => {
    api<AiUsageDto>('/api/ai/usage')
      .then(setUsage)
      .catch(() => setUsage(null));
  }, []);

  useEffect(() => {
    if (open) loadUsage();
  }, [open, loadUsage]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [feed]);

  // Escape закрывает окно — как и у остальных всплывающих элементов панели.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  if (!hasPermission('ai.chat')) return null;

  /** История для сервера: следы инструментов туда не нужны, только реплики. */
  const historyFor = (items: FeedItem[]): AiChatMessage[] =>
    items
      .filter((i): i is Extract<FeedItem, { kind: 'message' }> => i.kind === 'message')
      .map((i) => ({ role: i.role, content: i.text }));

  async function send() {
    const text = input.trim();
    if (!text || busy) return;

    const next: FeedItem[] = [...feed, { kind: 'message', role: 'user', text }];
    setFeed(next);
    setInput('');
    setBusy(true);
    setError('');

    // Пустая реплика ассистента — в неё дописывается ответ по мере прихода.
    let assistantText = '';
    setFeed([...next, { kind: 'message', role: 'assistant', text: '' }]);

    const applyDelta = (chunk: string) => {
      assistantText += chunk;
      setFeed((prev) => {
        const copy = [...prev];
        for (let i = copy.length - 1; i >= 0; i--) {
          const item = copy[i]!;
          if (item.kind === 'message' && item.role === 'assistant') {
            copy[i] = { ...item, text: assistantText };
            break;
          }
        }
        return copy;
      });
    };

    try {
      // fetch, а не EventSource: EventSource не умеет заголовок Authorization,
      // а класть токен в query-строку значит записать его в логи nginx.
      const res = await fetch('/api/ai/chat', {
        method: 'POST',
        credentials: 'include',
        headers: {
          'content-type': 'application/json',
          ...(getAccessToken() ? { authorization: `Bearer ${getAccessToken()!}` } : {}),
        },
        body: JSON.stringify({ messages: historyFor(next) }),
      });
      if (!res.ok || !res.body) {
        throw new Error(res.status === 403 ? 'Нет доступа к ассистенту' : 'Ассистент недоступен');
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // События SSE разделены пустой строкой; хвост может быть неполным.
        let sep = buffer.indexOf('\n\n');
        while (sep !== -1) {
          const raw = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          for (const line of raw.split('\n')) {
            if (!line.startsWith('data:')) continue;
            let event: AiStreamEvent;
            try {
              event = JSON.parse(line.slice(5).trim()) as AiStreamEvent;
            } catch {
              continue;
            }
            if (event.type === 'delta') applyDelta(event.text);
            if (event.type === 'tool')
              setFeed((prev) => [...prev, { kind: 'tool', summary: event.summary }]);
            if (event.type === 'action')
              setFeed((prev) => [...prev, { kind: 'action', action: event.action }]);
            if (event.type === 'error') setError(event.message);
          }
          sep = buffer.indexOf('\n\n');
        }
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      loadUsage();
      // Пустой ответ ассистента убираем: висящий пузырь ни о чём.
      setFeed((prev) =>
        prev.filter((i) => !(i.kind === 'message' && i.role === 'assistant' && !i.text)),
      );
    }
  }

  async function resolve(action: AiPendingActionDto, approve: boolean) {
    try {
      const updated = await api<AiPendingActionDto>(`/api/ai/actions/${action.id}`, {
        method: 'POST',
        body: JSON.stringify({ approve }),
      });
      setFeed((prev) =>
        prev.map((i) =>
          i.kind === 'action' && i.action.id === action.id ? { kind: 'action', action: updated } : i,
        ),
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      {/* Плавающая кнопка. Отступ снизу учитывает полосу жестов iPhone. */}
      {!open && (
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-label="Открыть AI-ассистента"
          className="fixed bottom-4 right-4 z-40 flex h-14 w-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-transform hover:scale-105"
          style={{ bottom: 'max(1rem, env(safe-area-inset-bottom))' }}
        >
          <AssistantIcon />
        </button>
      )}

      {open && (
        <div
          className="fixed inset-0 z-50 flex sm:inset-auto sm:bottom-4 sm:right-4 sm:h-[600px] sm:max-h-[85vh] sm:w-[420px]"
          role="dialog"
          aria-label="AI-ассистент"
        >
          <Card className="flex min-h-0 w-full flex-col gap-0 rounded-none p-0 sm:rounded-lg">
            <header
              className="flex shrink-0 items-center justify-between gap-2 border-b border-border p-3"
              style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
            >
              <div className="min-w-0">
                <div className="truncate font-semibold">Ассистент</div>
                {usage && (
                  <div className="truncate text-[11px] text-muted">
                    {usage.requestsLastHour}/{usage.requestsPerHour} обращений за час ·{' '}
                    {usage.tokensToday.toLocaleString('ru-RU')}/
                    {usage.tokensPerDay.toLocaleString('ru-RU')} токенов сегодня
                  </div>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-1">
                {feed.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setFeed([])}
                    title="Очистить переписку"
                    aria-label="Очистить переписку"
                    className="flex h-10 w-10 items-center justify-center rounded-md text-muted hover:bg-white/5"
                  >
                    ⟲
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  aria-label="Закрыть"
                  className="flex h-10 w-10 items-center justify-center rounded-md text-muted hover:bg-white/5"
                >
                  ✕
                </button>
              </div>
            </header>

            <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-3">
              {feed.length === 0 && (
                <div className="space-y-2 text-sm text-muted">
                  <p>Спросите про серверы, игроков или тикеты.</p>
                  <p className="text-xs">
                    Ассистент сам ничего не меняет: действия вроде кика или бана он предлагает
                    карточкой, а выполняются они только после вашего подтверждения. Работает с
                    вашими правами — того, чего вы не можете сами, он тоже не сможет.
                  </p>
                </div>
              )}

              {feed.map((item, i) => {
                if (item.kind === 'tool') {
                  return (
                    <p key={i} className="text-[11px] italic text-muted">
                      ⚙ {item.summary}
                    </p>
                  );
                }
                if (item.kind === 'action') {
                  return <ActionCard key={i} action={item.action} onResolve={resolve} />;
                }
                return (
                  <div
                    key={i}
                    className={`max-w-[85%] whitespace-pre-wrap break-words rounded-lg px-3 py-2 text-sm ${
                      item.role === 'user' ? 'ml-auto bg-primary/20' : 'bg-white/5'
                    }`}
                  >
                    {item.text || (busy ? '…' : '')}
                  </div>
                );
              })}
              <div ref={bottomRef} />
            </div>

            <div
              className="shrink-0 space-y-2 border-t border-border p-3"
              style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
            >
              {error && <ErrorText>{error}</ErrorText>}
              <div className="flex gap-2">
                <Input
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && void send()}
                  placeholder={busy ? 'Ассистент печатает…' : 'Спросить…'}
                  disabled={busy}
                />
                <Button onClick={() => void send()} disabled={busy || !input.trim()}>
                  →
                </Button>
              </div>
            </div>
          </Card>
        </div>
      )}
    </>
  );
}

/**
 * Карточка предложенного действия.
 *
 * Показывает не только «что», но и точные аргументы: человек подтверждает
 * конкретное действие, а не общую формулировку. Предложения, возникшие
 * после чтения игровых данных, помечены отдельно — в таких данных может
 * быть попытка внушить ассистенту команду.
 */
function ActionCard({
  action,
  onResolve,
}: {
  action: AiPendingActionDto;
  onResolve: (action: AiPendingActionDto, approve: boolean) => void;
}) {
  const settled = action.status !== 'pending';
  return (
    <div
      className={`rounded-lg border p-3 ${
        action.status === 'approved'
          ? 'border-emerald-500/40 bg-emerald-500/5'
          : action.status === 'pending'
            ? 'border-amber-500/50 bg-amber-500/5'
            : 'border-border'
      }`}
    >
      <div className="text-xs font-semibold text-amber-400">AI предлагает действие</div>
      <p className="mt-1 break-words text-sm">{action.summary}</p>

      <dl className="mt-2 space-y-0.5 text-[11px] text-muted">
        {Object.entries(action.args).map(([key, value]) => {
          const text = String(value);
          // Многострочное значение — это почти всегда ASCII-арт. Показать его
          // через break-all значило бы показать кашу, а подтверждать человек
          // должен ровно то, что уйдёт собеседнику.
          if (text.includes('\n')) {
            return (
              <div key={key}>
                <dt className="font-mono">{key}:</dt>
                <dd>
                  <pre
                    className="mt-1 overflow-x-auto rounded bg-black/30 p-2 font-mono text-[11px] leading-[1.15] text-neutral-100"
                    style={{ whiteSpace: 'pre' }}
                  >
                    {text}
                  </pre>
                </dd>
              </div>
            );
          }
          return (
            <div key={key} className="flex gap-2">
              <dt className="shrink-0 font-mono">{key}:</dt>
              <dd className="min-w-0 break-all font-mono">{text}</dd>
            </div>
          );
        })}
      </dl>

      {action.fromUntrustedInput && (
        <p className="mt-2 rounded border border-amber-500/40 bg-amber-500/10 p-2 text-[11px] text-amber-300">
          Предложено после чтения данных из игры (ники, тикеты, вывод консоли). В таком тексте
          может быть попытка подсказать ассистенту команду — проверьте особенно внимательно.
        </p>
      )}

      {!settled ? (
        <div className="mt-3 flex flex-col-reverse gap-2 sm:flex-row">
          <Button size="sm" variant="ghost" onClick={() => onResolve(action, false)}>
            Отклонить
          </Button>
          <Button size="sm" variant="destructive" onClick={() => onResolve(action, true)}>
            Подтвердить
          </Button>
        </div>
      ) : (
        <p className="mt-2 text-xs">
          <span
            className={
              action.status === 'approved'
                ? 'text-emerald-400'
                : action.status === 'rejected'
                  ? 'text-muted'
                  : 'text-red-400'
            }
          >
            {STATUS_LABELS[action.status]}
          </span>
          {action.result ? ` · ${action.result}` : ''}
        </p>
      )}
    </div>
  );
}

const STATUS_LABELS: Record<AiPendingActionDto['status'], string> = {
  pending: 'ждёт решения',
  approved: 'выполнено',
  rejected: 'отклонено',
  failed: 'не удалось',
  expired: 'предложение устарело',
};

function AssistantIcon() {
  return (
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 3a7 7 0 0 1 7 7v1.5a7 7 0 0 1-7 7H8.5L5 21.5V17a7 7 0 0 1-1-3.5V10a7 7 0 0 1 7-7Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <circle cx="9.5" cy="11" r="1.1" fill="currentColor" />
      <circle cx="14.5" cy="11" r="1.1" fill="currentColor" />
    </svg>
  );
}
