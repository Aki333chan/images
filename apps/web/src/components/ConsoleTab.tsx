import { useCallback, useEffect, useRef, useState } from 'react';
import {
  completeFromDictionary,
  type MinecraftConsoleCompletionDto,
  type MinecraftConsoleDictionaryDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, Input } from './ui';

/** Сколько живёт кэш словаря: за это время список игроков успевает устареть. */
const DICTIONARY_TTL_MS = 30_000;

/** Сколько вариантов показывать под полем ввода. */
const VISIBLE_SUGGESTIONS = 12;

/** Разбор строки на «уже набранное» и последнее незавершённое слово. */
function splitLastToken(line: string): { head: string; token: string } {
  const match = /(^|\s)(\S*)$/.exec(line);
  if (!match) return { head: line, token: '' };
  const token = match[2] ?? '';
  return { head: line.slice(0, line.length - token.length), token };
}

/**
 * Консоль сервера — возможность ЯДРА: подключается напрямую к Wings по
 * WebSocket, как это делает сама панель Pterodactyl. Игровые модули объявляют
 * capability `console`, но свою реализацию не пишут.
 *
 * Протокол Wings: после connect шлём {event:'auth', args:[token]}; сервер
 * отвечает событиями console output / status / token expiring / token expired.
 */
export function ConsoleTab({ serverId, moduleId }: { serverId: string; moduleId: string }) {
  const { hasPermission } = useAuth();
  const [lines, setLines] = useState<string[]>([]);
  const [state, setState] = useState<'connecting' | 'online' | 'error'>('connecting');
  const [error, setError] = useState('');
  /** Адрес узла Wings — показываем в подсказке, чтобы было что искать в CSP. */
  const [socketUrl, setSocketUrl] = useState('');
  const [command, setCommand] = useState('');
  const socketRef = useRef<WebSocket | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  // ---------- Автодополнение ----------
  //
  // Словарь команд знает только модуль Minecraft, а консоль — возможность
  // ядра, общая для всех модулей. Поэтому автодополнение включается ровно
  // там, где есть чем дополнять, и всё остальное работает как раньше.
  const completionEnabled = moduleId === 'minecraft';
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [suggestionIndex, setSuggestionIndex] = useState(0);
  /** Откуда пришли текущие подсказки — это стоит показать: источники разные. */
  const [suggestionSource, setSuggestionSource] = useState<'companion' | 'static'>('static');
  const dictionaryRef = useRef<{ data: MinecraftConsoleDictionaryDto; at: number } | null>(null);
  /** Состояние перебора: повторный Tab идёт по списку, как в игре. */
  const cycleRef = useRef<{ head: string; options: string[]; index: number; applied: string } | null>(
    null,
  );
  const completingRef = useRef(false);

  useEffect(() => {
    let closed = false;
    let socket: WebSocket | null = null;

    const push = (text: string) =>
      // Держим окно в разумных пределах, иначе вкладка съест память.
      setLines((prev) => [...prev.slice(-800), text]);

    async function connect() {
      try {
        const { token, socket: url } = await api<{ token: string; socket: string }>(
          `/api/servers/${serverId}/console-token`,
        );
        if (closed) return;
        socket = new WebSocket(url);
        socketRef.current = socket;

        socket.onopen = () => socket?.send(JSON.stringify({ event: 'auth', args: [token] }));
        socket.onmessage = (event) => {
          const payload = JSON.parse(event.data as string) as { event: string; args?: string[] };
          switch (payload.event) {
            case 'auth success':
              setState('online');
              // Просим Wings прислать историю и текущее состояние.
              socket?.send(JSON.stringify({ event: 'send logs', args: [null] }));
              break;
            case 'console output':
            case 'install output':
              for (const line of payload.args ?? []) push(line);
              break;
            case 'status':
              push(`— статус сервера: ${payload.args?.[0] ?? '?'}`);
              break;
            case 'token expiring':
            case 'token expired': {
              // Токен живёт недолго — берём новый и продлеваем сессию.
              void api<{ token: string }>(`/api/servers/${serverId}/console-token`).then((fresh) =>
                socket?.send(JSON.stringify({ event: 'auth', args: [fresh.token] })),
              );
              break;
            }
            case 'jwt error':
              setState('error');
              setError(payload.args?.[0] ?? 'Ошибка авторизации консоли');
              break;
          }
        };
        socket.onerror = () => {
          setState('error');
          // Браузер намеренно не сообщает JS причину отказа — блокировку по CSP
          // и недоступный узел здесь не различить. Поэтому подсказка ниже
          // перечисляет обе, а точную причину показывает консоль браузера.
          setError('Не удалось подключиться к консоли Wings');
          setSocketUrl(url);
        };
        socket.onclose = () => {
          if (!closed) setState('error');
        };
      } catch (e) {
        setState('error');
        setError((e as Error).message);
      }
    }

    void connect();
    return () => {
      closed = true;
      socket?.close();
      socketRef.current = null;
    };
  }, [serverId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [lines]);

  /**
   * Словарь базового уровня. Кэшируется: он почти неизменен, меняется в нём
   * только список игроков — ради него кэш и протухает через полминуты.
   */
  const loadDictionary = useCallback(async (): Promise<MinecraftConsoleDictionaryDto | null> => {
    const cached = dictionaryRef.current;
    if (cached && Date.now() - cached.at < DICTIONARY_TTL_MS) return cached.data;
    try {
      const data = await api<MinecraftConsoleDictionaryDto>(
        `/api/modules/minecraft/servers/${serverId}/console/dictionary`,
      );
      dictionaryRef.current = { data, at: Date.now() };
      return data;
    } catch {
      // Автодополнение — удобство, а не функция: молча остаёмся без него,
      // но с уже загруженным словарём, если он был.
      return cached?.data ?? null;
    }
  }, [serverId]);

  // Забираем словарь заранее, чтобы первый Tab сработал мгновенно.
  useEffect(() => {
    if (!completionEnabled || !hasPermission('servers.power')) return;
    dictionaryRef.current = null;
    void loadDictionary();
  }, [completionEnabled, hasPermission, loadDictionary]);

  /** Варианты для текущей строки: сперва у сервера, иначе по словарю. */
  async function optionsFor(line: string): Promise<{ options: string[]; source: 'companion' | 'static' }> {
    const dictionary = await loadDictionary();
    if (!dictionary) return { options: [], source: 'static' };

    if (dictionary.companionAvailable) {
      const result = await api<MinecraftConsoleCompletionDto>(
        `/api/modules/minecraft/servers/${serverId}/console/complete?line=${encodeURIComponent(line)}`,
      ).catch(() => null);
      // Пустой ответ настоящего сервера — это ответ «нечего предложить»,
      // и подменять его догадками из словаря значило бы врать. Словарь
      // остаётся только на случай, когда спросить не удалось.
      if (result?.available) return { options: result.suggestions, source: 'companion' };
    }

    return {
      options: completeFromDictionary(line, dictionary.commands, dictionary.players),
      source: 'static',
    };
  }

  async function completeCommand() {
    if (completingRef.current) return;

    // Повторный Tab по той же строке — следующий вариант из уже полученных.
    const cycle = cycleRef.current;
    if (cycle && cycle.applied === command && cycle.options.length > 1) {
      const index = (cycle.index + 1) % cycle.options.length;
      const applied = cycle.head + cycle.options[index];
      cycleRef.current = { ...cycle, index, applied };
      setSuggestionIndex(index);
      setCommand(applied);
      return;
    }

    completingRef.current = true;
    try {
      const { options, source } = await optionsFor(command);
      setSuggestions(options);
      setSuggestionIndex(0);
      setSuggestionSource(source);
      if (options.length === 0) {
        cycleRef.current = null;
        return;
      }
      const { head } = splitLastToken(command);
      // Единственный вариант дописываем с пробелом: следующий аргумент можно
      // начинать сразу, не нажимая пробел руками — как в игре.
      const applied = head + options[0] + (options.length === 1 ? ' ' : '');
      cycleRef.current = { head, options, index: 0, applied };
      setCommand(applied);
    } finally {
      completingRef.current = false;
    }
  }

  function sendCommand() {
    const value = command.trim();
    if (!value || !socketRef.current) return;
    socketRef.current.send(JSON.stringify({ event: 'send command', args: [value] }));
    setCommand('');
    setSuggestions([]);
    cycleRef.current = null;
  }

  return (
    <div className="space-y-3">
      {state === 'error' && (
        <Card className="border-destructive/50">
          <p className="text-sm text-red-400">{error || 'Консоль недоступна'}</p>
          <div className="mt-1 space-y-1 text-xs text-muted">
            <p>
              Точную причину показывает консоль браузера (<b>F12</b> → Console). Что искать, по
              порядку:
            </p>
            <ol className="ml-4 list-decimal space-y-1">
              <li>
                <b>«Refused to connect… violates… connect-src»</b> — соединение запретил браузер по
                Content-Security-Policy. Консоль идёт напрямую к узлу Wings, а это другой домен, и
                его нужно перечислить в <code>connect-src</code> на стороне nginx.
              </li>
              <li>
                <b>«WebSocket connection… failed»</b> без упоминания CSP — рукопожатие отклонил сам
                Wings. Он сверяет заголовок <code>Origin</code> и по умолчанию пускает только
                Pterodactyl; адрес этой панели нужно добавить в <code>allowed_origins</code> в{' '}
                <code>config.yml</code> Wings и перезапустить его. Отказ выглядит как HTTP 403 и в
                логах панели не виден — она в этом обмене не участвует.
              </li>
              <li>Узел Wings выключен или недоступен — проверьте его в Pterodactyl.</li>
              <li>У служебного пользователя Pterodactyl нет доступа к этому серверу.</li>
            </ol>
            {socketUrl && (
              <p>
                Адрес узла: <code className="break-all">{socketUrl}</code>
              </p>
            )}
          </div>
        </Card>
      )}
      <Card className="h-[420px] overflow-y-auto bg-black/70 font-mono text-xs leading-5 text-neutral-200">
        {lines.length === 0 && (
          <p className="text-muted">
            {state === 'connecting' ? 'Подключение к консоли…' : 'Вывода пока нет'}
          </p>
        )}
        {lines.map((line, i) => (
          <div key={i} className="whitespace-pre-wrap break-all">
            {line}
          </div>
        ))}
        <div ref={bottomRef} />
      </Card>
      {hasPermission('servers.power') && (
        <div className="space-y-1">
          {suggestions.length > 0 && (
            <div className="flex flex-wrap items-center gap-1 text-xs">
              {suggestions.slice(0, VISIBLE_SUGGESTIONS).map((option, i) => (
                <button
                  key={option}
                  type="button"
                  // Мышью — то же самое, что Tab: не всем удобно перебирать.
                  onClick={() => {
                    const cycle = cycleRef.current;
                    const head = cycle?.head ?? splitLastToken(command).head;
                    setSuggestionIndex(i);
                    setCommand(head + option + ' ');
                    cycleRef.current = null;
                  }}
                  className={`rounded px-1.5 py-0.5 font-mono ${
                    i === suggestionIndex ? 'bg-primary/20 text-primary' : 'text-muted hover:bg-white/5'
                  }`}
                >
                  {option}
                </button>
              ))}
              {suggestions.length > VISIBLE_SUGGESTIONS && (
                <span className="text-muted">…ещё {suggestions.length - VISIBLE_SUGGESTIONS}</span>
              )}
              <span className="ml-1 text-[11px] text-muted">
                {suggestionSource === 'companion' ? 'подсказал сервер' : 'по списку команд'}
              </span>
            </div>
          )}
          <div className="flex gap-2">
            <Input
              value={command}
              onChange={(e) => {
                setCommand(e.target.value);
                // Строку правят руками — перебор больше не относится к ней.
                cycleRef.current = null;
                setSuggestions([]);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  sendCommand();
                  return;
                }
                if (e.key === 'Tab' && completionEnabled) {
                  // Иначе Tab уведёт фокус с поля — и вместо дополнения
                  // получится переход на кнопку «Отправить».
                  e.preventDefault();
                  void completeCommand();
                  return;
                }
                if (e.key === 'Escape') {
                  setSuggestions([]);
                  cycleRef.current = null;
                }
              }}
              placeholder={
                completionEnabled
                  ? 'Команда в консоль сервера… (Tab — автодополнение)'
                  : 'Команда в консоль сервера…'
              }
              disabled={state !== 'online'}
            />
            <Button onClick={sendCommand} disabled={state !== 'online' || !command.trim()}>
              Отправить
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
