import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../lib/api';
import { Button, Card, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';

/**
 * Вкладки фейкового модуля — проверяют механизм динамического рендера по capabilities.
 *
 * Своей консоли у модуля нет намеренно: capability `console` реализует ядро
 * (WebSocket Pterodactyl), и дублировать её в модуле не нужно.
 */

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
      {/* Как и в модуле Minecraft: таблица с md, ниже — карточки.
          UUID в 36 символов сам по себе шире экрана телефона. */}
      <table className="hidden w-full text-sm md:table">
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

      <ul className="space-y-2 md:hidden">
        {players.map((p) => (
          <li key={p.uuid} className="rounded-md border border-border p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium">{p.name}</span>
              <span className="shrink-0 text-xs text-muted">
                {p.online ? 'в сети' : 'офлайн'}
              </span>
            </div>
            <p className="mt-1 break-all font-mono text-[11px] text-muted">{p.uuid}</p>
          </li>
        ))}
      </ul>
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
