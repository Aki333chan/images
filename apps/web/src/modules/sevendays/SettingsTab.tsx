import { useCallback, useEffect, useState } from 'react';
import type { SevenDaysCompanionStatusDto, SevenDaysConfigStatusDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';

const base = (serverId: string) => `/api/modules/sevendays/servers/${serverId}`;

/** Порт telnet по умолчанию из serverconfig.xml игры. */
const DEFAULT_PORT = '8081';

/**
 * Настройки подключения к консоли 7 Days to Die.
 *
 * Наружу отдаются только флаги: ни адрес, ни порт, ни пароль обратно не
 * приходят — ровно как с RCON-паролем в модуле Minecraft.
 */
export function SevenDaysSettingsTab({ serverId }: ModuleTabProps) {
  const [status, setStatus] = useState<SevenDaysConfigStatusDto | null>(null);
  const [host, setHost] = useState('');
  const [port, setPort] = useState(DEFAULT_PORT);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [probe, setProbe] = useState('');

  const load = useCallback(() => {
    api<SevenDaysConfigStatusDto>(`${base(serverId)}/config`)
      .then(setStatus)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(load, [load]);

  async function save(disable = false) {
    setBusy(true);
    setError('');
    setProbe('');
    try {
      const res = await api<{ ok: boolean; configured: boolean; probe?: string }>(
        `${base(serverId)}/config`,
        {
          method: 'PUT',
          body: JSON.stringify(
            disable
              ? { host: null, port: null, password: null }
              : { host: host.trim(), port: Number(port) || Number(DEFAULT_PORT), password },
          ),
        },
      );
      setPassword('');
      if (disable) {
        setHost('');
        setPort(DEFAULT_PORT);
      }
      setProbe(res.probe ?? (disable ? 'Подключение отключено' : 'Сохранено'));
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return <Spinner />;

  const canSave = host.trim().length > 0 && password.length > 0 && !busy;

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">Консоль 7 Days to Die</h2>
          <Badge variant={status.telnetConfigured ? 'success' : 'outline'}>
            {status.telnetConfigured ? 'настроено' : 'не настроено'}
          </Badge>
        </div>

        <p className="text-xs text-muted">
          7 Days to Die не поддерживает RCON — удалённое администрирование здесь идёт по
          встроенной telnet-консоли. «RCON» в документации хостеров этой игры означает то же
          самое подключение, названное привычным словом.
        </p>

        <div className="grid gap-3 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <Label>Адрес консоли</Label>
            <Input
              value={host}
              onChange={(e) => setHost(e.target.value)}
              placeholder="10.0.0.2"
            />
          </div>
          <div>
            <Label>Порт</Label>
            <Input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
          </div>
        </div>

        <div>
          <Label>Пароль консоли</Label>
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={
              status.telnetConfigured
                ? 'ввести заново для замены'
                : 'TelnetPassword из serverconfig.xml'
            }
          />
        </div>

        {error && <ErrorText>{error}</ErrorText>}
        {probe && <p className="break-words text-xs text-emerald-400">{probe}</p>}
        {status.lastSeenAt && (
          <p className="text-xs text-muted">
            Последний ответ сервера: {new Date(status.lastSeenAt).toLocaleString('ru-RU')}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={() => void save()} disabled={!canSave}>
            {busy ? 'Сохраняем…' : 'Сохранить и проверить'}
          </Button>
          {status.telnetConfigured && (
            <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
              Отключить
            </Button>
          )}
        </div>
      </Card>

      <CompanionCard serverId={serverId} />

      <Card className="space-y-2 text-xs text-muted">
        <h3 className="text-sm font-semibold text-neutral-100">Что включить на игровом сервере</h3>
        <p>
          В <code>serverconfig.xml</code> задайте <code>TelnetEnabled=&quot;true&quot;</code>,{' '}
          <code>TelnetPort=&quot;8081&quot;</code> и непустой{' '}
          <code>TelnetPassword</code>, затем перезапустите сервер.
        </p>
        <p>
          Пароль обязателен: с пустым <code>TelnetPassword</code> сервер принимает подключения
          только с самого себя, и панель с другой машины до него не достучится.
        </p>
        <p className="text-amber-400">
          Порт консоли наружу выставлять нельзя: telnet — это открытый текст, пароль уходит по
          сети как есть. Адрес указывайте приватный — через тот же туннель, что и RCON остальных
          серверов.
        </p>
      </Card>
    </div>
  );
}

/**
 * Companion-мод: подключение и состояние.
 *
 * Мод НЕ обязателен, и карточка это говорит прямо. Без него работает всё,
 * кроме того, чего у ванильного сервера нет в принципе: обращений игроков
 * из игры, личных ответов им в чат и достоверного состояния мира.
 */
function CompanionCard({ serverId }: { serverId: string }) {
  const [status, setStatus] = useState<SevenDaysCompanionStatusDto | null>(null);
  const [host, setHost] = useState('');
  const [port, setPort] = useState('8110');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [probe, setProbe] = useState('');

  const load = useCallback(() => {
    api<SevenDaysCompanionStatusDto>(`${base(serverId)}/companion`)
      .then(setStatus)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(load, [load]);

  async function save(disable = false) {
    setBusy(true);
    setError('');
    setProbe('');
    try {
      const res = await api<{ ok: boolean; configured: boolean; probe?: string }>(
        `${base(serverId)}/companion`,
        {
          method: 'PUT',
          body: JSON.stringify(
            disable
              ? { host: null, port: null, token: null }
              : { host: host.trim(), port: Number(port) || 8110, token },
          ),
        },
      );
      setToken('');
      if (disable) {
        setHost('');
        setPort('8110');
      }
      setProbe(res.probe ?? (disable ? 'Мод отключён' : 'Сохранено'));
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return <Spinner />;

  const canSave = host.trim().length > 0 && token.length >= 16 && !busy;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Companion-мод</h2>
        <Badge
          variant={!status.configured ? 'outline' : status.online ? 'success' : 'destructive'}
        >
          {!status.configured ? 'не установлен' : status.online ? 'на связи' : 'не отвечает'}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        Мод не обязателен — без него работает всё, кроме того, чего у ванильного сервера нет
        вовсе: обращений игроков командой <code>/ticket</code> прямо из игры, личного ответа им
        в чат и достоверного состояния мира. Ставится только на сервер, игрокам ничего не нужно.
      </p>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="sm:col-span-2">
          <Label>Адрес мода</Label>
          <Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="10.0.0.2" />
        </div>
        <div>
          <Label>Порт</Label>
          <Input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
        </div>
      </div>

      <div>
        <Label>Общий секрет</Label>
        <Input
          type="password"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          autoComplete="new-password"
          placeholder={status.configured ? 'ввести заново для замены' : 'не короче 16 символов'}
        />
        {/* Тот же токен идёт в companion.cfg: им мод авторизуется в панели,
            а панель — в моде. Разные значения — самая частая причина
            «мод не отвечает». */}
        <p className="mt-1 text-[11px] text-muted">
          Тот же самый, что в <code>companion.cfg</code> у мода. Обе стороны должны совпадать.
        </p>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {probe && <p className="break-words text-xs text-emerald-400">{probe}</p>}
      {status.configured && (
        <p className="text-xs text-muted">
          {status.version ? `Версия мода: ${status.version}. ` : ''}
          {status.lastSeenAt
            ? `Последний ответ: ${new Date(status.lastSeenAt).toLocaleString('ru-RU')}`
            : 'Мод ещё ни разу не отвечал.'}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => void save()} disabled={!canSave}>
          {busy ? 'Сохраняем…' : 'Сохранить и проверить'}
        </Button>
        {status.configured && (
          <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
            Отключить
          </Button>
        )}
      </div>
    </Card>
  );
}
