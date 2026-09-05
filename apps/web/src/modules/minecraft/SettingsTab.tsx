import { useCallback, useEffect, useState } from 'react';
import { PLUGIN_PERMISSIONS, type MinecraftConfigStatusDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label } from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { useI18n, useT } from '../../i18n';
import { InstalledPluginsPanel } from './InstalledPluginsPanel';

/** Тот же принцип, что и во вкладках: префикс берётся из модуля сервера. */
const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/**
 * Настройки подключения модуля: RCON, companion-плагин и управление
 * установленными плагинами.
 *
 * Секреты сюда не приходят — сервер отдаёт только флаги «настроено/нет».
 * Поэтому поля пароля и токена всегда пустые: это не потеря данных, а
 * следствие того, что прочитать сохранённое нельзя даже владельцу панели.
 *
 * Список установленных плагинов живёт именно здесь, а не на виду у страницы
 * сервера: это редкая настроечная работа, а не ежедневная. Посмотреть, что
 * стоит на сервере, можно и без неё — в блоке «Поддерживаемые плагины» по
 * кнопке «Показать все плагины сервера».
 */
export function MinecraftSettingsTab({ serverId, moduleId }: ModuleTabProps) {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState<MinecraftConfigStatusDto | null>(null);
  const [error, setError] = useState('');

  /**
   * companion-плагин и маркет плагинов есть только у Paper. На Forge и
   * NeoForge плагинов Bukkit не существует, и показывать здесь форму адреса
   * плагина значило бы предлагать настроить то, чего не бывает.
   */
  const bukkit = moduleId === 'minecraft';

  const load = useCallback(() => {
    api<MinecraftConfigStatusDto>(`${base(moduleId, serverId)}/config`)
      .then(setStatus)
      .catch((e: Error) => setError(e.message));
  }, [serverId, moduleId]);

  useEffect(load, [load]);

  return (
    <div className="space-y-4">
      {error && <ErrorText>{error}</ErrorText>}
      <RconForm serverId={serverId} moduleId={moduleId} status={status} onSaved={load} />
      {bukkit && <CompanionForm serverId={serverId} status={status} onSaved={load} />}
      {bukkit && hasPermission(PLUGIN_PERMISSIONS.manage) && (
        <InstalledPluginsPanel
          serverId={serverId}
          onRestart={
            hasPermission('servers.power')
              ? () =>
                  void api(`/api/servers/${serverId}/power`, {
                    method: 'POST',
                    body: JSON.stringify({ signal: 'restart' }),
                  })
              : undefined
          }
        />
      )}
    </div>
  );
}

function RconForm({
  serverId,
  moduleId,
  status,
  onSaved,
}: {
  serverId: string;
  moduleId: string;
  status: MinecraftConfigStatusDto | null;
  onSaved: () => void;
}) {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('25575');
  const [password, setPassword] = useState('');
  const { t, formatDateTime } = useI18n();
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
      const res = await api<{ ok: boolean; probe: string }>(`${base(moduleId, serverId)}/config/rcon`, {
        method: 'PUT',
        body: JSON.stringify({ host: host.trim(), port: Number(port), password }),
      });
      setProbe(res.probe || t('mc.s.emptyProbe'));
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
        <h2 className="font-semibold">{t('mc.s.rcon')}</h2>
        <Badge variant={status?.rconConfigured ? 'success' : 'outline'}>
          {status?.rconConfigured ? t('mc.s.configured') : t('mc.s.notConfigured')}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        {t('mc.s.rconHintA')} <code>10.0.0.2</code>{t('mc.s.rconHintB')}
        {status?.lastSeenAt && <>{t('mc.s.lastOk', { date: formatDateTime(status.lastSeenAt) })}</>}
      </p>

      <div className="grid gap-3 sm:grid-cols-3">
        <div>
          <Label>{t('mc.s.host')}</Label>
          <Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="10.0.0.2" />
        </div>
        <div>
          <Label>{t('mc.s.port')}</Label>
          <Input
            value={port}
            onChange={(e) => setPort(e.target.value)}
            inputMode="numeric"
            placeholder="25575"
          />
          {!portValid && port !== '' && <ErrorText>{t('mc.s.badPort')}</ErrorText>}
        </div>
        <div>
          <Label>{t('mc.s.password')}</Label>
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={status?.rconConfigured ? t('mc.s.replace') : 'rcon.password'}
          />
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {probe && (
        <p className="text-xs text-emerald-400">
          {t('mc.s.probeOk')} <span className="font-mono">{probe}</span>
        </p>
      )}

      <div className="flex items-center gap-3">
        <Button onClick={() => void save()} disabled={!canSave}>
          {busy ? t('mc.s.checking') : t('mc.s.saveCheck')}
        </Button>
        <span className="text-xs text-muted">
          {t('mc.s.passwordHidden')}
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
  const t = useT();
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
      await api<{ ok: boolean; configured: boolean }>(`${base('minecraft', serverId)}/config/companion`, {
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
        <h2 className="font-semibold">{t('mc.s.companion')}</h2>
        <Badge variant={status?.companionConfigured ? 'success' : 'outline'}>
          {status?.companionConfigured ? t('mc.s.configured') : t('mc.s.notConfigured')}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        {t('mc.s.companionHint')}
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>{t('mc.s.address')}</Label>
          <Input
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="http://10.0.0.2:8085"
          />
        </div>
        <div>
          <Label>{t('mc.s.token')}</Label>
          <Input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="new-password"
            placeholder={status?.companionConfigured ? t('mc.s.replace') : t('mc.s.tokenFromConfig')}
          />
          {!tokenIsAscii && <ErrorText>{t('mc.s.tokenAscii')}</ErrorText>}
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">{t('mc.s.saved')}</p>}

      <div className="flex items-center gap-2">
        <Button onClick={() => void save()} disabled={!canSave}>
          {busy ? t('common.saving') : t('common.save')}
        </Button>
        {status?.companionConfigured && (
          <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
            {t('mc.s.disable')}
          </Button>
        )}
      </div>
    </Card>
  );
}
