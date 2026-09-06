import { useCallback, useEffect, useRef, useState } from 'react';
import type { MinecraftPluginsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useServerRuntime } from '../../lib/server-runtime';
import { Badge, Button, Card, ErrorText, Spinner } from '../../components/ui';
import { useApiText, useT } from '../../i18n';

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
 * набор плагинов действительно может стать другим.
 *
 * Хранилище — sessionStorage, а не переменная в модуле. Переменная живёт до
 * первого F5, а перезагружают страницу как раз тогда, когда «всё пропало»:
 * человек видит сплошное «нет», жмёт обновить — и получает ровно то же самое.
 * sessionStorage переживает перезагрузку и умирает вместе со вкладкой.
 */
const CACHE_PREFIX = 'aurum.plugins.';

export interface Remembered {
  data: MinecraftPluginsDto;
  /**
   * Момент запуска сервера, при котором эти данные получены, или null — если
   * сервер тогда не работал. По нему видно, тот ли это ещё запуск.
   */
  bootAt: number | null;
}

function readRemembered(serverId: string): Remembered | null {
  try {
    const raw = sessionStorage.getItem(CACHE_PREFIX + serverId);
    return raw ? (JSON.parse(raw) as Remembered) : null;
  } catch {
    // Приватный режим, переполненное хранилище, чужой мусор по тому же ключу —
    // память это удобство, и падать из-за неё экран не должен.
    return null;
  }
}

function writeRemembered(serverId: string, value: Remembered): void {
  try {
    sessionStorage.setItem(CACHE_PREFIX + serverId, JSON.stringify(value));
  } catch {
    /* см. выше */
  }
}

/**
 * Годится ли запомненное к тому, что происходит с сервером сейчас.
 *
 * Сервер не работает — годится: набор плагинов с прошлого запуска и есть
 * последнее, что о нём известно, и показать его правильнее, чем «нет».
 * Сервер работает — только если это ТОТ ЖЕ запуск: после перезапуска плагины
 * могли поменяться, и выдавать вчерашний список за сегодняшний нельзя.
 *
 * Чистая функция и экспортируется ради тестов: ошибка здесь незаметна на
 * глаз — таблица выглядит правдоподобно в обоих случаях.
 */
export function rememberedFits(kept: Remembered, bootAt: number | null): boolean {
  if (bootAt === null) return true;
  return kept.bootAt === bootAt;
}

/**
 * Сколько раз подряд пробовать достучаться до companion, пока сервер работает.
 *
 * Повторы нужны потому, что момент, когда Wings объявляет сервер запущенным, и
 * момент, когда companion поднял свой HTTP-порт, не совпадают. Одной попытки
 * не хватало: она уходила в пустоту, и до нажатия «Обновить» руками таблица
 * так и оставалась пустой.
 *
 * Тридцать попыток с шагом опроса (10 с) — это пять минут: столько сервер
 * поднимается с большим запасом. Дальше молчание означает не «ещё грузится», а
 * «настроено неверно», и долбиться в него бесконечно незачем — кнопка
 * «Обновить» никуда не делась.
 */
const MAX_RETRIES = 30;

export function PluginsPanel({ serverId }: { serverId: string }) {
  const t = useT();
  const apiText = useApiText();
  const runtime = useServerRuntime(serverId);
  const [data, setData] = useState<MinecraftPluginsDto | null>(null);
  /** Показанное — из памяти, живого ответа сейчас нет. */
  const [stale, setStale] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showAll, setShowAll] = useState(false);
  /** Сколько раз подряд companion не ответил на этом запуске сервера. */
  const retriesRef = useRef(0);

  const load = useCallback(
    async (bootAt: number | null) => {
      setBusy(true);
      setError('');
      try {
        const fresh = await api<MinecraftPluginsDto>(
          `/api/modules/minecraft/servers/${serverId}/plugins`,
        );
        if (fresh.available) {
          retriesRef.current = 0;
          writeRemembered(serverId, { data: fresh, bootAt });
          setData(fresh);
          setStale(false);
          return;
        }
        // Живого ответа нет. Память годится, только если сервер с тех пор не
        // перезапускался.
        retriesRef.current += 1;
        const kept = readRemembered(serverId);
        if (kept && rememberedFits(kept, bootAt)) {
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

  // Первый показ и каждый новый запуск сервера. runId растёт ровно тогда,
  // когда сервер поднялся заново, — это и есть «обновись со стартом».
  useEffect(() => {
    retriesRef.current = 0;
    void load(runtime.bootAt);
    // bootAt намеренно не в зависимостях: он уточняется на каждом опросе на
    // доли секунды, и по нему запрос уходил бы каждые десять секунд.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, runtime.runId]);

  // Первый запрос уходит раньше первого ответа Pterodactyl, поэтому свежие
  // данные поначалу оказываются привязаны к «неизвестно какому» запуску.
  // Как только запуск становится известен — дописываем его в память. Только
  // из null и только один раз: иначе после перезапуска старые данные молча
  // привязались бы к новому запуску и выдавались бы за сегодняшние.
  useEffect(() => {
    if (!data?.available || stale || runtime.bootAt === null) return;
    const kept = readRemembered(serverId);
    if (kept && kept.bootAt === null) {
      writeRemembered(serverId, { data: kept.data, bootAt: runtime.bootAt });
    }
  }, [serverId, data, stale, runtime.bootAt]);

  // Догоняем: сервер уже работает, а companion ещё не ответил. Считаем по
  // тикам общего опроса, чтобы не заводить второй таймер на ту же задачу.
  useEffect(() => {
    if (runtime.tick === 0) return;
    if (data?.available) return;
    if (runtime.state !== 'running') return;
    if (retriesRef.current === 0 || retriesRef.current > MAX_RETRIES) return;
    void load(runtime.bootAt);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runtime.tick]);

  if (error && !data) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  const retrying =
    !data.available && runtime.state === 'running' && retriesRef.current <= MAX_RETRIES;

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{t('mc.plug.title')}</h2>
        <div className="flex items-center gap-2">
          {data.available && !stale && (
            <span className="text-xs text-muted">{t('mc.plug.total', { count: data.installed.length })}</span>
          )}
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => void load(runtime.bootAt)}>
            {t(busy ? 'mc.plug.checking' : 'common.refresh')}
          </Button>
        </div>
      </div>

      {stale && (
        <p className="text-xs text-warn">{t('mc.plug.stale')}</p>
      )}
      {!data.available && !stale && (
        <p className="text-xs text-warn">
          {retrying ? t('mc.plug.waiting') : apiText(data.reason)}
        </p>
      )}

      <ul className="space-y-2">
        {data.known.map((plugin) => (
          <li key={plugin.id} className="flex items-start gap-3">
            <Badge variant={plugin.installed ? 'success' : 'outline'}>
              {t(plugin.installed ? 'mc.plug.yes' : 'mc.plug.no')}
            </Badge>
            <div className="min-w-0">
              <div className="text-sm font-medium">
                {plugin.displayName}
                {plugin.version && <span className="ml-2 text-xs text-muted">{plugin.version}</span>}
              </div>
              <div className="text-xs text-muted">{t(plugin.givesKey)}</div>
            </div>
          </li>
        ))}
      </ul>

      <p className="text-xs text-muted">{t('mc.plug.hint')}</p>

      {data.installed.length > 0 && (
        <div>
          {/* Ссылка-переключатель высотой в строку текста (16 px) в палец не
              попадает. Область нажатия увеличена отступами, вид не изменился. */}
          <button
            className="-mx-2 flex min-h-11 items-center px-2 text-xs text-muted underline underline-offset-2 sm:mx-0 sm:min-h-0 sm:px-0"
            onClick={() => setShowAll((v) => !v)}
          >
            {t(showAll ? 'mc.plug.hide' : 'mc.plug.show')} {t('mc.plug.allPlugins')}
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
