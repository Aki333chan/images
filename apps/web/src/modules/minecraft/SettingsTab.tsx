import { useCallback, useEffect, useState } from 'react';
import type { MinecraftConfigStatusDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label } from '../../components/ui';
import type { ModuleTabProps } from '../registry';

const base = (serverId: string) => `/api/modules/minecraft/servers/${serverId}`;

/**
 * Настройки подключения модуля: RCON и companion-плагин.
 *
 * Секреты сюда не приходят — сервер отдаёт только флаги «настроено/нет».
 * Поэтому поля пароля и токена всегда пустые: это не потеря данных, а
 * следствие того, что прочитать сохранённое нельзя даже владельцу панели.
 */
export function MinecraftSettingsTab({ serverId }: ModuleTabProps) {
  const [status, setStatus] = useState<MinecraftConfigStatusDto | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api<MinecraftConfigStatusDto>(`${base(serverId)}/config`)
      .then(setStatus)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(load, [load]);

  return (
    <div className="space-y-4">
      {error && <ErrorText>{error}</ErrorText>}
      <RconForm serverId={serverId} status={status} onSaved={load} />
      <CompanionForm serverId={serverId} status={status} onSaved={load} />
    </div>
  );
}

function RconForm({
  serverId,
  status,
  onSaved,
}: {
  serverId: string;
  status: MinecraftConfigStatusDto | null;
  onSaved: () => void;
}) {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('25575');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [probe, setProbe] = useState('');

  async function save() {
    setBusy(true);
    setError('');
    setProbe('');
    try {
      // Сервер сразу выполняет проверочную команду и возвращает её вывод,
      // чтобы неверный пароль или порт всплыли здесь, а не у модератора.
      const res = await api<{ ok: boolean; probe: string }>(`${base(serverId)}/config/rcon`, {
        method: 'PUT',
        body: JSON.stringify({ host: host.trim(), port: Number(port), password }),
      });
      setProbe(res.probe || 'команда выполнена, ответ пустой');
      setPassword('');
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const portNumber = Number(port);
  const portValid = Number.isInteger(portNumber) && portNumber >= 1 && portNumber <= 65535;
  const canSave = host.trim().length > 0 && portValid && password.length > 0 && !busy;

  return (
    <Card className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">Подключение по RCON</h2>
        <Badge variant={status?.rconConfigured ? 'success' : 'outline'}>
          {status?.rconConfigured ? 'настроено' : 'не настроено'}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        Через RCON работают вкладки «Игроки», «Баны», «Whitelist» и быстрые команды. Адрес
        указывайте приватный, через туннель — например <code>10.0.0.2</code>, а не публичный.
        {status?.lastSeenAt && (
          <> Последняя успешная команда: {new Date(status.lastSeenAt).toLocaleString()}.</>
        )}
      </p>

      <div className="grid gap-3 sm:grid-cols-3">
        <div>
          <Label>Хост</Label>
          <Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="10.0.0.2" />
        </div>
        <div>
          <Label>Порт</Label>
          <Input
            value={port}
            onChange={(e) => setPort(e.target.value)}
            inputMode="numeric"
            placeholder="25575"
          />
          {!portValid && port !== '' && <ErrorText>Порт должен быть числом от 1 до 65535</ErrorText>}
        </div>
        <div>
          <Label>Пароль</Label>
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={status?.rconConfigured ? 'ввести заново для замены' : 'rcon.password'}
          />
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {probe && (
        <p className="text-xs text-emerald-400">
          Проверка прошла, сервер ответил: <span className="font-mono">{probe}</span>
        </p>
      )}

      <div className="flex items-center gap-3">
        <Button onClick={() => void save()} disabled={!canSave}>
          {busy ? 'Проверяем связь…' : 'Сохранить и проверить'}
        </Button>
        <span className="text-xs text-muted">
          Сохранённый пароль обратно не показывается — его нельзя прочитать даже владельцу.
        </span>
      </div>
    </Card>
  );
}

function CompanionForm({
  serverId,
  status,
  onSaved,
}: {
  serverId: string;
  status: MinecraftConfigStatusDto | null;
  onSaved: () => void;
}) {
  const [baseUrl, setBaseUrl] = useState('');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  async function save(disable = false) {
    setBusy(true);
    setError('');
    setSaved(false);
    try {
      await api<{ ok: boolean; configured: boolean }>(`${base(serverId)}/config/companion`, {
        method: 'PUT',
        body: JSON.stringify(
          disable ? { baseUrl: null, token: null } : { baseUrl: baseUrl.trim(), token },
        ),
      });
      setToken('');
      if (disable) setBaseUrl('');
      setSaved(true);
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  // Токен уходит в HTTP-заголовок, а заголовки допускают только ASCII:
  // кириллица в токене сломала бы запрос уже на плагине.
  const tokenIsAscii = /^[\x20-\x7e]*$/.test(token);
  const canSave = baseUrl.trim().length > 0 && token.length > 0 && tokenIsAscii && !busy;

  return (
    <Card className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">Companion-плагин</h2>
        <Badge variant={status?.companionConfigured ? 'success' : 'outline'}>
          {status?.companionConfigured ? 'настроено' : 'не настроено'}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        Нужен только для вкладки «Инвентарь», а также чтобы во вкладке «Игроки» появились UUID и
        пинг. Без него остальное работает по RCON. Адрес — приватный, через туннель; наружу порт
        плагина выставлять не нужно.
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Адрес плагина</Label>
          <Input
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="http://10.0.0.2:8085"
          />
        </div>
        <div>
          <Label>Токен</Label>
          <Input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="new-password"
            placeholder={status?.companionConfigured ? 'ввести заново для замены' : 'из config.yml'}
          />
          {!tokenIsAscii && <ErrorText>Токен может содержать только латиницу и цифры</ErrorText>}
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">Сохранено.</p>}

      <div className="flex items-center gap-2">
        <Button onClick={() => void save()} disabled={!canSave}>
          {busy ? 'Сохраняем…' : 'Сохранить'}
        </Button>
        {status?.companionConfigured && (
          <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
            Отключить
          </Button>
        )}
      </div>
    </Card>
  );
}
