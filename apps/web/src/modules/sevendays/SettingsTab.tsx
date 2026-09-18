import { useCallback, useEffect, useState } from 'react';
import type { SevenDaysCompanionStatusDto, SevenDaysConfigStatusDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';
import { CompanionSetup } from './CompanionSetup';
import { useI18n } from '../../i18n';

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
  const { t, formatDateTime } = useI18n();
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
      const res = await api<{ ok: boolean; configured: boolean; online?: boolean }>(
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
      setProbe(
        t(
          disable
            ? 'sdtd.connection.disabled'
            : res.online === true
              ? 'sdtd.connection.connected'
              : 'sdtd.connection.savedOffline',
        ),
      );
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return error ? <ErrorText>{error}</ErrorText> : <Spinner />;

  const canSave = host.trim().length > 0 && password.length > 0 && !busy;

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">{t('sdtd.connection.console')}</h2>
          <Badge variant={status.telnetConfigured ? 'success' : 'outline'}>
            {t(
              status.telnetConfigured
                ? 'sdtd.connection.configured'
                : 'sdtd.connection.notConfigured',
            )}
          </Badge>
        </div>

        <p className="text-xs text-muted">{t('sdtd.connection.consoleHint')}</p>

        <div className="grid gap-3 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <Label>{t('sdtd.connection.consoleHost')}</Label>
            <Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="10.0.0.2" />
          </div>
          <div>
            <Label>{t('sdtd.connection.port')}</Label>
            <Input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
          </div>
        </div>

        <div>
          <Label>{t('sdtd.connection.password')}</Label>
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={
              status.telnetConfigured
                ? t('sdtd.connection.replaceSecret')
                : t('sdtd.connection.passwordHint')
            }
          />
        </div>

        {error && <ErrorText>{error}</ErrorText>}
        {probe && <p className="break-words text-xs text-emerald-400">{probe}</p>}
        {status.lastSeenAt && (
          <p className="text-xs text-muted">
            {t('sdtd.connection.lastSeen', { date: formatDateTime(status.lastSeenAt) })}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={() => void save()} disabled={!canSave}>
            {t(busy ? 'sdtd.connection.saving' : 'sdtd.connection.save')}
          </Button>
          {status.telnetConfigured && (
            <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
              {t('sdtd.connection.disable')}
            </Button>
          )}
        </div>
      </Card>

      <CompanionCard serverId={serverId} />

      <Card className="space-y-2 text-xs text-muted">
        <h3 className="text-sm font-semibold text-neutral-100">
          {t('sdtd.connection.serverSetup')}
        </h3>
        <p>{t('sdtd.connection.serverSetupHint')}</p>
        <p>{t('sdtd.connection.passwordRequired')}</p>
        <p className="text-amber-400">{t('sdtd.connection.privateOnly')}</p>
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
  const { t, formatDateTime } = useI18n();
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
      const res = await api<{
        ok: boolean;
        configured: boolean;
        online?: boolean;
        compatible?: boolean | null;
      }>(`${base(serverId)}/companion`, {
        method: 'PUT',
        body: JSON.stringify(
          disable
            ? { host: null, port: null, token: null }
            : { host: host.trim(), port: Number(port) || 8110, token },
        ),
      });
      setToken('');
      if (disable) {
        setHost('');
        setPort('8110');
      }
      setProbe(
        t(
          disable
            ? 'sdtd.connection.disabled'
            : res.online !== true
              ? 'sdtd.connection.savedOffline'
              : res.compatible === false
                ? 'sdtd.connection.incompatible'
                : 'sdtd.connection.connected',
        ),
      );
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return error ? <ErrorText>{error}</ErrorText> : <Spinner />;

  const canSave = host.trim().length > 0 && token.length >= 16 && !busy;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{t('sdtd.connection.mod')}</h2>
        <Badge variant={!status.configured ? 'outline' : status.online ? 'success' : 'destructive'}>
          {t(
            !status.configured
              ? 'sdtd.connection.notConfigured'
              : status.online
                ? 'sdtd.connection.online'
                : 'sdtd.connection.offline',
          )}
        </Badge>
      </div>

      <p className="text-xs text-muted">{t('sdtd.connection.modHint')}</p>

      <CompanionSetup serverId={serverId} />

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="sm:col-span-2">
          <Label>{t('sdtd.connection.modHost')}</Label>
          <Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="10.0.0.2" />
        </div>
        <div>
          <Label>{t('sdtd.connection.port')}</Label>
          <Input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
        </div>
      </div>

      <div>
        <Label>{t('sdtd.connection.token')}</Label>
        <Input
          type="password"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          autoComplete="new-password"
          placeholder={t(
            status.configured ? 'sdtd.connection.replaceSecret' : 'sdtd.connection.tokenLength',
          )}
        />
        {/* Тот же токен идёт в companion.cfg: им мод авторизуется в панели,
            а панель — в моде. Разные значения — самая частая причина
            «мод не отвечает». */}
        <p className="mt-1 text-[11px] text-muted">{t('sdtd.connection.tokenHint')}</p>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {probe && <p className="break-words text-xs text-emerald-400">{probe}</p>}
      {status.configured && (
        <p className="text-xs text-muted">
          {status.version ? `${t('sdtd.connection.version', { version: status.version })} ` : ''}
          {status.lastSeenAt
            ? t('sdtd.connection.lastSeen', { date: formatDateTime(status.lastSeenAt) })
            : t('sdtd.connection.neverSeen')}
        </p>
      )}

      {status.online && (
        <div className="space-y-2 text-xs text-muted">
          {status.compatible === false ? (
            <p className="text-amber-400">{t('sdtd.connection.incompatible')}</p>
          ) : status.capabilities == null ? (
            <p>{t('sdtd.connection.legacy')}</p>
          ) : (
            <>
              <p>{t('sdtd.connection.capabilities')}</p>
              <div className="flex flex-wrap gap-2">
                {status.capabilities.map((capability) => (
                  <Badge key={capability} variant="outline">
                    {t(`sdtd.capability.${capability}`)}
                  </Badge>
                ))}
                {status.capabilities.length === 0 && (
                  <span>{t('sdtd.connection.noCapabilities')}</span>
                )}
              </div>
            </>
          )}
          <p>{t('sdtd.connection.languageHint', { language: status.language ?? '—' })}</p>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => void save()} disabled={!canSave}>
          {t(busy ? 'sdtd.connection.saving' : 'sdtd.connection.save')}
        </Button>
        {status.configured && (
          <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
            {t('sdtd.connection.disable')}
          </Button>
        )}
      </div>
    </Card>
  );
}
