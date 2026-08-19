import { useCallback, useEffect, useState } from 'react';
import { DISABLED_PLUGINS_DIR, type InstalledPluginDto, type InstalledPluginsResponseDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Spinner } from '../../components/ui';
import { Modal } from '../../components/Modal';

/**
 * Управление установленными плагинами прямо из карточки сервера.
 *
 * Два разных способа выключить, и путать их нельзя:
 *
 *   «Выключить» — горячее, через PluginManager сервера. Мгновенно и без
 *   даунтайма, но best-effort по природе Bukkit: плагины регистрируют
 *   слушателей, задачи и команды, и не все аккуратно снимают их за собой.
 *   Ровно поэтому /reload считается рискованной командой. Об этом сказано
 *   прямо в интерфейсе, а рядом стоит кнопка перезапуска.
 *
 *   «Отключить файлом» — перенос .jar в plugins/.disabled/. Переживает
 *   перезапуск сервера кем угодно: через панель, через Pterodactyl или руками
 *   по SFTP, — потому что это состояние диска, а не отметка в базе панели.
 */
export function InstalledPluginsPanel({
  serverId,
  onRestart,
}: {
  serverId: string;
  /** Перезапуск сервера — рядом с горячим переключением он нужен под рукой. */
  onRestart?: () => void;
}) {
  const [data, setData] = useState<InstalledPluginsResponseDto | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [removing, setRemoving] = useState<InstalledPluginDto | null>(null);

  const base = `/api/modules/minecraft/servers/${serverId}/plugins`;

  const load = useCallback(() => {
    setError('');
    api<InstalledPluginsResponseDto>(`${base}/installed`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [base]);

  useEffect(load, [load]);

  async function act(key: string, run: () => Promise<{ message: string }>) {
    setBusy(key);
    setError('');
    setNotice('');
    try {
      setNotice((await run()).message);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const toggleRuntime = (plugin: InstalledPluginDto, enabled: boolean) =>
    act(plugin.name, () =>
      api<{ message: string }>(`${base}/${encodeURIComponent(plugin.name)}/enabled`, {
        method: 'POST',
        body: JSON.stringify({ enabled }),
      }),
    );

  const toggleFile = (plugin: InstalledPluginDto, disabled: boolean) =>
    act(plugin.name, () =>
      api<{ message: string }>(`${base}/file-state`, {
        method: 'POST',
        body: JSON.stringify({ fileName: plugin.fileName, disabled }),
      }),
    );

  if (error && !data) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Установленные плагины</h2>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={load}>
            Обновить
          </Button>
          {onRestart && (
            <Button size="sm" variant="outline" onClick={onRestart}>
              Перезапустить сервер
            </Button>
          )}
        </div>
      </div>

      {!data.companionAvailable && <p className="text-xs text-amber-400">{data.reason}</p>}
      {!data.filesAvailable && (
        <p className="text-xs text-amber-400">
          Файлы сервера недоступны через Pterodactyl — отключение файлом и удаление работать не
          будут.
        </p>
      )}

      <p className="text-xs text-muted">
        «Выключить» действует сразу, без перезапуска, но это best-effort: не все плагины Bukkit
        аккуратно переживают горячее отключение — по той же причине команда <code>/reload</code>{' '}
        считается рискованной. Если после переключения сервер повёл себя странно, перезапустите
        его. «Отключить файлом» переносит .jar в <code>plugins/{DISABLED_PLUGINS_DIR}/</code> — это
        переживёт перезапуск, но подействует только после него.
      </p>

      {data.plugins.length === 0 ? (
        <p className="text-xs text-muted">Плагинов не найдено.</p>
      ) : (
        <ul className="space-y-2">
          {data.plugins.map((plugin) => (
            <li
              key={`${plugin.name}-${plugin.fileName ?? ''}`}
              className="flex flex-wrap items-center justify-between gap-2 rounded border border-border px-3 py-2"
            >
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                  {plugin.name}
                  {plugin.version && <span className="text-xs text-muted">{plugin.version}</span>}
                  <StateBadge state={plugin.state} />
                  {plugin.protected && <Badge variant="outline">плагин панели</Badge>}
                </div>
                {plugin.fileName && (
                  <div className="truncate font-mono text-[11px] text-muted">{plugin.fileName}</div>
                )}
              </div>

              <div className="flex flex-wrap gap-2">
                {plugin.state !== 'disabled-file' && data.companionAvailable && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={plugin.protected || busy === plugin.name}
                    title={
                      plugin.protected
                        ? 'Плагин самой панели: выключив его, панель потеряет связь с сервером'
                        : 'Горячее переключение через PluginManager, без перезапуска'
                    }
                    onClick={() => void toggleRuntime(plugin, plugin.state !== 'enabled')}
                  >
                    {plugin.state === 'enabled' ? 'Выключить' : 'Включить'}
                  </Button>
                )}

                {plugin.fileName && data.filesAvailable && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={plugin.protected || busy === plugin.name}
                    onClick={() => void toggleFile(plugin, plugin.state !== 'disabled-file')}
                  >
                    {plugin.state === 'disabled-file' ? 'Вернуть файл' : 'Отключить файлом'}
                  </Button>
                )}

                {plugin.fileName && data.filesAvailable && (
                  <Button
                    size="sm"
                    variant="destructive"
                    disabled={plugin.protected || busy === plugin.name}
                    onClick={() => setRemoving(plugin)}
                  >
                    Удалить
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {notice && <p className="text-xs text-emerald-400">{notice}</p>}

      {removing && (
        <RemoveDialog
          plugin={removing}
          onClose={() => setRemoving(null)}
          onConfirm={(withData) => {
            const plugin = removing;
            setRemoving(null);
            void act(plugin.name, () =>
              api<{ message: string }>(`${base}/remove`, {
                method: 'POST',
                body: JSON.stringify({
                  fileName: plugin.fileName,
                  pluginName: plugin.name,
                  withData,
                }),
              }),
            );
          }}
        />
      )}
    </Card>
  );
}

function StateBadge({ state }: { state: InstalledPluginDto['state'] }) {
  if (state === 'enabled') return <Badge variant="success">включён</Badge>;
  if (state === 'disabled-runtime') return <Badge variant="outline">выключен</Badge>;
  return <Badge variant="destructive">отключён файлом</Badge>;
}

/**
 * Подтверждение удаления.
 *
 * Папка данных отдельным флажком и по умолчанию НЕ отмечена: в ней конфиги,
 * права, экономика — то, что восстановить неоткуда, и удалять это заодно,
 * без явного согласия, нельзя.
 */
function RemoveDialog({
  plugin,
  onClose,
  onConfirm,
}: {
  plugin: InstalledPluginDto;
  onClose: () => void;
  onConfirm: (withData: boolean) => void;
}) {
  const [withData, setWithData] = useState(false);

  return (
    <Modal title={`Удалить плагин ${plugin.name}?`} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-sm">
          Файл <span className="font-mono text-xs">{plugin.fileName}</span> будет удалён с сервера.
          Плагин перестанет загружаться после перезапуска.
        </p>

        <label className="-mx-2 flex cursor-pointer items-start gap-3 rounded-md px-2 py-2 hover:bg-white/5">
          <input
            type="checkbox"
            className="mt-0.5 h-5 w-5 shrink-0 accent-primary"
            checked={withData}
            onChange={(e) => setWithData(e.target.checked)}
          />
          <span className="text-sm">
            Удалить и папку данных <span className="font-mono text-xs">plugins/{plugin.name}/</span>
            <span className="mt-1 block text-xs text-muted">
              Там лежат конфиги и данные плагина — права, экономика, настройки. Восстановить их
              будет неоткуда. По умолчанию не трогаем.
            </span>
          </span>
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button variant="destructive" onClick={() => onConfirm(withData)}>
            {withData ? 'Удалить с данными' : 'Удалить'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
