import { useCallback, useEffect, useState } from 'react';
import type {
  AddonInstallResponseDto,
  AddonInstallResultDto,
  ServerAddonsDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useApiText, useT } from '../i18n';
import { Button, ErrorText, Spinner } from './ui';
import { Modal } from './Modal';

/**
 * Окно с нашими собственными плагинами для этого сервера.
 *
 * ОДНО ОКНО НА ДВА ПОВОДА. Открывается оно само при первом заходе на сервер,
 * где чего-то не хватает, и вручную — кнопкой «Рекомендуемые плагины». Второе
 * важнее, чем кажется: после «не предлагать» кнопка остаётся единственным
 * способом вернуться к пакету, и разводить эти два случая по разным окнам
 * значило бы поддерживать две копии одного списка.
 *
 * Это один логический пакет, хотя на диск ложатся отдельные jar: так можно
 * независимо обновлять и диагностировать компоненты, не оставляя человеку
 * возможность случайно собрать несовместимую половину экосистемы.
 */
export function AddonsModal({
  serverId,
  state,
  onClose,
  onDone,
}: {
  serverId: string;
  /** Уже прочитанное состояние: окно открывается мгновенно, без ожидания. */
  state: ServerAddonsDto;
  onClose: () => void;
  /** Что-то поставили — обновить состояние снаружи. */
  onDone: (next: ServerAddonsDto) => void;
}) {
  const t = useT();
  const apiText = useApiText();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [results, setResults] = useState<AddonInstallResultDto[] | null>(null);

  const packageAddons = state.required ? [state.required, ...state.optional] : state.optional;
  const missing = packageAddons.filter((addon) => !addon.installed);

  return (
    <Modal title={t('addons.title')} size="lg" onClose={onClose}>
      <div className="space-y-4">
        <p className="text-xs text-muted">{t('addons.intro')}</p>
        {state.vaultBridgeInstalled === false && (
          <p className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200">
            {t('addons.vaultNote')}
          </p>
        )}

        <ul className="space-y-1">
          {packageAddons.map((addon) => (
            <li key={addon.id}>
              <div
                className={'-mx-2 rounded-md px-2 py-2 ' + (addon.installed ? 'opacity-60' : '')}
              >
                <span className="min-w-0 text-sm">
                  <span className="font-medium">{addon.displayName}</span>
                  {addon.installed && (
                    <span className="ml-2 text-xs text-emerald-400">{t('addons.installed')}</span>
                  )}
                  <span className="mt-0.5 block text-xs text-muted">{t(addon.aboutKey)}</span>
                  {addon.requires.length > 0 && (
                    <span className="mt-0.5 block text-xs text-sky-300">
                      {t('addons.requires', {
                        names: addon.requires
                          .map((dependency) => dependency.displayName)
                          .join(', '),
                      })}
                    </span>
                  )}
                </span>
              </div>
            </li>
          ))}
        </ul>

        {missing.length === 0 && <p className="text-xs text-emerald-400">{t('addons.allSet')}</p>}

        {results && (
          <ul className="space-y-0.5 text-xs">
            {results.map((r) => (
              <li key={r.id} className={r.ok ? 'text-emerald-400' : 'text-amber-400'}>
                {r.displayName} — {apiText(r.message, r.messageValues)}
              </li>
            ))}
          </ul>
        )}

        {error && <ErrorText>{error}</ErrorText>}

        {/* Кнопки в столбик на телефоне: три штуки в строку не влезают, а
            переносятся они так, что «не предлагать» оказывается под пальцем
            рядом с «установить».
            flex-wrap на широком экране — не перестраховка: без него ряд с
            justify-end при нехватке места вылезал ЗА ЛЕВЫЙ край окна, и
            «Закрыть» просто не было видно. Длина подписей зависит от языка,
            так что «влезает» — не то свойство, на которое можно полагаться. */}
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:justify-end">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            {t('common.close')}
          </Button>
          <Button variant="outline" disabled={busy} onClick={() => void dismiss()}>
            {t('addons.dismiss')}
          </Button>
          <Button disabled={busy || missing.length === 0} onClick={() => void install()}>
            {busy ? <Spinner /> : t('addons.install')}
          </Button>
        </div>
      </div>
    </Modal>
  );

  async function install() {
    setBusy(true);
    setError('');
    try {
      const response = await api<AddonInstallResponseDto>(
        `/api/servers/${serverId}/addons/install`,
        {
          method: 'POST',
        },
      );
      setResults(response.results);
      // Состояние перечитываем, а не досочиняем по ответу: часть строк могла
      // не поставиться, и отметка «уже есть» должна стоять только у тех, кто
      // действительно лёг на диск.
      onDone(await api<ServerAddonsDto>(`/api/servers/${serverId}/addons`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function dismiss() {
    setBusy(true);
    setError('');
    try {
      onDone(
        await api<ServerAddonsDto>(`/api/servers/${serverId}/addons/dismiss`, { method: 'POST' }),
      );
      onClose();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }
}

/**
 * Состояние аддонов сервера плюс автоустановка обязательного.
 *
 * Один запрос на заход: он же ставит companion, если его нет, и он же
 * приносит всё, что нужно для решения «показывать ли поп-ап». Разделять их
 * значило бы два обращения там, где хватает одного, и гонку между ними.
 */
export function useServerAddons(serverId: string | undefined, moduleId: string | null) {
  const [state, setState] = useState<ServerAddonsDto | null>(null);

  useEffect(() => {
    if (!serverId) return;
    let alive = true;
    api<ServerAddonsDto>(`/api/servers/${serverId}/addons/bootstrap`, { method: 'POST' })
      .then((next) => alive && setState(next))
      // Молча: аддоны — это подсказка, а не работа страницы сервера. Отказ
      // здесь не должен закрывать собой консоль и кнопки питания.
      .catch(() => alive && setState(null));
    return () => {
      alive = false;
    };
    // moduleId в зависимостях не случайно: сменили модуль сервера — поменялся
    // и набор аддонов, и companion теперь может быть нужен, а мог и перестать.
  }, [serverId, moduleId]);

  const refresh = useCallback(async () => {
    if (!serverId) return;
    setState(await api<ServerAddonsDto>(`/api/servers/${serverId}/addons`));
  }, [serverId]);

  return { state, setState, refresh };
}
