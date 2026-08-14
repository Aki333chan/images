import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../lib/api';
import { Button, Card, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';

/** Вкладки фейкового модуля — проверяют механизм динамического рендера по capabilities. */

export function DummyConsoleTab({ serverId }: ModuleTabProps) {
  const [lines, setLines] = useState<string[] | null>(null);
  useEffect(() => {
    void api<{ lines: string[] }>(`/api/modules/test-dummy/servers/${serverId}/console`).then((r) =>
      setLines(r.lines),
    );
  }, [serverId]);
  if (!lines) return <Spinner />;
  return (
    <Card className="bg-black/60 font-mono text-xs text-emerald-300">
      {lines.map((l, i) => (
        <div key={i}>{l}</div>
      ))}
    </Card>
  );
}

export function DummyPlayersTab({ serverId }: ModuleTabProps) {
  const [players, setPlayers] = useState<{ uuid: string; name: string; online: boolean }[] | null>(
    null,
  );
  useEffect(() => {
    void api<{ players: { uuid: string; name: string; online: boolean }[] }>(
      `/api/modules/test-dummy/servers/${serverId}/players`,
    ).then((r) => setPlayers(r.players));
  }, [serverId]);
  if (!players) return <Spinner />;
  return (
    <Card>
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-muted">
          <tr>
            <th className="pb-2">Игрок</th>
            <th className="pb-2">UUID</th>
            <th className="pb-2">Статус</th>
          </tr>
        </thead>
        <tbody>
          {players.map((p) => (
            <tr key={p.uuid} className="border-t border-border">
              <td className="py-2">{p.name}</td>
              <td className="py-2 font-mono text-xs text-muted">{p.uuid}</td>
              <td className="py-2">{p.online ? 'в сети' : 'офлайн'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

export function DummyQuickCommandsTab({ serverId }: ModuleTabProps) {
  const [result, setResult] = useState('');
  async function fakeTicket() {
    const ticket = await api<{ id: string }>(
      `/api/modules/test-dummy/servers/${serverId}/fake-ticket`,
      {
        method: 'POST',
        body: JSON.stringify({
          playerUuid: '11111111-1111-4111-8111-111111111111',
          playerName: 'TestPlayerOne',
          text: 'Тестовое обращение из quick command',
        }),
      },
    );
    setResult(`Создан/дополнен тикет ${ticket.id} — проверьте бейдж в меню`);
  }
  return (
    <Card className="space-y-3">
      <p className="text-sm text-muted">
        Демонстрация: модуль вызывает core-сервис createOrAppendTicket.
      </p>
      <Button size="sm" onClick={() => void fakeTicket()}>
        Создать тестовый тикет
      </Button>
      {result && <p className="text-sm text-emerald-400">{result}</p>}
    </Card>
  );
}

export function DummyTicketsTab({ serverId }: ModuleTabProps) {
  return (
    <Card>
      <p className="text-sm text-muted">
        Тикеты этого сервера доступны на общем экране{' '}
        <Link className="text-primary underline" to="/tickets">
          «Тикеты»
        </Link>
        . (Фильтр по серверу {serverId} — на общем экране.)
      </p>
    </Card>
  );
}
