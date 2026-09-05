import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DISABLED_PLUGINS_DIR, type InstalledPluginDto, type InstalledPluginsResponseDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Spinner } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { useApiText, useT } from '../../i18n';

/** Ответ действия над плагином: ключ фразы и подстановки к ней. */
interface PluginActionOutcome {
  message: string;
  messageValues?: Record<string, string>;
}

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
  const t = useT();
  const apiText = useApiText();
  const [data, setData] = useState<InstalledPluginsResponseDto | null>(null);
  const [error, setError] = useState('');
  // Сообщение приходит ключом и подстановками: имена плагина и файла в нём
  // не переводятся, а всё вокруг — да.
  const [notice, setNotice] = useState<{ key: string; values?: Record<string, string> } | null>(
    null,
  );
  const [busy, setBusy] = useState<string | null>(null);
  const [removing, setRemoving] = useState<InstalledPluginDto | null>(null);
  const navigate = useNavigate();

  const base = `/api/modules/minecraft/servers/${serverId}/plugins`;

  const load = useCallback(() => {
    setError('');
    api<InstalledPluginsResponseDto>(`${base}/installed`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [base]);

  useEffect(load, [load]);

  async function act(
    key: string,
    run: () => Promise<{ message: string; messageValues?: Record<string, string> }>,
  ) {
    setBusy(key);
    setError('');
    setNotice(null);
    try {
      const done = await run();
      setNotice({ key: done.message, values: done.messageValues });
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const toggleRuntime = (plugin: InstalledPluginDto, enabled: boolean) =>
    act(plugin.name, () =>
      api<PluginActionOutcome>(`${base}/${encodeURIComponent(plugin.name)}/enabled`, {
        method: 'POST',
        body: JSON.stringify({ enabled }),
      }),
    );

  const toggleFile = (plugin: InstalledPluginDto, disabled: boolean) =>
    act(plugin.name, () =>
      api<PluginActionOutcome>(`${base}/file-state`, {
        method: 'POST',
        body: JSON.stringify({ fileName: plugin.fileName, disabled }),
      }),
    );

  if (error && !data) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{t('mc.ip.title')}</h2>
        <div className="flex gap-2">
          {/* serverId в адресе не косметика: маркет по нему открывает нужную
              вкладку (плагины или моды — по ядру этого сервера) и заранее
              отмечает его в мастере установки. */}
          <Button size="sm" variant="outline" onClick={() => navigate(`/market?serverId=${serverId}`)}>
            {t('mc.ip.market')}
          </Button>
          <Button size="sm" variant="outline" onClick={load}>
            {t('common.refresh')}
          </Button>
          {onRestart && (
            <Button size="sm" variant="outline" onClick={onRestart}>
              {t('mc.ip.restart')}
            </Button>
          )}
        </div>
      </div>

      {!data.companionAvailable && (
        <p className="text-xs text-amber-400">{apiText(data.reason)}</p>
      )}
      {!data.filesAvailable && (
        <p className="text-xs text-amber-400">{t('mc.ip.filesUnavailable')}</p>
      )}

      <p className="text-xs text-muted">
        {t('mc.ip.hintA')} <code>/reload</code> {t('mc.ip.hintB')}{' '}
        <code>plugins/{DISABLED_PLUGINS_DIR}/</code> {t('mc.ip.hintC')}
      </p>

      {data.plugins.length === 0 ? (
        <p className="text-xs text-muted">{t('mc.ip.none')}</p>
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
                  {plugin.protected && (
                    <span
                      title={
                        plugin.protectedReasonKey
                          ? t(plugin.protectedReasonKey, { name: plugin.name })
                          : undefined
                      }
                    >
                      <Badge variant="outline">{t('mc.ip.neededByPanel')}</Badge>
                    </span>
                  )}
                </div>
                {plugin.fileName && (
                  <div className="truncate font-mono text-[11px] text-muted">{plugin.fileName}</div>
                )}
                {plugin.protected && plugin.protectedReasonKey && (
                  <div className="mt-1 text-[11px] text-muted">
                    {t(plugin.protectedReasonKey, { name: plugin.name })}
                  </div>
                )}
              </div>

              {/* Запрет односторонний: выключить защищённый плагин нельзя,
                  а включить или вернуть файл — можно. Поэтому «выключающие»
                  кнопки у него не показываются вовсе, а «включающие» — да:
                  спрятанная кнопка честнее заблокированной, по которой
                  непонятно, что вообще произойдёт при нажатии. */}
              <div className="flex flex-wrap gap-2">
                {plugin.state !== 'disabled-file' &&
                  data.companionAvailable &&
                  !(plugin.protected && plugin.state === 'enabled') && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy === plugin.name}
                      title={t('mc.ip.toggleHint')}
                      onClick={() => void toggleRuntime(plugin, plugin.state !== 'enabled')}
                    >
                      {plugin.state === 'enabled' ? t('mc.ip.disable') : t('mc.ip.enable')}
                    </Button>
                  )}

                {plugin.fileName &&
                  data.filesAvailable &&
                  !(plugin.protected && plugin.state !== 'disabled-file') && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy === plugin.name}
                      onClick={() => void toggleFile(plugin, plugin.state !== 'disabled-file')}
                    >
                      {plugin.state === 'disabled-file'
                        ? t('mc.ip.fileRestore')
                        : t('mc.ip.fileDisable')}
                    </Button>
                  )}

                {plugin.fileName && data.filesAvailable && !plugin.protected && (
                  <Button
                    size="sm"
                    variant="destructive"
                    disabled={busy === plugin.name}
                    onClick={() => setRemoving(plugin)}
                  >
                    {t('common.delete')}
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {notice && (
        <p className="text-xs text-emerald-400">{apiText(notice.key, notice.values)}</p>
      )}

      {removing && (
        <RemoveDialog
          plugin={removing}
          onClose={() => setRemoving(null)}
          onConfirm={(withData) => {
            const plugin = removing;
            setRemoving(null);
            void act(plugin.name, () =>
              api<PluginActionOutcome>(`${base}/remove`, {
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
  const t = useT();
  if (state === 'enabled') return <Badge variant="success">{t('mc.ip.stateEnabled')}</Badge>;
  if (state === 'disabled-runtime') return <Badge variant="outline">{t('mc.ip.stateDisabled')}</Badge>;
  return <Badge variant="destructive">{t('mc.ip.stateDisabledFile')}</Badge>;
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
  const t = useT();
  const [withData, setWithData] = useState(false);

  return (
    <Modal title={t('mc.ip.removeTitle', { name: plugin.name })} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-sm">
          {t('mc.ip.removeFileA')}{' '}
          <span className="font-mono text-xs">{plugin.fileName}</span> {t('mc.ip.removeFileB')}
        </p>

        <label className="-mx-2 flex cursor-pointer items-start gap-3 rounded-md px-2 py-2 hover:bg-white/5">
          <input
            type="checkbox"
            className="mt-0.5 h-5 w-5 shrink-0 accent-primary"
            checked={withData}
            onChange={(e) => setWithData(e.target.checked)}
          />
          <span className="text-sm">
            {t('mc.ip.removeData')}{' '}
            <span className="font-mono text-xs">plugins/{plugin.name}/</span>
            <span className="mt-1 block text-xs text-muted">{t('mc.ip.removeDataHint')}</span>
          </span>
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="destructive" onClick={() => onConfirm(withData)}>
            {withData ? t('mc.ip.removeWithData') : t('common.delete')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
