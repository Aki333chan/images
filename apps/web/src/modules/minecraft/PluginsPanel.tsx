import { useEffect, useState } from 'react';
import type { MinecraftPluginsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Card, ErrorText, Spinner } from '../../components/ui';

/**
 * Какие из известных панели плагинов стоят на этом сервере.
 *
 * Показывается на странице сервера под вкладками. Смысл — ответить на вопрос
 * «почему у меня нет кнопки Heal / вкладки Права» до того, как он возникнет:
 * список сразу говорит, чего не хватает и что это даёт.
 */
export function PluginsPanel({ serverId }: { serverId: string }) {
  const [data, setData] = useState<MinecraftPluginsDto | null>(null);
  const [error, setError] = useState('');
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    setData(null);
    setError('');
    api<MinecraftPluginsDto>(`/api/modules/minecraft/servers/${serverId}/plugins`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  if (error) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Поддерживаемые плагины</h2>
        {data.available && (
          <span className="text-xs text-muted">всего на сервере: {data.installed.length}</span>
        )}
      </div>

      {!data.available && <p className="text-xs text-amber-400">{data.reason}</p>}

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
        дополнительно не нужно: панель проверяет список сама.
      </p>

      {data.available && data.installed.length > 0 && (
        <div>
          <button
            className="text-xs text-muted underline underline-offset-2"
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
