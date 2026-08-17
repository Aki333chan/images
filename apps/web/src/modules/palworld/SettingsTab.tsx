import { useCallback, useEffect, useState } from 'react';
import type { PalworldConfigStatusDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../../components/ui';
import type { ModuleTabProps } from '../registry';

const base = (serverId: string) => `/api/modules/palworld/servers/${serverId}`;

/**
 * Настройки подключения к REST API Palworld.
 *
 * Наружу отдаются только флаги: ни адрес, ни пароль администратора обратно
 * не приходят — ровно как с RCON-паролем в модуле Minecraft.
 */
export function PalworldSettingsTab({ serverId }: ModuleTabProps) {
  const [status, setStatus] = useState<PalworldConfigStatusDto | null>(null);
  const [baseUrl, setBaseUrl] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [probe, setProbe] = useState('');

  const load = useCallback(() => {
    api<PalworldConfigStatusDto>(`${base(serverId)}/config`)
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
              ? { baseUrl: null, adminPassword: null }
              : { baseUrl: baseUrl.trim(), adminPassword: password },
          ),
        },
      );
      setPassword('');
      if (disable) setBaseUrl('');
      setProbe(res.probe ?? (disable ? 'Подключение отключено' : 'Сохранено'));
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!status) return <Spinner />;

  const canSave = baseUrl.trim().length > 0 && password.length > 0 && !busy;

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">REST API Palworld</h2>
          <Badge variant={status.configured ? 'success' : 'outline'}>
            {status.configured ? 'настроено' : 'не настроено'}
          </Badge>
        </div>

        <p className="text-xs text-muted">
          Palworld администрируется по собственному REST API. RCON игра тоже понимает, но
          разработчики пометили его устаревшим и объявили, что он перестанет работать — поэтому
          панель использует REST.
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <Label>Адрес REST API</Label>
            <Input
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="http://10.0.0.2:8212"
            />
          </div>
          <div>
            <Label>Пароль администратора</Label>
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              placeholder={
                status.configured ? 'ввести заново для замены' : 'AdminPassword из настроек сервера'
              }
            />
          </div>
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
          {status.configured && (
            <Button variant="outline" onClick={() => void save(true)} disabled={busy}>
              Отключить
            </Button>
          )}
        </div>
      </Card>

      <Card className="space-y-2 text-xs text-muted">
        <h3 className="text-sm font-semibold text-neutral-100">Что включить на игровом сервере</h3>
        <p>
          В <code>PalWorldSettings.ini</code> задайте <code>RESTAPIEnabled=True</code>,{' '}
          <code>RESTAPIPort=8212</code> и непустой <code>AdminPassword</code>, затем перезапустите
          сервер. Логин у API всегда <code>admin</code> — он задан игрой и не настраивается.
        </p>
        <p className="text-amber-400">
          Порт REST API наружу выставлять нельзя: HTTPS сервер не умеет, и пароль администратора
          уходит в заголовке практически открытым текстом. Адрес указывайте приватный — через тот
          же туннель, что и RCON.
        </p>
      </Card>
    </div>
  );
}
