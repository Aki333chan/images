import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, Input } from './ui';

/**
 * Консоль сервера — возможность ЯДРА: подключается напрямую к Wings по
 * WebSocket, как это делает сама панель Pterodactyl. Игровые модули объявляют
 * capability `console`, но свою реализацию не пишут.
 *
 * Протокол Wings: после connect шлём {event:'auth', args:[token]}; сервер
 * отвечает событиями console output / status / token expiring / token expired.
 */
export function ConsoleTab({ serverId }: { serverId: string; moduleId: string }) {
  const { hasPermission } = useAuth();
  const [lines, setLines] = useState<string[]>([]);
  const [state, setState] = useState<'connecting' | 'online' | 'error'>('connecting');
  const [error, setError] = useState('');
  /** Адрес узла Wings — показываем в подсказке, чтобы было что искать в CSP. */
  const [socketUrl, setSocketUrl] = useState('');
  const [command, setCommand] = useState('');
  const socketRef = useRef<WebSocket | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

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

  function sendCommand() {
    const value = command.trim();
    if (!value || !socketRef.current) return;
    socketRef.current.send(JSON.stringify({ event: 'send command', args: [value] }));
    setCommand('');
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
        <div className="flex gap-2">
          <Input
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && sendCommand()}
            placeholder="Команда в консоль сервера…"
            disabled={state !== 'online'}
          />
          <Button onClick={sendCommand} disabled={state !== 'online' || !command.trim()}>
            Отправить
          </Button>
        </div>
      )}
    </div>
  );
}
