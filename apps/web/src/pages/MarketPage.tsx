import { useCallback, useEffect, useState } from 'react';
import {
  MARKET_SOURCES,
  type MarketHitDto,
  type MarketMatch,
  type MarketPluginDto,
  type MarketSearchResponseDto,
  type MarketSourceId,
  type MarketVersionDto,
  type MarketVersionsResponseDto,
  type PluginInstallResultDto,
  type ServerTargetDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, ErrorText, Input, Spinner } from '../components/ui';
import { PluginIcon } from './PluginIcon';
import { Modal } from '../components/Modal';

const BASE = '/api/modules/minecraft/market';

/**
 * Маркет плагинов: поиск по Modrinth и Hangar.
 *
 * ГЛАВНОЕ ПРАВИЛО ЭКРАНА: заявленная автором совместимость — подсказка, а не
 * фильтр. Панель показывает ВСЕ найденные плагины и ВСЕ их версии, включая
 * те, где текущая версия сервера не заявлена. Огромная часть плагинов
 * прекрасно работает на ядрах новее заявленных — автор просто не обновил
 * метаданные, и прятать их значило бы решать за человека.
 *
 * Подсветка совпадений — есть. Скрытие — нет.
 */
export function MarketPage() {
  const [query, setQuery] = useState('');
  const [data, setData] = useState<MarketSearchResponseDto | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<MarketHitDto | null>(null);
  const [targets, setTargets] = useState<ServerTargetDto[]>([]);

  useEffect(() => {
    api<ServerTargetDto[]>(`${BASE}/targets`)
      .then(setTargets)
      .catch(() => setTargets([]));
  }, []);

  const search = useCallback((q: string) => {
    setLoading(true);
    setError('');
    api<MarketSearchResponseDto>(`${BASE}/search?q=${encodeURIComponent(q)}&limit=20`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  // Поиск по мере ввода, но с задержкой: у Modrinth есть лимит запросов,
  // и дёргать оба источника на каждую букву незачем.
  useEffect(() => {
    const timer = setTimeout(() => search(query.trim()), query.trim() ? 450 : 0);
    return () => clearTimeout(timer);
  }, [query, search]);

  const failed = data?.sources.filter((s) => !s.ok) ?? [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-bold">Маркет плагинов</h1>
        <span className="text-xs text-muted">
          {MARKET_SOURCES.map((s) => s.label).join(' · ')}
        </span>
      </div>

      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Название плагина: luckperms, essentials, vault…"
      />

      {failed.length > 0 && (
        <p className="text-xs text-amber-400">
          Не ответил: {failed.map((s) => sourceLabel(s.source)).join(', ')}. Показано то, что
          нашлось у остальных.
        </p>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {loading && <Spinner />}

      {!loading && data && data.hits.length === 0 && (
        <p className="text-sm text-muted">Ничего не нашлось. Попробуйте другое слово.</p>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {data?.hits.map((hit) => (
          <button
            key={`${hit.source}-${hit.id}`}
            className="rounded-lg border border-border p-3 text-left hover:bg-white/5"
            onClick={() => setSelected(hit)}
          >
            <div className="flex gap-3">
              <PluginIcon url={hit.iconUrl} title={hit.title} size={48} />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="truncate font-medium">{hit.title}</span>
                  <Badge variant="outline">{sourceLabel(hit.source)}</Badge>
                </div>
                <p className="mt-0.5 line-clamp-2 text-xs text-muted">{hit.description}</p>
                <div className="mt-1 flex flex-wrap gap-x-3 text-[11px] text-muted">
                  {hit.author && <span>{hit.author}</span>}
                  <span>{formatDownloads(hit.downloads)} загрузок</span>
                </div>
              </div>
            </div>
          </button>
        ))}
      </div>

      {selected && (
        <PluginModal
          hit={selected}
          targets={targets}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

/** Карточка плагина: описание, заявленные ядра и версии, все релизы. */
function PluginModal({
  hit,
  targets,
  onClose,
}: {
  hit: MarketHitDto;
  targets: ServerTargetDto[];
  onClose: () => void;
}) {
  const [plugin, setPlugin] = useState<MarketPluginDto | null>(null);
  const [versions, setVersions] = useState<MarketVersionsResponseDto | null>(null);
  const [error, setError] = useState('');
  const [installing, setInstalling] = useState<MarketVersionDto | null>(null);

  // Сравниваем с первым сервером просто чтобы бейджи не были пустыми; на шаге
  // выбора сервера в мастере они пересчитываются под выбранный.
  const compareWith = targets[0]?.serverId;
  const path = `${BASE}/${hit.source}/${encodeURIComponent(hit.id)}`;

  useEffect(() => {
    api<MarketPluginDto>(path)
      .then(setPlugin)
      .catch((e: Error) => setError(e.message));
    api<MarketVersionsResponseDto>(
      `${path}/versions${compareWith ? `?serverId=${compareWith}` : ''}`,
    )
      .then(setVersions)
      .catch((e: Error) => setError(e.message));
  }, [path, compareWith]);

  return (
    <Modal title={hit.title} onClose={onClose}>
      {error && <ErrorText>{error}</ErrorText>}
      {!plugin ? (
        <Spinner />
      ) : (
        <div className="space-y-4">
          <div className="flex gap-3">
            <PluginIcon url={plugin.iconUrl} title={plugin.title} size={64} />
            <div className="min-w-0">
              <p className="text-sm">{plugin.description}</p>
              <div className="mt-1 flex flex-wrap gap-2 text-[11px] text-muted">
                <Badge variant="outline">{sourceLabel(plugin.source)}</Badge>
                <span>{formatDownloads(plugin.downloads)} загрузок</span>
                <a className="text-primary underline" href={plugin.pageUrl} target="_blank" rel="noreferrer">
                  страница плагина
                </a>
              </div>
            </div>
          </div>

          {/* Все заявленные ядра и версии игры — как их отдаёт источник. */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">Заявленные ядра</div>
              <div className="mt-1 flex flex-wrap gap-1">
                {plugin.loaders.length === 0 ? (
                  <span className="text-xs text-muted">не указаны</span>
                ) : (
                  plugin.loaders.map((l) => <Badge key={l} variant="outline">{l}</Badge>)
                )}
              </div>
            </div>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">
                Заявленные версии Minecraft
              </div>
              <div className="mt-1 flex flex-wrap gap-1">
                {plugin.gameVersions.length === 0 ? (
                  <span className="text-xs text-muted">не указаны</span>
                ) : (
                  plugin.gameVersions.map((v) => <Badge key={v} variant="outline">{v}</Badge>)
                )}
              </div>
            </div>
          </div>

          <div>
            <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-sm font-semibold">Все версии</h3>
              {versions?.comparedTo && (
                <span className="text-[11px] text-muted">
                  бейджи сверены с «{versions.comparedTo.name}»
                  {versions.comparedTo.gameVersion ? ` (${versions.comparedTo.gameVersion}` : ''}
                  {versions.comparedTo.loader ? `, ${versions.comparedTo.loader})` : versions.comparedTo.gameVersion ? ')' : ''}
                </span>
              )}
            </div>

            <p className="mb-2 text-xs text-muted">
              Показаны все опубликованные версии. Бейдж «не заявлено» — это только отметка о
              метаданных релиза: плагины часто работают на ядрах новее заявленных. Установить
              можно любую версию из списка.
            </p>

            {!versions ? (
              <Spinner />
            ) : (
              <ul className="max-h-72 space-y-2 overflow-y-auto pr-1">
                {versions.versions.map((v) => (
                  <li key={v.id} className="rounded border border-border p-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                          <span className="truncate">{v.name}</span>
                          {v.channel !== 'release' && <Badge variant="outline">{v.channel}</Badge>}
                          <MatchBadge compatibility={v.compatibility} />
                        </div>
                        <div className="mt-0.5 text-[11px] text-muted">
                          {new Date(v.publishedAt).toLocaleDateString('ru-RU')}
                          {v.loaders.length > 0 && ` · ${v.loaders.join(', ')}`}
                          {v.gameVersions.length > 0 && ` · ${v.gameVersions.join(', ')}`}
                        </div>
                      </div>
                      <Button size="sm" onClick={() => setInstalling(v)}>
                        Установить
                      </Button>
                    </div>
                    {v.changelog && (
                      <details className="mt-1">
                        <summary className="cursor-pointer text-[11px] text-muted">
                          Что изменилось
                        </summary>
                        <p className="mt-1 max-h-32 overflow-y-auto whitespace-pre-wrap text-[11px] text-muted">
                          {v.changelog}
                        </p>
                      </details>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {installing && plugin && (
        <InstallWizard
          plugin={plugin}
          version={installing}
          targets={targets}
          onClose={() => setInstalling(null)}
        />
      )}
    </Modal>
  );
}

/**
 * Установка в три шага.
 *
 * Шаг с выбором сервера показывается ВСЕГДА, даже когда сервер один: серверов
 * станет больше, и интерфейс сразу делается под это, а не под сегодняшний
 * частный случай.
 */
function InstallWizard({
  plugin,
  version,
  targets,
  onClose,
}: {
  plugin: MarketPluginDto;
  version: MarketVersionDto;
  targets: ServerTargetDto[];
  onClose: () => void;
}) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [serverId, setServerId] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<PluginInstallResultDto | null>(null);

  const target = targets.find((t) => t.serverId === serverId) ?? null;
  const running = target?.status === 'running' || target?.status === 'starting';

  async function install() {
    setBusy(true);
    setError('');
    try {
      setResult(
        await api<PluginInstallResultDto>(
          `/api/modules/minecraft/servers/${serverId}/plugins/install`,
          {
            method: 'POST',
            body: JSON.stringify({
              source: plugin.source,
              pluginId: plugin.id,
              versionId: version.id,
            }),
          },
        ),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Установка ${plugin.title}`} onClose={onClose}>
      <div className="space-y-4">
        <ol className="flex flex-wrap gap-2 text-[11px]">
          {['1. Версия', '2. Сервер', '3. Подтверждение'].map((label, i) => (
            <li
              key={label}
              className={`rounded px-2 py-1 ${
                step === i + 1 ? 'bg-primary/20 text-primary' : 'text-muted'
              }`}
            >
              {label}
            </li>
          ))}
        </ol>

        {step === 1 && (
          <div className="space-y-3">
            <div className="rounded border border-border p-3">
              <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                {version.name}
                {version.channel !== 'release' && <Badge variant="outline">{version.channel}</Badge>}
                <MatchBadge compatibility={version.compatibility} />
              </div>
              <div className="mt-1 text-[11px] text-muted">
                Опубликована {new Date(version.publishedAt).toLocaleDateString('ru-RU')}
                {version.fileName && ` · ${version.fileName}`}
                {version.fileSizeBytes ? ` · ${formatSize(version.fileSizeBytes)}` : ''}
              </div>
              <div className="mt-1 text-[11px] text-muted">
                Ядра: {version.loaders.join(', ') || 'не указаны'} · Версии:{' '}
                {version.gameVersions.join(', ') || 'не указаны'}
              </div>
            </div>
            <p className="text-xs text-muted">
              Выбрана эта версия. Вернуться к списку можно, закрыв окно, — блокировок по
              совместимости нет, ставится любая.
            </p>
            <div className="flex justify-end">
              <Button onClick={() => setStep(2)}>Дальше</Button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-3">
            <p className="text-sm">На какой сервер ставим?</p>
            {targets.length === 0 ? (
              <p className="text-sm text-muted">
                Нет серверов с модулем Minecraft — плагин ставить некуда.
              </p>
            ) : (
              <ul className="space-y-2">
                {targets.map((t) => (
                  <li key={t.serverId}>
                    <button
                      className={`w-full rounded border p-3 text-left ${
                        serverId === t.serverId
                          ? 'border-primary bg-primary/10'
                          : 'border-border hover:bg-white/5'
                      }`}
                      onClick={() => setServerId(t.serverId)}
                    >
                      <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                        {t.name}
                        {t.status && <Badge variant="outline">{t.status}</Badge>}
                      </div>
                      <div className="text-[11px] text-muted">
                        {t.gameVersion || t.loader
                          ? `${t.loader ?? 'ядро неизвестно'} ${t.gameVersion ?? ''}`
                          : 'версия сервера не определена — бейджи совместимости будут пустыми'}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-between">
              <Button variant="ghost" onClick={() => setStep(1)}>
                Назад
              </Button>
              <Button disabled={!serverId} onClick={() => setStep(3)}>
                Дальше
              </Button>
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-3">
            {result ? (
              <>
                <p className="text-sm text-emerald-400">{result.message}</p>
                <p className="text-[11px] text-muted">
                  {result.fileName} · {formatSize(result.sizeBytes)}
                </p>
                <div className="flex justify-end">
                  <Button onClick={onClose}>Готово</Button>
                </div>
              </>
            ) : (
              <>
                <dl className="space-y-1 text-sm">
                  <Row label="Плагин" value={`${plugin.title} (${sourceLabel(plugin.source)})`} />
                  <Row label="Версия" value={version.name} />
                  <Row label="Сервер" value={target?.name ?? '—'} />
                  <Row label="Куда" value="plugins/" />
                </dl>

                {/* Предупреждение только если сервер сейчас работает: у
                    выключенного плагин просто подхватится при старте. */}
                {running ? (
                  <p className="rounded border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-300">
                    Сервер сейчас запущен. Плагин будет загружен в папку, но заработает только
                    после перезапуска сервера.
                  </p>
                ) : (
                  <p className="text-xs text-muted">
                    Сервер сейчас выключен — плагин подхватится при следующем запуске.
                  </p>
                )}

                {version.compatibility?.gameVersion === 'not-declared' && (
                  <p className="text-xs text-muted">
                    Автор не заявил эту версию Minecraft для выбранного релиза. Это не значит, что
                    плагин не заработает — метаданные часто отстают. Установка не блокируется.
                  </p>
                )}

                {error && <ErrorText>{error}</ErrorText>}

                <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-between">
                  <Button variant="ghost" disabled={busy} onClick={() => setStep(2)}>
                    Назад
                  </Button>
                  <Button disabled={busy} onClick={() => void install()}>
                    {busy ? 'Ставим…' : 'Установить'}
                  </Button>
                </div>
              </>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <dt className="shrink-0 text-muted">{label}:</dt>
      <dd className="min-w-0 break-words">{value}</dd>
    </div>
  );
}

/**
 * Бейдж совпадения — ИНФОРМАЦИОННЫЙ. Он ничего не блокирует и ничего не
 * прячет: рядом с любым его состоянием кнопка «Установить» одинаково активна.
 */
function MatchBadge({ compatibility }: { compatibility?: { gameVersion: MarketMatch; loader: MarketMatch } }) {
  if (!compatibility) return null;
  const { gameVersion, loader } = compatibility;
  if (gameVersion === 'unknown' && loader === 'unknown') return null;

  if (gameVersion === 'match' && loader !== 'not-declared') {
    return <Badge variant="success">совпадает с сервером</Badge>;
  }
  return (
    <span title="Автор не заявил эту версию. Плагин всё равно можно поставить.">
      <Badge variant="outline">не заявлено для этой версии</Badge>
    </span>
  );
}

function sourceLabel(id: MarketSourceId): string {
  return MARKET_SOURCES.find((s) => s.id === id)?.label ?? id;
}

function formatDownloads(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)} млн`;
  if (value >= 1000) return `${Math.round(value / 1000)} тыс.`;
  return String(value);
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
  return `${Math.round(bytes / 1024)} КБ`;
}
