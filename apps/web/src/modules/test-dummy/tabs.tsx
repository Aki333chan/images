import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useT } from '../../i18n';
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
  const t = useT();
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
            <th className="pb-2">{t('dummy.player')}</th>
            <th className="pb-2">UUID</th>
            <th className="pb-2">{t('dummy.status')}</th>
          </tr>
        </thead>
        <tbody>
          {players.map((p) => (
            <tr key={p.uuid} className="border-t border-border">
              <td className="py-2">{p.name}</td>
              <td className="py-2 font-mono text-xs text-muted">{p.uuid}</td>
              <td className="py-2">{t(p.online ? 'dummy.online' : 'dummy.offline')}</td>
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
                {t(p.online ? 'dummy.online' : 'dummy.offline')}
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
  const t = useT();
  const [result, setResult] = useState('');
  async function fakeTicket() {
    const ticket = await api<{ id: string }>(
      `/api/modules/test-dummy/servers/${serverId}/fake-ticket`,
      {
        method: 'POST',
        body: JSON.stringify({
          playerUuid: '11111111-1111-4111-8111-111111111111',
          playerName: 'TestPlayerOne',
          // Текст обращения не переводится: он уезжает в тикет и остаётся
          // там навсегда, а читать его будут те же люди, что и настоящие.
          text: 'Тестовое обращение из quick command',
        }),
      },
    );
    setResult(t('dummy.ticketDone', { id: ticket.id }));
  }
  return (
    <Card className="space-y-3">
      <p className="text-sm text-muted">{t('dummy.demo')}</p>
      <Button size="sm" onClick={() => void fakeTicket()}>
        {t('dummy.makeTicket')}
      </Button>
      {result && <p className="text-sm text-emerald-400">{result}</p>}
    </Card>
  );
}

export function DummyTicketsTab({ serverId }: ModuleTabProps) {
  const t = useT();
  // Ссылка внутри фразы: строка режется по плейсхолдеру, чтобы порядок слов
  // остался за языком, а не за нашей вёрсткой.
  const [before, after] = t('dummy.ticketsHere', { server: serverId }).split('{link}');
  return (
    <Card>
      <p className="text-sm text-muted">
        {before}
        <Link className="text-primary underline" to="/tickets">
          {t('nav.tickets')}
        </Link>
        {after}
      </p>
    </Card>
  );
}
