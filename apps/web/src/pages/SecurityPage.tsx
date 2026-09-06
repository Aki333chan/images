import { useEffect, useState } from 'react';
import type { SessionDto } from '@aurum/shared';
import { api } from '../lib/api';
import { useI18n } from '../i18n';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../components/ui';

export function SecurityPage() {
  const { t, formatDateTime } = useI18n();
  const { me, refreshMe } = useAuth();
  const [sessions, setSessions] = useState<SessionDto[] | null>(null);
  const [setup, setSetup] = useState<{ secret: string; otpauthUrl: string } | null>(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  const loadSessions = () => void api<SessionDto[]>('/api/auth/sessions').then(setSessions);
  useEffect(loadSessions, []);

  async function revoke(id: string) {
    await api(`/api/auth/sessions/${id}`, { method: 'DELETE' });
    loadSessions();
  }

  async function startSetup() {
    setError('');
    setSetup(await api('/api/auth/totp/setup', { method: 'POST' }));
  }

  async function enable() {
    setError('');
    try {
      await api('/api/auth/totp/enable', { method: 'POST', body: JSON.stringify({ code }) });
      setSetup(null);
      setCode('');
      await refreshMe();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function disable() {
    setError('');
    try {
      await api('/api/auth/totp/disable', { method: 'POST', body: JSON.stringify({ password }) });
      setPassword('');
      await refreshMe();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="text-xl font-bold">{t('nav.security')}</h1>

      <Card>
        <h2 className="mb-3 font-semibold">{t('sec.totp')}</h2>
        {me?.user.totpEnabled ? (
          <div className="space-y-3">
            <Badge variant="success">{t('sec.on')}</Badge>
            <div>
              <Label>{t('sec.passwordToDisable')}</Label>
              <div className="flex gap-2">
                <Input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                <Button variant="destructive" onClick={() => void disable()} disabled={!password}>
                  {t('sec.disable')}
                </Button>
              </div>
            </div>
          </div>
        ) : setup ? (
          <div className="space-y-3">
            <p className="text-sm text-muted">
              {t('sec.addSecret')}
            </p>
            <code className="block break-all rounded bg-black/40 p-2 text-xs">{setup.secret}</code>
            <p className="break-all text-xs text-muted">{setup.otpauthUrl}</p>
            <div>
              <Label>{t('sec.code')}</Label>
              <div className="flex gap-2">
                <Input value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} />
                <Button onClick={() => void enable()} disabled={code.length !== 6}>
                  {t('sec.enable')}
                </Button>
              </div>
            </div>
          </div>
        ) : (
          <Button variant="outline" onClick={() => void startSetup()}>
            {t('sec.setup')}
          </Button>
        )}
        <ErrorText>{error}</ErrorText>
      </Card>

      <Card>
        <h2 className="mb-3 font-semibold">{t('sec.sessions')}</h2>
        {!sessions ? (
          <Spinner />
        ) : (
          <div className="space-y-2">
            {sessions.map((s) => (
              <div
                key={s.id}
                className="flex items-center justify-between rounded-md border border-border p-3"
              >
                <div>
                  <div className="text-sm">
                    {s.userAgent?.slice(0, 60) ?? t('sec.unknownDevice')}{' '}
                    {s.current && <Badge>{t('sec.current')}</Badge>}
                  </div>
                  <div className="text-xs text-muted">
                    {s.ip ?? ''} · {t('sec.lastSeen', { date: formatDateTime(s.lastUsedAt) })}
                  </div>
                </div>
                {!s.current && (
                  <Button size="sm" variant="destructive" onClick={() => void revoke(s.id)}>
                    {t('sec.revoke')}
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
