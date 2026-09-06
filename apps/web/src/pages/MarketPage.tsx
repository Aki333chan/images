import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  EMPTY_FILTERS,
  LOCALE_TAGS,
  formatBytes,
  MARKET_SORTS,
  MARKET_SORT_KEYS,
  MARKET_SOURCES,
  defaultProjectTypeFor,
  loadersFor,
  sourcesFor,
  type Locale,
  type MarketFilters,
  type MarketHitDto,
  type MarketMatch,
  type MarketPluginDto,
  type MarketProjectType,
  type MarketSearchResponseDto,
  type MarketSort,
  type MarketSourceId,
  type MarketVersionDto,
  type MarketVersionsResponseDto,
  type PluginInstallResultDto,
  type ServerTargetDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useApiText, useI18n, useT } from '../i18n';
import { Badge, Button, ErrorText, Input, Select, Spinner, Tabs } from '../components/ui';
import { PluginIcon } from './PluginIcon';
import { Modal } from '../components/Modal';

const BASE = '/api/modules/minecraft/market';

/**
 * Маркет: плагины (Modrinth, Hangar, SpigotMC) и моды (Modrinth).
 *
 * ГЛАВНОЕ ПРАВИЛО ЭКРАНА: заявленная автором совместимость — подсказка, а не
 * фильтр. Панель показывает ВСЕ найденные проекты и ВСЕ их версии, включая
 * те, где текущая версия сервера не заявлена. Огромная часть плагинов
 * прекрасно работает на ядрах новее заявленных — автор просто не обновил
 * метаданные, и прятать их значило бы решать за человека.
 *
 * Подсветка совпадений — есть. Скрытие — нет. Фильтры на этом экране ставит
 * человек галочкой; ни одна из них не проставляется панелью самостоятельно.
 *
 * ПЛАГИНЫ И МОДЫ РАЗДЕЛЬНО, а не одним списком. Плагин под Paper и мод под
 * Fabric ставятся в разные папки и на чужом сервере не загрузятся вовсе.
 * Свалить их в одну выдачу значило бы предлагать заведомо неработающее.
 */
export function MarketPage() {
  const { t, locale, formatDate } = useI18n();
  const [params] = useSearchParams();
  // Сервер, со страницы которого пришли: он решает, какая вкладка откроется
  // и какой сервер подставится в мастере установки.
  const fromServerId = params.get('serverId');

  const [type, setType] = useState<MarketProjectType>('plugin');
  const [sort, setSort] = useState<MarketSort>('relevance');
  const [filters, setFilters] = useState<MarketFilters>(EMPTY_FILTERS);
  const [query, setQuery] = useState('');

  const [data, setData] = useState<MarketSearchResponseDto | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<MarketHitDto | null>(null);
  const [targets, setTargets] = useState<ServerTargetDto[]>([]);
  const [gameVersions, setGameVersions] = useState<string[]>([]);

  // Ручное переключение вкладки нельзя отменять автоподстановкой: список
  // серверов приезжает асинхронно, и без этой отметки вкладка перепрыгивала бы
  // обратно уже после того, как человек её сменил.
  const typeTouched = useRef(false);

  useEffect(() => {
    api<ServerTargetDto[]>(`${BASE}/targets`)
      .then(setTargets)
      .catch(() => setTargets([]));
    // Справочник версий не критичен: без него просто не будет галочек версий.
    api<string[]>(`${BASE}/game-versions`)
      .then(setGameVersions)
      .catch(() => setGameVersions([]));
  }, []);

  // Вкладка по типу сервера, с которого пришли: на Paper искать моды
  // бессмысленно, на Fabric — плагины.
  useEffect(() => {
    if (typeTouched.current || !fromServerId) return;
    const target = targets.find((t) => t.serverId === fromServerId);
    if (target) setType(defaultProjectTypeFor(target.loader));
  }, [fromServerId, targets]);

  const switchType = useCallback((next: MarketProjectType) => {
    typeTouched.current = true;
    setType(next);
    // Ядра и источники у плагинов и модов разные: отмеченный «fabric» на
    // вкладке плагинов не значит ничего, а отмеченный Hangar на вкладке модов
    // дал бы вечно пустую выдачу. Версии игры общие — их сохраняем.
    setFilters((f) => ({ ...f, loaders: [], sources: [] }));
  }, []);

  const search = useCallback(
    (q: string) => {
      setLoading(true);
      setError('');
      const url = new URLSearchParams({ limit: '20', type, sort });
      if (q) url.set('q', q);
      if (filters.gameVersions.length) url.set('gameVersions', filters.gameVersions.join(','));
      if (filters.loaders.length) url.set('loaders', filters.loaders.join(','));
      if (filters.sources.length) url.set('sources', filters.sources.join(','));

      api<MarketSearchResponseDto>(`${BASE}/search?${url.toString()}`)
        .then(setData)
        .catch((e: Error) => setError(e.message))
        .finally(() => setLoading(false));
    },
    [type, sort, filters],
  );

  // Поиск по мере ввода, но с задержкой: у Modrinth есть лимит запросов,
  // и дёргать все источники на каждую букву незачем. Смена вкладки, сортировки
  // или галочки ждать не должна — там задержки нет.
  useEffect(() => {
    const timer = setTimeout(() => search(query.trim()), query.trim() ? 450 : 0);
    return () => clearTimeout(timer);
  }, [query, search]);

  const failed = data?.sources.filter((s) => !s.ok) ?? [];
  const activeSources = useMemo(() => sourcesFor(type), [type]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-bold">{t('market.title')}</h1>
        <span className="text-xs text-muted">
          {activeSources.map((id) => sourceLabel(id)).join(' · ')}
        </span>
      </div>

      <Tabs
        fill
        active={type}
        onChange={(id) => switchType(id as MarketProjectType)}
        tabs={[
          { id: 'plugin', label: t('market.plugins') },
          { id: 'mod', label: t('market.mods') },
        ]}
      />

      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          className="flex-1"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={
            type === 'mod'
              ? t('market.searchMod')
              : t('market.searchPlugin')
          }
        />
        <Select
          className="sm:w-56"
          value={sort}
          onChange={(v) => setSort(v as MarketSort)}
          options={MARKET_SORTS.map((s) => ({ value: s, label: t(MARKET_SORT_KEYS[s]) }))}
        />
      </div>

      <FilterBar
        type={type}
        filters={filters}
        onChange={setFilters}
        gameVersions={gameVersions}
      />

      {type === 'mod' && (
        <p className="text-xs text-muted">{t('market.modsOnlyModrinth')}</p>
      )}

      {failed.length > 0 && (
        <p className="text-xs text-amber-400">
          {t('market.sourcesFailed', {
            sources: failed.map((s) => sourceLabel(s.source)).join(', '),
          })}
        </p>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {loading && <Spinner />}

      {!loading && data && data.hits.length === 0 && (
        <p className="text-sm text-muted">{t('market.nothingFound')}</p>
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
                  {hit.premium && (
                    <span title={t('market.premiumHint')}>
                      <Badge variant="outline">{t('market.premium')}</Badge>
                    </span>
                  )}
                </div>
                <p className="mt-0.5 line-clamp-2 text-xs text-muted">{hit.description}</p>
                <div className="mt-1 flex flex-wrap gap-x-3 text-[11px] text-muted">
                  {hit.author && <span>{hit.author}</span>}
                  <span>{t('market.downloads', { value: formatDownloads(hit.downloads, locale) })}</span>
                  {hit.loaders.length > 0 && <span>{hit.loaders.join(', ')}</span>}
                  {hit.updatedAt && (
                    <span>{t('market.updated', { date: formatDate(hit.updatedAt) })}</span>
                  )}
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
          preferredServerId={fromServerId}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

/**
 * Комбинируемые фильтры.
 *
 * ГАЛОЧКИ, А НЕ ПЕРЕКЛЮЧАТЕЛИ: значения внутри одной группы складываются по
 * «или», группы между собой — по «и». Отметить 1.20 и 1.21 разом это нормальный
 * запрос, и радиокнопки его бы не выразили.
 *
 * Пустая группа означает «без ограничения», а не «ничего не показывать»:
 * галочки снимают, чтобы увидеть больше.
 */
function FilterBar({
  type,
  filters,
  onChange,
  gameVersions,
}: {
  type: MarketProjectType;
  filters: MarketFilters;
  onChange: (next: MarketFilters) => void;
  gameVersions: string[];
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [allVersions, setAllVersions] = useState(false);

  const sources = sourcesFor(type);
  const loaders = loadersFor(type);
  const active =
    filters.gameVersions.length + filters.loaders.length + filters.sources.length;

  // Версий Minecraft полсотни; сразу показываем свежие, остальные по кнопке —
  // иначе панель фильтров занимает весь экран ради того, что нужно раз в год.
  const shownVersions = allVersions ? gameVersions : gameVersions.slice(0, 12);

  function toggle<K extends keyof MarketFilters>(key: K, value: string) {
    const current = filters[key] as string[];
    const next = current.includes(value)
      ? current.filter((v) => v !== value)
      : [...current, value];
    onChange({ ...filters, [key]: next } as MarketFilters);
  }

  return (
    <div className="rounded-lg border border-border">
      <div className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
        <button
          type="button"
          className="text-sm font-medium"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
        >
          {t('market.filters')}
          {active > 0 ? ` · ${active}` : ''} {open ? '▲' : '▼'}
        </button>
        {active > 0 && (
          <Button size="sm" variant="ghost" onClick={() => onChange(EMPTY_FILTERS)}>
            {t('market.reset')}
          </Button>
        )}
      </div>

      {open && (
        <div className="space-y-3 border-t border-border px-3 py-3">
          {gameVersions.length > 0 && (
            <FilterGroup title={t('market.gameVersion')}>
              {shownVersions.map((v) => (
                <CheckChip
                  key={v}
                  label={v}
                  checked={filters.gameVersions.includes(v)}
                  onToggle={() => toggle('gameVersions', v)}
                />
              ))}
              {gameVersions.length > shownVersions.length && (
                <button
                  type="button"
                  className="text-[11px] text-primary underline"
                  onClick={() => setAllVersions(true)}
                >
                  {t('market.moreVersions', { count: gameVersions.length - shownVersions.length })}
                </button>
              )}
            </FilterGroup>
          )}

          <FilterGroup title={t(type === 'mod' ? 'market.loader' : 'market.core')}>
            {loaders.map((l) => (
              <CheckChip
                key={l}
                label={l}
                checked={filters.loaders.includes(l)}
                onToggle={() => toggle('loaders', l)}
              />
            ))}
          </FilterGroup>

          {/* Одному источнику галочка не нужна: выбирать не из чего. */}
          {sources.length > 1 && (
            <FilterGroup title={t('market.source')}>
              {sources.map((s) => (
                <CheckChip
                  key={s}
                  label={sourceLabel(s)}
                  checked={filters.sources.includes(s)}
                  onToggle={() => toggle('sources', s)}
                />
              ))}
            </FilterGroup>
          )}

          {type === 'plugin' && (
            <p className="text-[11px] text-muted">{t('market.spigetNoLoader')}</p>
          )}
        </div>
      )}
    </div>
  );
}

function FilterGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-muted">{title}</div>
      <div className="mt-1 flex flex-wrap items-center gap-1.5">{children}</div>
    </div>
  );
}

function CheckChip({
  label,
  checked,
  onToggle,
}: {
  label: string;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <label
      className={`flex cursor-pointer items-center gap-1.5 rounded border px-2 py-1 text-xs ${
        checked ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted'
      }`}
    >
      <input type="checkbox" className="h-3.5 w-3.5" checked={checked} onChange={onToggle} />
      {label}
    </label>
  );
}

/** Карточка проекта: описание, заявленные ядра и версии, все релизы. */
function PluginModal({
  hit,
  targets,
  preferredServerId,
  onClose,
}: {
  hit: MarketHitDto;
  targets: ServerTargetDto[];
  preferredServerId: string | null;
  onClose: () => void;
}) {
  const { t, locale, formatDate } = useI18n();
  const [plugin, setPlugin] = useState<MarketPluginDto | null>(null);
  const [versions, setVersions] = useState<MarketVersionsResponseDto | null>(null);
  const [error, setError] = useState('');
  const [installing, setInstalling] = useState<MarketVersionDto | null>(null);

  // Сверяем с тем сервером, со страницы которого пришли, а если пришли из
  // меню — с первым: так бейджи не пустуют. На шаге выбора сервера в мастере
  // они пересчитываются под выбранный.
  const compareWith =
    (preferredServerId && targets.some((t) => t.serverId === preferredServerId)
      ? preferredServerId
      : targets[0]?.serverId) ?? undefined;
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

  const premium = plugin?.premium ?? hit.premium ?? false;

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
              <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-muted">
                <Badge variant="outline">{sourceLabel(plugin.source)}</Badge>
                <Badge variant="outline">
                  {t(plugin.projectType === 'mod' ? 'market.mod' : 'market.plugin')}
                </Badge>
                <span>
                  {t('market.downloads', { value: formatDownloads(plugin.downloads, locale) })}
                </span>
                <a
                  className="text-primary underline"
                  href={plugin.pageUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  {t('market.sourcePage')}
                </a>
              </div>
            </div>
          </div>

          {premium && (
            <p className="rounded border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-300">
              {t('market.premiumFull')}
            </p>
          )}

          {plugin.externalFile && !premium && (
            <p className="text-xs text-muted">
              {t('market.externalFile')}
            </p>
          )}

          {/* Все заявленные ядра и версии игры — как их отдаёт источник. */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">
                {t(plugin.projectType === 'mod' ? 'market.declaredLoaders' : 'market.declaredCores')}
              </div>
              <div className="mt-1 flex flex-wrap gap-1">
                {plugin.loaders.length === 0 ? (
                  <span className="text-xs text-muted">
                    {t(plugin.source === 'spiget' ? 'market.spigetNoStore' : 'market.notDeclared')}
                  </span>
                ) : (
                  plugin.loaders.map((l) => (
                    <Badge key={l} variant="outline">
                      {l}
                    </Badge>
                  ))
                )}
              </div>
            </div>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-muted">
                {t('market.declaredVersions')}
              </div>
              <div className="mt-1 flex flex-wrap gap-1">
                {plugin.gameVersions.length === 0 ? (
                  <span className="text-xs text-muted">{t('market.notDeclared')}</span>
                ) : (
                  plugin.gameVersions.map((v) => (
                    <Badge key={v} variant="outline">
                      {v}
                    </Badge>
                  ))
                )}
              </div>
            </div>
          </div>

          <div>
            <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-sm font-semibold">{t('market.allVersions')}</h3>
              {versions?.comparedTo && (
                <span className="text-[11px] text-muted">
                  {t('market.comparedTo', { name: versions.comparedTo.name })}
                  {versions.comparedTo.gameVersion ? ` (${versions.comparedTo.gameVersion}` : ''}
                  {versions.comparedTo.loader
                    ? `, ${versions.comparedTo.loader})`
                    : versions.comparedTo.gameVersion
                      ? ')'
                      : ''}
                </span>
              )}
            </div>

            <p className="mb-2 text-xs text-muted">
              {t('market.versionsHint')}
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
                          {formatDate(v.publishedAt)}
                          {v.loaders.length > 0 && ` · ${v.loaders.join(', ')}`}
                          {v.gameVersions.length > 0 && ` · ${v.gameVersions.join(', ')}`}
                        </div>
                      </div>
                      <Button size="sm" disabled={premium} onClick={() => setInstalling(v)}>
                        {t('market.install')}
                      </Button>
                    </div>
                    {v.changelog && (
                      <details className="mt-1">
                        <summary className="cursor-pointer text-[11px] text-muted">
                          {t('market.changelog')}
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
          preferredServerId={preferredServerId}
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
 * частный случай. Сервер, со страницы которого пришли, просто отмечен заранее.
 */
function InstallWizard({
  plugin,
  version,
  targets,
  preferredServerId,
  onClose,
}: {
  plugin: MarketPluginDto;
  version: MarketVersionDto;
  targets: ServerTargetDto[];
  preferredServerId: string | null;
  onClose: () => void;
}) {
  // Экран маркета ещё не переведён целиком, но сообщение об установке
  // приходит от API ключом — показать его сырым значило бы вывести человеку
  // «mc.err.installedPluginRunning» вместо фразы.
  const { t, formatDate } = useI18n();
  const apiText = useApiText();
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [serverId, setServerId] = useState<string>(
    preferredServerId && targets.some((t) => t.serverId === preferredServerId)
      ? preferredServerId
      : '',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<PluginInstallResultDto | null>(null);

  const target = targets.find((t) => t.serverId === serverId) ?? null;
  const running = target?.status === 'running' || target?.status === 'starting';
  const folder = plugin.projectType === 'mod' ? 'mods/' : 'plugins/';
  const isMod = plugin.projectType === 'mod';

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
    <Modal title={t('market.installTitle', { name: plugin.title })} onClose={onClose}>
      <div className="space-y-4">
        <ol className="flex flex-wrap gap-2 text-[11px]">
          {[t('market.step1'), t('market.step2'), t('market.step3')].map((label, i) => (
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
                {t('market.published', { date: formatDate(version.publishedAt) })}
                {version.fileName && ` · ${version.fileName}`}
                {version.fileSizeBytes ? ` · ${formatBytes(version.fileSizeBytes, t)}` : ''}
              </div>
              <div className="mt-1 text-[11px] text-muted">
                {t('market.coresAndVersions', {
                  loaders: version.loaders.join(', ') || t('market.notDeclared'),
                  versions: version.gameVersions.join(', ') || t('market.notDeclared'),
                })}
              </div>
            </div>
            <p className="text-xs text-muted">
              {t('market.versionChosen')}
            </p>
            <div className="flex justify-end">
              <Button onClick={() => setStep(2)}>{t('market.next')}</Button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-3">
            <p className="text-sm">{t('market.whichServer')}</p>
            {targets.length === 0 ? (
              <p className="text-sm text-muted">
                {t('market.noTargets')}
              </p>
            ) : (
              <ul className="space-y-2">
                {targets.map((option) => (
                  <li key={option.serverId}>
                    <button
                      className={`w-full rounded border p-3 text-left ${
                        serverId === option.serverId
                          ? 'border-primary bg-primary/10'
                          : 'border-border hover:bg-white/5'
                      }`}
                      onClick={() => setServerId(option.serverId)}
                    >
                      <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                        {option.name}
                        {option.status && <Badge variant="outline">{option.status}</Badge>}
                      </div>
                      <div className="text-[11px] text-muted">
                        {option.gameVersion || option.loader
                          ? `${option.loader ?? t('market.unknownCore')} ${option.gameVersion ?? ''}`
                          : t('market.unknownServerVersion')}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-between">
              <Button variant="ghost" onClick={() => setStep(1)}>
                {t('market.back')}
              </Button>
              <Button disabled={!serverId} onClick={() => setStep(3)}>
                {t('market.next')}
              </Button>
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-3">
            {result ? (
              <>
                <p className="text-sm text-emerald-400">
                  {apiText(result.message, result.messageValues)}
                </p>
                <p className="text-[11px] text-muted">
                  {result.fileName} · {formatBytes(result.sizeBytes, t)}
                </p>
                <div className="flex justify-end">
                  <Button onClick={onClose}>{t('market.done')}</Button>
                </div>
              </>
            ) : (
              <>
                <dl className="space-y-1 text-sm">
                  <Row
                    label={t(isMod ? 'market.rowMod' : 'market.rowPlugin')}
                    value={`${plugin.title} (${sourceLabel(plugin.source)})`}
                  />
                  <Row label={t('market.rowVersion')} value={version.name} />
                  <Row label={t('market.rowServer')} value={target?.name ?? '—'} />
                  <Row label={t('market.rowTarget')} value={folder} />
                </dl>

                {/* Мод в plugins/ не загрузится, а плагин в mods/ у Forge ещё
                    и роняет запуск. Куда именно кладём — не мелочь. */}
                {plugin.projectType === 'mod' && (
                  <p className="text-xs text-muted">
                    {t('market.modLoadersOnly')}
                  </p>
                )}

                {/* Предупреждение только если сервер сейчас работает: у
                    выключенного файл просто подхватится при старте. */}
                {running ? (
                  <p className="rounded border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-300">
                    {t('market.serverRunning')}
                  </p>
                ) : (
                  <p className="text-xs text-muted">
                    {t(isMod ? 'market.serverStoppedMod' : 'market.serverStoppedPlugin')}
                  </p>
                )}

                {version.compatibility?.gameVersion === 'not-declared' && (
                  <p className="text-xs text-muted">
                    {t('market.versionNotDeclared')}
                  </p>
                )}

                {error && <ErrorText>{error}</ErrorText>}

                <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-between">
                  <Button variant="ghost" disabled={busy} onClick={() => setStep(2)}>
                    {t('market.back')}
                  </Button>
                  <Button disabled={busy} onClick={() => void install()}>
                    {busy ? t('market.installing') : t('market.install')}
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
function MatchBadge({
  compatibility,
}: {
  compatibility?: { gameVersion: MarketMatch; loader: MarketMatch };
}) {
  const t = useT();
  if (!compatibility) return null;
  const { gameVersion, loader } = compatibility;
  if (gameVersion === 'unknown' && loader === 'unknown') return null;

  if (gameVersion === 'match' && loader !== 'not-declared') {
    return <Badge variant="success">{t('market.match')}</Badge>;
  }
  return (
    <span title={t('market.notDeclaredHint')}>
      <Badge variant="outline">{t('market.notDeclaredBadge')}</Badge>
    </span>
  );
}

function sourceLabel(id: MarketSourceId): string {
  return MARKET_SOURCES.find((s) => s.id === id)?.label ?? id;
}

/**
 * Короткая запись числа загрузок.
 *
 * Своих «млн» и «тыс.» здесь больше нет: сокращения у каждого языка свои
 * (en — «1.2M», pl — «1,2 mln»), и вместе с ними меняется разделитель дробной
 * части. Всё это Intl знает про каждую локаль, а мы — нет.
 */
function formatDownloads(value: number, locale: Locale): string {
  return new Intl.NumberFormat(LOCALE_TAGS[locale], {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}
