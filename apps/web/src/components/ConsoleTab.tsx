import { useCallback, useEffect, useRef, useState } from 'react';
import {
  completeFromDictionary,
  type MinecraftConsoleCompletionDto,
  type MinecraftConsoleDictionaryDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { cn } from '../lib/cn';
import { Button, Card, Dot, Input } from './ui';
import { IconSend } from './icons';

/** Как называется состояние соединения в шапке журнала. */
const CONNECTION_LABEL: Record<string, string> = {
  connecting: 'подключение',
  online: 'на связи',
  reconnecting: 'переподключение',
  error: 'нет связи',
};

/** Цвет той же строки: зелёный — идёт, жёлтый — чинится, красный — стоит. */
const CONNECTION_TONE: Record<string, string> = {
  connecting: 'text-warn',
  online: 'text-ok',
  reconnecting: 'text-warn',
  error: 'text-destructive',
};

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

/** Пауза перед попыткой переподключения: 1, 2, 4, 8 и дальше по 15 секунд. */
const RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000];

/**
 * Насколько близко к низу человек должен быть, чтобы список продолжал
 * прокручиваться сам. Чуть больше высоты строки: если отлистнуть вверх хотя
 * бы на строку, значит читают старое, и утаскивать вниз уже нельзя.
 */
const STICK_TO_BOTTOM_PX = 24;

/**
 * Консоль сервера — возможность ЯДРА: подключается напрямую к Wings по
 * WebSocket, как это делает сама панель Pterodactyl. Игровые модули объявляют
 * capability `console`, но свою реализацию не пишут.
 *
 * Протокол Wings: после connect шлём {event:'auth', args:[token]}; сервер
 * отвечает событиями console output / status / token expiring / token expired.
 *
 * ПРО ОБРЫВЫ. Соединение рвётся регулярно и по причинам, которые от панели не
 * зависят: браузер усыпляет фоновую вкладку и закрывает её сокеты, телефон
 * уходит в сон, сеть переключается с Wi-Fi на мобильную. Раньше любой такой
 * обрыв означал ошибку до перезагрузки страницы — теперь консоль
 * переподключается сама, а возврат на вкладку пробует соединение сразу, не
 * дожидаясь очередной паузы.
 */
export function ConsoleTab({ serverId, moduleId }: { serverId: string; moduleId: string }) {
  const { hasPermission } = useAuth();
  const [lines, setLines] = useState<string[]>([]);
  const [state, setState] = useState<'connecting' | 'online' | 'reconnecting' | 'error'>(
    'connecting',
  );
  const [error, setError] = useState('');
  /** Адрес узла Wings — показываем в подсказке, чтобы было что искать в CSP. */
  const [socketUrl, setSocketUrl] = useState('');
  const [command, setCommand] = useState('');
  const socketRef = useRef<WebSocket | null>(null);
  const logRef = useRef<HTMLDivElement | null>(null);
  /** Человек не отлистывал вверх — можно продолжать прокручивать за ним. */
  const stickRef = useRef(true);

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
  const cycleRef = useRef<{
    head: string;
    options: string[];
    index: number;
    applied: string;
  } | null>(null);
  const completingRef = useRef(false);

  useEffect(() => {
    /** Компонент размонтирован — все попытки прекращаются насовсем. */
    let disposed = false;
    let socket: WebSocket | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;
    /** Хоть раз авторизовались: значит адрес и CSP в порядке, дело в обрыве. */
    let everOnline = false;

    const push = (text: string) =>
      // Держим окно в разумных пределах, иначе вкладка съест память.
      setLines((prev) => [...prev.slice(-800), text]);

    /**
     * Пауза перед следующей попыткой.
     *
     * Растёт до 15 секунд и на этом останавливается: узел Wings может лежать
     * долго, и долбиться в него раз в секунду всё это время незачем, а вот
     * подняться он должен подхватываться за разумное время без участия
     * человека.
     */
    function scheduleRetry() {
      if (disposed || retryTimer !== null) return;
      const delay = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)];
      attempt += 1;
      retryTimer = setTimeout(() => {
        retryTimer = null;
        void connect();
      }, delay);
    }

    /** Обрыв: показываем состояние по тому, работало ли соединение раньше. */
    function dropped(message?: string) {
      if (disposed) return;
      if (everOnline) {
        // Соединение уже работало — значит и адрес, и CSP, и Wings в порядке,
        // и длинная подсказка про настройку тут только мешала бы.
        setState('reconnecting');
      } else {
        setState('error');
        if (message) setError(message);
      }
      scheduleRetry();
    }

    async function connect() {
      if (disposed) return;
      // Закрываем предыдущий сокет молча: его onclose не должен считаться
      // новым обрывом и заводить ещё одну очередь переподключений.
      if (socket) {
        socket.onclose = null;
        socket.onerror = null;
        socket.onmessage = null;
        socket.close();
        socket = null;
      }
      if (!everOnline) setState('connecting');

      let token: string;
      let url: string;
      try {
        const res = await api<{ token: string; socket: string }>(
          `/api/servers/${serverId}/console-token`,
        );
        token = res.token;
        url = res.socket;
      } catch (e) {
        dropped((e as Error).message);
        return;
      }
      if (disposed) return;

      const active = new WebSocket(url);
      socket = active;
      socketRef.current = active;

      active.onopen = () => active.send(JSON.stringify({ event: 'auth', args: [token] }));
      active.onmessage = (event) => {
        const payload = JSON.parse(event.data as string) as { event: string; args?: string[] };
        switch (payload.event) {
          case 'auth success':
            setState('online');
            setError('');
            attempt = 0;
            if (everOnline) {
              // Перечитываем журнал с нуля, а не дописываем: Wings отдаёт
              // хвост файла целиком, и дописанный он дал бы десятки уже
              // показанных строк — человек читал бы одно и то же дважды.
              setLines(['— соединение восстановлено, журнал перечитан —']);
            }
            everOnline = true;
            // Просим Wings прислать историю и текущее состояние.
            active.send(JSON.stringify({ event: 'send logs', args: [null] }));
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
            // Не вышло — сокет всё равно закроется, и обычное
            // переподключение сделает то же самое с новым токеном.
            void api<{ token: string }>(`/api/servers/${serverId}/console-token`)
              .then((fresh) => active.send(JSON.stringify({ event: 'auth', args: [fresh.token] })))
              .catch(() => undefined);
            break;
          }
          case 'jwt error':
            setState('error');
            setError(payload.args?.[0] ?? 'Ошибка авторизации консоли');
            break;
        }
      };
      active.onerror = () => {
        // Браузер намеренно не сообщает JS причину отказа — блокировку по CSP
        // и недоступный узел здесь не различить. Поэтому подсказка ниже
        // перечисляет обе, а точную причину показывает консоль браузера.
        setSocketUrl(url);
      };
      active.onclose = () => {
        if (socket !== active) return;
        socketRef.current = null;
        dropped('Не удалось подключиться к консоли Wings');
      };
    }

    /**
     * Возврат к вкладке — повод попробовать немедленно.
     *
     * Пока вкладка была скрыта, браузер мог усыпить её и закрыть сокет, а
     * очередь переподключений — уползти на пятнадцатисекундную паузу (её
     * таймер в фоне тоже притормаживается). Человек, вернувшийся на страницу,
     * ждать этого не должен.
     */
    function wake() {
      if (disposed || document.hidden) return;
      if (
        socket &&
        (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)
      ) {
        return;
      }
      if (retryTimer !== null) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      attempt = 0;
      void connect();
    }

    document.addEventListener('visibilitychange', wake);
    window.addEventListener('online', wake);

    void connect();
    return () => {
      disposed = true;
      document.removeEventListener('visibilitychange', wake);
      window.removeEventListener('online', wake);
      if (retryTimer !== null) clearTimeout(retryTimer);
      if (socket) {
        socket.onclose = null;
        socket.close();
      }
      socketRef.current = null;
    };
  }, [serverId]);

  /**
   * Прокрутка — только внутри окна консоли.
   *
   * scrollIntoView здесь не годится: он прокручивает ВСЕ прокручиваемые
   * контейнеры до самого документа, и на телефоне каждая новая строка при
   * старте сервера утаскивала страницу к консоли — то есть к самому верху.
   * Пролистать вниз при этом было невозможно.
   *
   * И прокручиваем только если человек и так внизу: если он отлистнул вверх
   * читать стартовые ошибки, дёргать его на каждой новой строке нельзя.
   */
  useEffect(() => {
    const el = logRef.current;
    if (!el || !stickRef.current) return;
    el.scrollTop = el.scrollHeight;
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
  async function optionsFor(
    line: string,
  ): Promise<{ options: string[]; source: 'companion' | 'static' }> {
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
    const socket = socketRef.current;
    // readyState обязателен: во время переподключения сокет существует, но
    // send по нему бросает исключение, а не молча теряет команду.
    if (!value || !socket || socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({ event: 'send command', args: [value] }));
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
      {state === 'reconnecting' && (
        <p className="text-xs text-warn">
          Соединение с консолью прервалось — переподключаемся. Перезагружать страницу не нужно.
        </p>
      )}

      <div>
        {/* Шапка журнала: откуда идут строки и живо ли соединение прямо сейчас.
          Консоль — единственное место панели с постоянным соединением, и без
          такой строки «тишина в журнале» и «связь оборвалась» неотличимы. */}
        <div className="flex items-center gap-2 rounded-t-lg border border-b-0 border-border bg-card/60 px-3 py-2 text-[11px]">
          <span className={CONNECTION_TONE[state]}>
            <Dot
              className={
                state === 'connecting' || state === 'reconnecting' ? 'aurum-pulse' : undefined
              }
            />
          </span>
          <span className="text-muted">Консоль Pterodactyl · WebSocket</span>
          <span className={cn('ml-auto font-mono uppercase tracking-wide', CONNECTION_TONE[state])}>
            {CONNECTION_LABEL[state]}
          </span>
        </div>

        {/* Высота от экрана на телефоне: жёсткие 420 px занимали бы почти всю
          высоту вместе с шапкой и полем ввода. */}
        <Card
          ref={logRef}
          onScroll={(e) => {
            const el = e.currentTarget;
            stickRef.current =
              el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_TO_BOTTOM_PX;
          }}
          className="h-[45vh] overflow-y-auto rounded-t-none border-t-0 bg-background/80 font-mono text-xs leading-5 text-neutral-200 sm:h-[420px]"
        >
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
        </Card>
      </div>
      {hasPermission('servers.power') && (
        <div className="space-y-1">
          {suggestions.length > 0 && (
            // gap-1.5 и крупнее подложка: варианты кликают и пальцем тоже.
            <div className="flex flex-wrap items-center gap-1.5 text-xs">
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
                  className={`rounded px-2 py-1.5 font-mono sm:px-1.5 sm:py-0.5 ${
                    i === suggestionIndex
                      ? 'bg-primary/20 text-primary'
                      : 'text-muted hover:bg-white/5'
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
              placeholder="Команда в консоль сервера…"
              disabled={state !== 'online'}
            />
            <Button onClick={sendCommand} disabled={state !== 'online' || !command.trim()}>
              Отправить
              <IconSend size={13} />
            </Button>
          </div>
          {/* Подсказка про Tab — только там, где эта клавиша есть.
              На телефоне варианты выбирают, нажимая на них. */}
          {completionEnabled && (
            <p className="hidden text-[11px] text-muted sm:block">
              Tab — автодополнение, повторный Tab перебирает варианты
            </p>
          )}
        </div>
      )}
    </div>
  );
}
