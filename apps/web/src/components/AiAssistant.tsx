import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useI18n } from '../i18n';
import { LOCALE_TAGS } from '@aurum/shared';
import type {
  AiChatMessage,
  AiPendingActionDto,
  AiStreamEvent,
  AiUsageDto,
  Locale,
} from '@aurum/shared';
import { api, getAccessToken } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, ErrorText, Input } from './ui';
import {
  IconBack,
  IconCaretDown,
  IconClose,
  IconEraser,
  IconSend,
  IconSettings,
  IconSparkle,
} from './icons';
import { helpTopicFor, type HelpTopic } from './assistant-help';

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

/**
 * Токены счётом, а не числом: «12 000/200 000 токенов сегодня» по-русски в
 * шапку помещается, а по-английски и по-польски строка длиннее и обрезается
 * ровно на самом важном — на лимите. «12K/200K» читается так же и влезает.
 */
const compact = (value: number, locale: Locale): string =>
  new Intl.NumberFormat(LOCALE_TAGS[locale], {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);

/** Сообщение в ленте: реплики и следы работы ассистента. */
type FeedItem =
  | { kind: 'message'; role: 'user' | 'assistant'; text: string }
  | { kind: 'tool'; summary: string }
  | { kind: 'action'; action: AiPendingActionDto };

export function AiAssistant() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const location = useLocation();
  const canChat = hasPermission('ai.chat');
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<'help' | 'chat'>('help');
  const [helpTopic, setHelpTopic] = useState<HelpTopic>(() => helpTopicFor('/servers', null));
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
    if (open && view === 'chat' && canChat) loadUsage();
  }, [open, view, canChat, loadUsage]);

  useEffect(() => {
    if (view === 'chat') bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [feed, view]);

  // Escape закрывает окно — как и у остальных всплывающих элементов панели.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

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
        throw new Error(t(res.status === 403 ? 'ai.noAccess' : 'ai.unavailable'));
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
          i.kind === 'action' && i.action.id === action.id
            ? { kind: 'action', action: updated }
            : i,
        ),
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function openHelp() {
    const activeTab = document.querySelector<HTMLElement>(
      'main [data-tab][aria-current="page"]',
    )?.dataset.tab;
    setHelpTopic(helpTopicFor(location.pathname, activeTab ?? null));
    setView('help');
    setOpen(true);
  }

  return (
    <>
      {/* Плавающая кнопка. Отступ снизу учитывает полосу жестов iPhone. */}
      {!open && (
        <button
          type="button"
          onClick={openHelp}
          aria-label={t('ai.help.open')}
          title={t('ai.help.open')}
          className="fixed bottom-4 right-4 z-40 flex h-11 w-11 items-center justify-center rounded-full border border-primary/50 bg-primary text-primary-foreground shadow-md transition-[filter,transform] duration-200 ease-panel hover:-translate-y-0.5 hover:brightness-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-background"
          style={{ bottom: 'max(1rem, env(safe-area-inset-bottom))' }}
        >
          <IconSparkle size={18} />
        </button>
      )}

      {open && (
        <div
          className="fixed inset-0 z-50 flex sm:inset-auto sm:bottom-4 sm:right-4 sm:h-[600px] sm:max-h-[85vh] sm:w-[420px]"
          role="dialog"
          aria-label={t(view === 'help' ? 'ai.help.title' : 'ai.titleFull')}
        >
          <Card className="flex min-h-0 w-full flex-col gap-0 rounded-none p-0 sm:rounded-lg">
            <header
              className="flex shrink-0 items-center justify-between gap-2 border-b border-border p-3"
              style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
            >
              <div className="flex min-w-0 items-center gap-2">
                {view === 'chat' && (
                  <button
                    type="button"
                    onClick={() => setView('help')}
                    aria-label={t('ai.help.back')}
                    className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100"
                  >
                    <IconBack size={16} />
                  </button>
                )}
                <IconSparkle size={16} className="shrink-0 text-primary" />
                <div className="min-w-0">
                  <div className="truncate font-semibold">
                    {t(view === 'help' ? 'ai.help.title' : 'ai.title')}
                  </div>
                  {view === 'help' && (
                    <div className="truncate text-[11px] text-muted">
                      {t(helpTopic.titleKey)}
                    </div>
                  )}
                  {view === 'chat' && usage && (
                    // Полная фраза — в подсказке: в шапке она помещается
                    // только по-русски, а обрезается ровно на лимите.
                    <div
                      className="truncate text-[11px] text-muted"
                      title={t('ai.usageFull', {
                        requests: usage.requestsLastHour,
                        requestsLimit: usage.requestsPerHour,
                        tokens: usage.tokensToday,
                        tokensLimit: usage.tokensPerDay,
                      })}
                    >
                      {t('ai.usage', {
                        requests: usage.requestsLastHour,
                        requestsLimit: usage.requestsPerHour,
                        tokens: compact(usage.tokensToday, locale),
                        tokensLimit: compact(usage.tokensPerDay, locale),
                      })}
                    </div>
                  )}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-1">
                {view === 'chat' && feed.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setFeed([])}
                    title={t('ai.clear')}
                    aria-label={t('ai.clear')}
                    className="flex h-10 w-10 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100"
                  >
                    <IconEraser size={16} />
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  aria-label={t('common.close')}
                  className="flex h-10 w-10 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100"
                >
                  <IconClose size={16} />
                </button>
              </div>
            </header>

            {view === 'help' ? (
              <HelpHome topic={helpTopic} canChat={canChat} onChat={() => setView('chat')} />
            ) : (
              <>
                <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-3">
                  {feed.length === 0 && (
                    <div className="space-y-2 text-sm text-muted">
                      <p>{t('ai.hello')}</p>
                      <p className="text-xs">{t('ai.helloHint')}</p>
                    </div>
                  )}

                  {feed.map((item, i) => {
                    if (item.kind === 'tool') {
                      return (
                        <p key={i} className="flex items-center gap-1.5 text-[11px] italic text-muted">
                          <IconSettings size={12} className="shrink-0" />
                          {item.summary}
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
                      placeholder={t(busy ? 'ai.typing' : 'ai.ask')}
                      disabled={busy}
                    />
                    <Button
                      onClick={() => void send()}
                      disabled={busy || !input.trim()}
                      aria-label={t('ai.send')}
                    >
                      <IconSend size={16} />
                    </Button>
                  </div>
                </div>
              </>
            )}
          </Card>
        </div>
      )}
    </>
  );
}

function HelpHome({
  topic,
  canChat,
  onChat,
}: {
  topic: HelpTopic;
  canChat: boolean;
  onChat: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        <h2 className="text-base font-semibold">{t(topic.titleKey)}</h2>
        <p className="mt-1 text-sm leading-5 text-muted">{t('ai.help.intro')}</p>

        <div className="mt-4 border-y border-border">
          {topic.questionIds.map((id) => (
            <details key={id} className="group border-b border-border last:border-b-0">
              <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 rounded-sm py-3 text-sm font-medium transition-colors hover:text-primary-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 [&::-webkit-details-marker]:hidden">
                <span>{t(`ai.help.faq.${id}.q`)}</span>
                <IconCaretDown
                  size={14}
                  className="shrink-0 text-muted transition-transform duration-200 group-open:rotate-180"
                />
              </summary>
              <p className="max-w-prose pb-3 pr-7 text-sm leading-6 text-muted">
                {t(`ai.help.faq.${id}.a`)}
              </p>
            </details>
          ))}
        </div>
      </div>

      {canChat && (
        <div
          className="shrink-0 border-t border-border p-3"
          style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
        >
          <Button className="w-full" onClick={onChat}>
            <IconSparkle size={16} />
            {t('ai.help.askAi')}
          </Button>
          <p className="mt-2 text-center text-[11px] leading-4 text-muted">
            {t('ai.help.aiHint')}
          </p>
        </div>
      )}
    </div>
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
  const { t } = useI18n();
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
      <div className="text-xs font-semibold text-amber-400">{t('ai.suggests')}</div>
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
          {t('ai.untrustedHint')}
        </p>
      )}

      {!settled ? (
        <div className="mt-3 flex flex-col-reverse gap-2 sm:flex-row">
          <Button size="sm" variant="ghost" onClick={() => onResolve(action, false)}>
            {t('ai.reject')}
          </Button>
          <Button size="sm" variant="destructive" onClick={() => onResolve(action, true)}>
            {t('ai.approve')}
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
            {t(STATUS_KEYS[action.status])}
          </span>
          {action.result ? ` · ${action.result}` : ''}
        </p>
      )}
    </div>
  );
}

const STATUS_KEYS: Record<AiPendingActionDto['status'], string> = {
  pending: 'ai.status.pending',
  approved: 'ai.status.approved',
  rejected: 'ai.status.rejected',
  failed: 'ai.status.failed',
  expired: 'ai.status.expired',
};
