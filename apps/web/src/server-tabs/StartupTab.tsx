import { useCallback, useEffect, useState } from 'react';
import type { PteroStartupDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';
import type { ServerTabProps } from './registry';

const base = (serverId: string) => `/api/servers/${serverId}/startup`;

/**
 * Переменные запуска и докер-образ.
 *
 * ЧТО МОЖНО МЕНЯТЬ, РЕШАЕТ EGG, а не панель: Pterodactyl отдаёт только
 * видимые переменные и правит только те, что egg разрешил. Панель это
 * показывает как есть и ничего не обходит.
 *
 * Сама команда запуска здесь только для чтения — и это ограничение
 * Pterodactyl, а не недоделка: Client API менять её не позволяет, она
 * правится администратором панели. Показываем, чтобы было видно, во что
 * складываются переменные.
 */
export function StartupTab({ serverId }: ServerTabProps) {
  const [data, setData] = useState<PteroStartupDto | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [busy, setBusy] = useState('');

  const load = useCallback(() => {
    setError('');
    return api<PteroStartupDto>(base(serverId))
      .then((d) => {
        setData(d);
        setValues(Object.fromEntries(d.variables.map((v) => [v.envVariable, v.serverValue])));
      })
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function saveVariable(key: string) {
    setBusy(key);
    setError('');
    setSaved('');
    try {
      const updated = await api<PteroStartupDto>(`${base(serverId)}/variable`, {
        method: 'PUT',
        body: JSON.stringify({ key, value: values[key] ?? '' }),
      });
      setData(updated);
      setSaved(`Сохранено: ${key}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function saveImage(image: string) {
    setBusy('__image');
    setError('');
    setSaved('');
    try {
      const updated = await api<PteroStartupDto>(`${base(serverId)}/docker-image`, {
        method: 'PUT',
        body: JSON.stringify({ image }),
      });
      setData(updated);
      setSaved('Образ изменён — он применится при следующем запуске сервера');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  }

  if (!data && !error) return <Spinner />;

  const imageOptions = Object.entries(data?.dockerImages ?? {}).map(([label, value]) => ({
    value,
    label: `${label} — ${value}`,
  }));

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <h2 className="font-semibold">Команда запуска</h2>
        <p className="text-xs text-muted">
          Менять её из панели нельзя — так устроен Pterodactyl: команда правится
          администратором самой панели. Здесь она видна, чтобы понимать, во что
          складываются переменные ниже.
        </p>
        <pre className="overflow-x-auto rounded-md border border-border bg-surface p-3 font-mono text-[11.5px] leading-relaxed">
          {data?.startupCommand || '—'}
        </pre>
      </Card>

      {imageOptions.length > 0 && (
        <Card className="space-y-3">
          <h2 className="font-semibold">Докер-образ</h2>
          <p className="text-xs text-muted">
            Список задан egg сервера. Смена образа — обычный способ поменять версию Java:
            применится при следующем запуске.
          </p>
          <Select
            value={data?.currentDockerImage ?? ''}
            onChange={(v) => void saveImage(v)}
            options={
              data?.currentDockerImage &&
              !imageOptions.some((o) => o.value === data.currentDockerImage)
                ? // Текущий образ вне списка egg — так бывает после ручной
                  // правки в панели. Показываем как есть, а не прячем.
                  [{ value: data.currentDockerImage, label: `${data.currentDockerImage} (не из списка egg)` }, ...imageOptions]
                : imageOptions
            }
          />
        </Card>
      )}

      <Card className="space-y-3">
        <h2 className="font-semibold">Переменные</h2>
        {error && <ErrorText>{error}</ErrorText>}
        {saved && <p className="text-xs text-emerald-400">{saved}</p>}

        {data?.variables.length === 0 ? (
          <p className="text-muted">Egg этого сервера не отдаёт ни одной переменной.</p>
        ) : (
          <ul className="space-y-4">
            {data?.variables.map((v) => (
              <li key={v.envVariable}>
                <Label>{v.name}</Label>
                <p className="mb-1.5 text-[11px] text-muted">{v.description}</p>
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Input
                    value={values[v.envVariable] ?? ''}
                    onChange={(e) =>
                      setValues((prev) => ({ ...prev, [v.envVariable]: e.target.value }))
                    }
                    disabled={!v.isEditable}
                    placeholder={v.defaultValue}
                  />
                  {v.isEditable && (
                    <Button
                      size="sm"
                      variant="outline"
                      className="sm:w-auto"
                      disabled={busy !== '' || (values[v.envVariable] ?? '') === v.serverValue}
                      onClick={() => void saveVariable(v.envVariable)}
                    >
                      {busy === v.envVariable ? 'Сохраняем…' : 'Сохранить'}
                    </Button>
                  )}
                </div>
                <p className="mt-1 font-mono text-[10.5px] text-muted">
                  {v.envVariable}
                  {!v.isEditable && ' · egg не разрешает менять'}
                  {v.rules && ` · ${v.rules}`}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
