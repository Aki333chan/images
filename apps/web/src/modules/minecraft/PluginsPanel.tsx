import { useCallback, useEffect, useState } from 'react';
import type { MinecraftPluginsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useServerRuntime } from '../../lib/server-runtime';
import { Badge, Button, Card, ErrorText, Spinner } from '../../components/ui';

/**
 * Что панель помнит о плагинах этого сервера.
 *
 * Список приходит от companion-плагина, а он живёт внутри игрового сервера:
 * пока сервер выключен или ещё поднимается, спросить некого, и честный ответ
 * «проверить нечем» превращает всю таблицу в сплошное «нет». Выглядит это
 * как «плагины пропали», хотя не изменилось ровно ничего.
 *
 * Поэтому последний удавшийся ответ запоминается — до следующего ЗАПУСКА
 * сервера, а не до конца сессии: перезапуск это единственный момент, когда
 * набор плагинов действительно может стать другим, и с него панель начинает
 * узнавать всё заново.
 */
const remembered = new Map<string, { data: MinecraftPluginsDto; runId: number }>();

export function PluginsPanel({ serverId }: { serverId: string }) {
  const runtime = useServerRuntime(serverId);
  const [data, setData] = useState<MinecraftPluginsDto | null>(null);
  /** Показанное — из памяти, живого ответа сейчас нет. */
  const [stale, setStale] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showAll, setShowAll] = useState(false);

  const load = useCallback(
    async (runId: number) => {
      setBusy(true);
      setError('');
      try {
        const fresh = await api<MinecraftPluginsDto>(
          `/api/modules/minecraft/servers/${serverId}/plugins`,
        );
        if (fresh.available) {
          remembered.set(serverId, { data: fresh, runId });
          setData(fresh);
          setStale(false);
          return;
        }
        // Живого ответа нет. Память годится, только если сервер с тех пор не
        // перезапускался: после перезапуска набор плагинов мог измениться, и
        // выдавать вчерашний список за сегодняшний нельзя.
        const kept = remembered.get(serverId);
        if (kept && kept.runId === runId) {
          setData(kept.data);
          setStale(true);
        } else {
          setData(fresh);
          setStale(false);
        }
      } catch (e) {
        setError((e as Error).message);
      } finally {
        setBusy(false);
      }
    },
    [serverId],
  );

  // runId в зависимостях — это и есть обновление на старте и рестарте:
  // он растёт ровно тогда, когда сервер поднялся заново.
  useEffect(() => {
    void load(runtime.runId);
  }, [load, runtime.runId]);

  if (error && !data) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Поддерживаемые плагины</h2>
        <div className="flex items-center gap-2">
          {data.available && !stale && (
            <span className="text-xs text-muted">всего на сервере: {data.installed.length}</span>
          )}
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => void load(runtime.runId)}>
            {busy ? 'Проверяем…' : 'Обновить'}
          </Button>
        </div>
      </div>

      {stale && (
        <p className="text-xs text-amber-400">
          Сервер сейчас не отвечает — показано состояние на момент последней проверки. Список
          обновится сам, когда сервер запустится.
        </p>
      )}
      {!data.available && !stale && <p className="text-xs text-amber-400">{data.reason}</p>}

      <ul className="space-y-2">
        {data.known.map((plugin) => (
          <li key={plugin.id} className="flex items-start gap-3">
            <Badge variant={plugin.installed ? 'success' : 'outline'}>
              {plugin.installed ? 'есть' : 'нет'}
            </Badge>
            <div className="min-w-0">
              <div className="text-sm font-medium">
                {plugin.displayName}
                {plugin.version && (
                  <span className="ml-2 text-xs text-muted">{plugin.version}</span>
                )}
              </div>
              <div className="text-xs text-muted">{plugin.gives}</div>
            </div>
          </li>
        ))}
      </ul>

      <p className="text-xs text-muted">
        Плагина нет — соответствующие кнопки и вкладки просто не появляются. Ничего настраивать
        дополнительно не нужно: панель проверяет список сама. Выключить или удалить эти плагины из
        панели нельзя — на них держится половина её возможностей; снимать их можно только вручную,
        по SFTP.
      </p>

      {data.installed.length > 0 && (
        <div>
          {/* Ссылка-переключатель высотой в строку текста (16 px) в палец не
              попадает. Область нажатия увеличена отступами, вид не изменился. */}
          <button
            className="-mx-2 flex min-h-11 items-center px-2 text-xs text-muted underline underline-offset-2 sm:mx-0 sm:min-h-0 sm:px-0"
            onClick={() => setShowAll((v) => !v)}
          >
            {showAll ? 'Скрыть' : 'Показать'} все плагины сервера
          </button>
          {showAll && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {data.installed.map((plugin) => (
                <Badge key={plugin.name} variant={plugin.enabled ? 'default' : 'outline'}>
                  {plugin.name} {plugin.version}
                </Badge>
              ))}
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
