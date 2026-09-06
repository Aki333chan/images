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
 * способом вернуться к выбору, и разводить эти два случая по разным окнам
 * значило бы поддерживать две копии одного списка.
 *
 * ОБЯЗАТЕЛЬНЫЙ COMPANION СЮДА НЕ ПОПАДАЕТ. Его панель ставит молча и без
 * галочки: без него она не видит ни инвентарей, ни экономики, ни списка
 * плагинов, и предлагать выбор там, где выбора нет, — обман.
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
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [results, setResults] = useState<AddonInstallResultDto[] | null>(null);

  const missing = state.optional.filter((a) => !a.installed);

  return (
    <Modal title={t('addons.title')} size="lg" onClose={onClose}>
      <div className="space-y-4">
        <p className="text-xs text-muted">{t('addons.intro')}</p>

        <ul className="space-y-1">
          {state.optional.map((addon) => (
            <li key={addon.id}>
              {/* Вся строка — цель нажатия: попасть пальцем в галочку 13×13
                  на телефоне нельзя, а список тут для того и стоит. */}
              <label
                className={
                  '-mx-2 flex items-start gap-3 rounded-md px-2 py-2 ' +
                  (addon.installed ? 'opacity-60' : 'cursor-pointer hover:bg-white/5')
                }
              >
                <input
                  type="checkbox"
                  className="mt-0.5 h-5 w-5 shrink-0 accent-primary"
                  checked={addon.installed || chosen.has(addon.id)}
                  // Установленный не снять и не поставить заново: галочка
                  // рядом с ним — не выбор, а сообщение «уже есть».
                  disabled={addon.installed || busy}
                  onChange={(e) => toggle(addon.id, e.target.checked)}
                />
                <span className="min-w-0 text-sm">
                  <span className="font-medium">{addon.displayName}</span>
                  {addon.installed && (
                    <span className="ml-2 text-xs text-emerald-400">{t('addons.installed')}</span>
                  )}
                  <span className="mt-0.5 block text-xs text-muted">{t(addon.aboutKey)}</span>
                </span>
              </label>
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

  function toggle(id: string, on: boolean) {
    setChosen((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Ничего не отмечено — просто закрываем.
   *
   * Так и задумано: «Установить выбранное» без единой галочки — это «я
   * посмотрел и ничего не хочу», и отвечать на это ошибкой было бы придиркой.
   */
  async function install() {
    if (chosen.size === 0) {
      onClose();
      return;
    }
    setBusy(true);
    setError('');
    try {
      const response = await api<AddonInstallResponseDto>(`/api/servers/${serverId}/addons/install`, {
        method: 'POST',
        body: JSON.stringify({ ids: [...chosen] }),
      });
      setResults(response.results);
      setChosen(new Set());
      // Состояние перечитываем, а не досочиняем по ответу: часть строк могла
      // не поставиться, и галочка «уже есть» должна стоять только у тех, кто
      // правда лёг на диск.
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
