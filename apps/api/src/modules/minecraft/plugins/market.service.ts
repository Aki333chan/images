import { BadRequestException, Injectable, Logger, NotFoundException } from '@nestjs/common';
import { request } from 'undici';
import {
  compatibilityOf,
  loadersFor,
  passesFilters,
  sortHits,
  sourcesFor,
  type MarketChannel,
  type MarketFilters,
  type MarketHitDto,
  type MarketPluginDto,
  type MarketProjectType,
  type MarketSearchResponseDto,
  type MarketSort,
  type MarketSourceId,
  type MarketSourceStatusDto,
  type MarketVersionDto,
  type ServerTargetDto,
} from '@aurum/shared';
import { env } from '../../../config/env';

/**
 * Маркет плагинов и модов: Modrinth, Hangar и SpigotMC (через SpiGet).
 *
 * ЭНДПОИНТЫ СВЕРЕНЫ С ОФИЦИАЛЬНЫМИ СПЕЦИФИКАЦИЯМИ, а не подобраны на глаз:
 *   - Modrinth: openapi.yaml из modrinth/code (apps/docs/public/openapi.yaml).
 *     База https://api.modrinth.com/v2, ключ не нужен, лимит 300 запросов в
 *     минуту на IP, но User-Agent обязателен и должен опознавать приложение —
 *     без него Modrinth вправе отказать.
 *   - Hangar: frontend/shared/types/backend/api.json из HangarMC/Hangar.
 *     База https://hangar.papermc.io/api/v1, публичные GET работают без ключа.
 *   - SpiGet: swagger.yml из SpiGetOrg/Documentation — это официальная
 *     спецификация самого SpiGet. База https://api.spiget.org/v2, ключа нет.
 *     SpiGet — НЕОФИЦИАЛЬНАЯ обёртка над SpigotMC: публичного API у самого
 *     SpigotMC не существует. Отсюда вся осторожность вокруг него, см.
 *     searchSpiget.
 *
 * ЧТО ГДЕ ЛЕЖИТ. Моды раздаёт только Modrinth, и это проверено, а не
 * предположено: в OpenAPI Hangar перечисление Platform — ровно PAPER,
 * WATERFALL, VELOCITY, а SpigotMC по своей природе площадка плагинов Bukkit.
 * Поэтому на вкладке «Моды» источник один, и панель не показывает рядом два
 * заведомо пустых.
 *
 * ПРО СОВМЕСТИМОСТЬ. Панель НИКОГДА не отсеивает выдачу по версии сервера
 * сама. Фильтры по версии и ядру попадают в запрос, только если человек
 * поставил галочку — это его выбор, а не решение панели за него. Заявленная
 * автором совместимость по-прежнему уезжает наружу бейджем.
 */

const MODRINTH_API = env.MODRINTH_BASE_URL;
const HANGAR_API = env.HANGAR_BASE_URL;
const SPIGET_API = env.SPIGET_BASE_URL;

/**
 * Modrinth требует User-Agent, по которому можно опознать приложение и
 * связаться с автором. Обезличенный (`node`, `undici`) — прямое нарушение их
 * правил и повод для блокировки.
 */
const USER_AGENT = 'Aki333chan/aurum-panel (game server admin panel)';

/** Дольше ждать нет смысла: человек стоит перед пустым списком. */
const TIMEOUT_MS = 10_000;

/**
 * SpiGet отвечает медленнее остальных и заметно чаще спотыкается — это
 * добровольный проект, а не площадка с обязательствами. Ждём его меньше:
 * лучше быстро показать выдачу двух источников и честно сказать про третий,
 * чем держать человека перед спиннером из-за нестабильного.
 */
const SPIGET_TIMEOUT_MS = 6_000;

/** У Hangar limit ограничен спецификацией; больше он просто не отдаст. */
const HANGAR_MAX_LIMIT = 25;

/**
 * Как наша сортировка ложится на параметры источников.
 *
 * Итоговый порядок всё равно считается на панели (sortHits): результаты
 * приходят из трёх мест, и каждый отсортировал только свой кусок. Но передать
 * сортировку источнику всё равно важно — иначе «по загрузкам» отобрало бы
 * двадцать случайных проектов и отсортировало уже их.
 */
const MODRINTH_INDEX: Record<MarketSort, string> = {
  relevance: 'relevance',
  downloads: 'downloads',
  updated: 'updated',
  // Алфавитного порядка у Modrinth нет вовсе (index — закрытый enum), поэтому
  // берём релевантные и сортируем по алфавиту уже у себя.
  name: 'relevance',
};

/** sort у Hangar — закрытый enum: views, downloads, newest, stars, updated, recent_*, slug. */
const HANGAR_SORT: Record<MarketSort, string> = {
  relevance: 'downloads',
  downloads: 'downloads',
  updated: 'updated',
  name: 'slug',
};

/** У SpiGet sort — имя поля с префиксом направления. */
const SPIGET_SORT: Record<MarketSort, string> = {
  relevance: '',
  downloads: '-downloads',
  updated: '-updateDate',
  name: '+name',
};

/** Справочник версий Minecraft меняется несколько раз в год — суток хватает. */
const GAME_VERSIONS_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * Сколько версий показывать в фильтре. Modrinth отдаёт их от новых к старым,
 * и полсотни релизов уходят вглубь до 1.7 — дальше уже археология.
 */
const MAX_GAME_VERSIONS = 50;

/** Ядра Hangar называет платформами и заглавными буквами. */
const HANGAR_PLATFORMS = new Set(['paper', 'waterfall', 'velocity']);

interface ModrinthHit {
  project_id: string;
  slug: string;
  title: string;
  description: string;
  author?: string;
  downloads: number;
  icon_url?: string | null;
  categories?: string[];
  versions?: string[];
  date_modified?: string;
}

interface ModrinthProject {
  id: string;
  slug: string;
  title: string;
  description: string;
  body?: string;
  icon_url?: string | null;
  downloads: number;
  followers?: number;
  loaders?: string[];
  game_versions?: string[];
  categories?: string[];
  project_type?: string;
  source_url?: string | null;
  issues_url?: string | null;
  wiki_url?: string | null;
  discord_url?: string | null;
}

interface ModrinthVersion {
  id: string;
  name: string;
  version_number: string;
  changelog?: string | null;
  game_versions?: string[];
  version_type?: string;
  loaders?: string[];
  date_published: string;
  downloads?: number;
  files?: {
    url: string;
    filename: string;
    primary?: boolean;
    size?: number;
    hashes?: { sha1?: string; sha512?: string };
  }[];
}

interface HangarProject {
  name: string;
  namespace: { owner: string; slug: string };
  description?: string;
  avatarUrl?: string | null;
  category?: string;
  lastUpdated?: string;
  createdAt?: string;
  stats?: { downloads?: number; views?: number; stars?: number; watchers?: number };
  supportedPlatforms?: Record<string, string[]>;
  settings?: { links?: unknown; source?: string; issues?: string; wiki?: string; support?: string };
}

interface HangarVersion {
  id: number;
  name: string;
  createdAt: string;
  description?: string | null;
  channel?: { name?: string };
  visibility?: string;
  stats?: { totalDownloads?: number };
  platformDependencies?: Record<string, string[]>;
  downloads?: Record<
    string,
    {
      downloadUrl?: string | null;
      externalUrl?: string | null;
      fileInfo?: { name?: string; sizeBytes?: number; sha256Hash?: string } | null;
    }
  >;
}

/** Ресурс SpigotMC в том виде, в каком его отдаёт SpiGet. */
interface SpigetResource {
  id: number;
  name?: string;
  tag?: string;
  downloads?: number;
  likes?: number;
  /** Секунды unix, НЕ ISO — у SpiGet все даты такие. */
  updateDate?: number;
  releaseDate?: number;
  testedVersions?: string[];
  external?: boolean;
  premium?: boolean;
  price?: number;
  currency?: string;
  icon?: { url?: string; data?: string };
  author?: { id?: number };
  version?: { id?: number; uuid?: string };
  file?: { type?: string; size?: number; sizeUnit?: string; url?: string; externalUrl?: string };
  sourceCodeLink?: string;
  donationLink?: string;
}

interface SpigetVersion {
  id: number;
  uuid?: string;
  name?: string;
  releaseDate?: number;
  downloads?: number;
}

/** Файл выбранной версии — то, что нужно установщику. */
export interface MarketVersionFile {
  url: string;
  fileName: string;
  sizeBytes: number | null;
  /** Хэш от источника, чтобы сверить скачанное. Алгоритм зависит от источника. */
  hash: { algo: 'sha512' | 'sha256' | 'sha1'; value: string } | null;
  /** Внешняя ссылка вместо прямой — Hangar и SpigotMC так отдают часть релизов. */
  external: boolean;
  /** Куда класть файл на сервере: plugins/ или mods/. */
  projectType: MarketProjectType;
}

/** Что именно ищем — приходит из строки запроса контроллера. */
export interface MarketSearchOptions {
  type: MarketProjectType;
  sort: MarketSort;
  filters: MarketFilters;
}

@Injectable()
export class MarketService {
  private readonly logger = new Logger(MarketService.name);

  private gameVersionsCache: { at: number; value: string[] } | null = null;

  private async fetchJson<T>(url: string, timeoutMs = TIMEOUT_MS): Promise<T> {
    return (await this.fetchJsonWithHeaders<T>(url, timeoutMs)).body;
  }

  private async fetchJsonWithHeaders<T>(
    url: string,
    timeoutMs = TIMEOUT_MS,
  ): Promise<{ body: T; headers: Record<string, string> }> {
    const res = await request(url, {
      method: 'GET',
      headers: { 'user-agent': USER_AGENT, accept: 'application/json' },
      headersTimeout: timeoutMs,
      bodyTimeout: timeoutMs,
      maxRedirections: 3,
    });
    const text = await res.body.text();
    if (res.statusCode >= 400) {
      throw new Error(`${res.statusCode}: ${text.slice(0, 200)}`);
    }
    const headers: Record<string, string> = {};
    for (const [key, value] of Object.entries(res.headers)) {
      headers[key.toLowerCase()] = Array.isArray(value) ? (value[0] ?? '') : String(value ?? '');
    }
    return { body: JSON.parse(text) as T, headers };
  }

  /**
   * Поиск по всем источникам, которые вообще знают про этот тип проекта.
   *
   * Источники опрашиваются параллельно и НЕЗАВИСИМО: если один лежит, выдача
   * остальных всё равно доезжает, а про упавший честно сказано в `sources`.
   * Именно поэтому нестабильный SpiGet можно было добавить третьим, ничем не
   * рискуя: его падение стоит ровно одной строки «источник не ответил».
   */
  async search(
    query: string,
    limit: number,
    offset: number,
    options: MarketSearchOptions,
  ): Promise<MarketSearchResponseDto> {
    const { type, sort, filters } = options;

    // Спрашиваем только тех, у кого есть что показать: галочка источника
    // сужает список, но выйти за пределы «кто вообще отдаёт такой тип» она не
    // может — иначе на вкладке модов появился бы вечно пустой Hangar.
    const wanted = new Set(filters.sources);
    const targets = sourcesFor(type).filter((s) => wanted.size === 0 || wanted.has(s));

    const results = await Promise.all(
      targets.map((source) =>
        this.searchSource(source, query, limit, offset, options).catch((e: Error) => e),
      ),
    );

    const collected: MarketHitDto[] = [];
    const sources: MarketSourceStatusDto[] = [];

    targets.forEach((source, index) => {
      const result = results[index];
      if (!result || result instanceof Error) {
        const message = result instanceof Error ? result.message : 'пустой ответ';
        // Наружу текст ошибки источника не уезжает: в нём бывает и адрес, и
        // кусок чужого ответа. Человеку важно одно — этот источник сейчас
        // молчит, остальные работают.
        this.logger.warn(`Источник ${source} не ответил: ${message}`);
        sources.push({ source, ok: false, error: 'источник не ответил', total: 0 });
        return;
      }
      collected.push(...result.hits);
      sources.push({ source, ok: true, total: result.total });
    });

    // Фильтры применяются ещё раз уже на панели. Часть источников умеет
    // фильтровать у себя (Modrinth — facets, Hangar — platform/version), часть
    // не умеет вовсе (SpiGet), и без общего прохода выдача была бы разной по
    // строгости в зависимости от того, кто ответил.
    const hits = sortHits(
      collected.filter((hit) => passesFilters(hit, filters)),
      sort,
    );

    return { hits, sources, offset, limit };
  }

  private searchSource(
    source: MarketSourceId,
    query: string,
    limit: number,
    offset: number,
    options: MarketSearchOptions,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    switch (source) {
      case 'modrinth':
        return this.searchModrinth(query, limit, offset, options);
      case 'hangar':
        return this.searchHangar(query, limit, offset, options);
      case 'spiget':
        return this.searchSpiget(query, limit, offset, options);
    }
  }

  // ------------------------------------------------------------- Modrinth

  /**
   * Facets Modrinth: массив массивов, внутри «или», между — «и».
   *
   * ПОЧЕМУ ДЛЯ МОДОВ ДВА УСЛОВИЯ, А НЕ ОДНО. У Modrinth плагин Bukkit — это
   * тоже project_type «mod», а «plugin» он получает по загрузчику. Поэтому
   * один facet project_type:mod вернул бы вперемешку и моды, и плагины.
   * Разделяет их именно загрузчик, а загрузчики в поиске Modrinth лежат в
   * categories — так написано в его же спецификации.
   */
  private modrinthFacets(type: MarketProjectType, filters: MarketFilters): string[][] {
    const facets: string[][] = [];
    const pool = loadersFor(type).map((l) => l.toLowerCase());
    const chosen = filters.loaders.map((l) => l.toLowerCase()).filter((l) => pool.includes(l));

    if (type === 'mod') {
      facets.push(['project_type:mod']);
      facets.push((chosen.length > 0 ? chosen : pool).map((l) => `categories:${l}`));
    } else {
      facets.push(['project_type:plugin']);
      if (chosen.length > 0) facets.push(chosen.map((l) => `categories:${l}`));
    }

    // Версия игры попадает в запрос ТОЛЬКО как отмеченная человеком галочка.
    // Сама панель по версии сервера ничего не прячет — см. шапку файла.
    if (filters.gameVersions.length > 0) {
      facets.push(filters.gameVersions.map((v) => `versions:${v}`));
    }

    return facets;
  }

  private async searchModrinth(
    query: string,
    limit: number,
    offset: number,
    { type, sort, filters }: MarketSearchOptions,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    const facets = encodeURIComponent(JSON.stringify(this.modrinthFacets(type, filters)));
    // Релевантность без строки поиска — пустой звук: сортировать по совпадению
    // не с чем. В этом случае показываем самое популярное.
    const index = sort === 'relevance' && !query ? 'downloads' : MODRINTH_INDEX[sort];
    const url =
      `${MODRINTH_API}/search?facets=${facets}&limit=${limit}&offset=${offset}&index=${index}` +
      (query ? `&query=${encodeURIComponent(query)}` : '');

    const data = await this.fetchJson<{ hits: ModrinthHit[]; total_hits: number }>(url);
    const raw = data.hits ?? [];
    return {
      total: data.total_hits ?? raw.length,
      hits: raw.map((h) => {
        const loaders = modrinthLoaders(h.categories ?? [], type);
        return {
          source: 'modrinth' as const,
          id: h.project_id,
          slug: h.slug,
          title: h.title,
          description: h.description,
          author: h.author ?? null,
          downloads: h.downloads ?? 0,
          iconUrl: h.icon_url ?? null,
          // Загрузчики Modrinth держит вперемешку с категориями; в categories
          // оставляем только то, что действительно категория.
          categories: (h.categories ?? []).filter((c) => !loaders.includes(c.toLowerCase())),
          gameVersions: h.versions ?? [],
          loaders,
          updatedAt: h.date_modified ?? null,
          pageUrl: `https://modrinth.com/${type}/${h.slug}`,
          projectType: type,
        };
      }),
    };
  }

  // --------------------------------------------------------------- Hangar

  private async searchHangar(
    query: string,
    limit: number,
    offset: number,
    { sort, filters }: MarketSearchOptions,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    const params = new URLSearchParams({
      limit: String(Math.min(limit, HANGAR_MAX_LIMIT)),
      offset: String(offset),
    });
    if (query) params.set('q', query);
    // Своя релевантность у Hangar есть, но выражается она ОТСУТСТВИЕМ sort:
    // prioritizeExactMatch включён по умолчанию.
    if (sort !== 'relevance' || !query) params.set('sort', HANGAR_SORT[sort]);

    // platform и version у Hangar принимают ровно одно значение. Несколько
    // отмеченных галочек он бы не понял, поэтому источнику отправляем сузить
    // только однозначный выбор; остальное досчитает passesFilters у нас.
    const platforms = filters.loaders
      .map((l) => l.toLowerCase())
      .filter((l) => HANGAR_PLATFORMS.has(l));
    if (platforms.length === 1) params.set('platform', platforms[0]!.toUpperCase());
    if (filters.gameVersions.length === 1) params.set('version', filters.gameVersions[0]!);

    const data = await this.fetchJson<{
      pagination?: { count?: number };
      result: HangarProject[];
    }>(`${HANGAR_API}/projects?${params.toString()}`);

    return {
      total: data.pagination?.count ?? data.result?.length ?? 0,
      hits: (data.result ?? []).map((p) => this.hangarHit(p)),
    };
  }

  private hangarHit(p: HangarProject): MarketHitDto {
    // supportedPlatforms — это карта «платформа -> список версий игры».
    const platforms = p.supportedPlatforms ?? {};
    const gameVersions = [...new Set(Object.values(platforms).flat())];
    return {
      source: 'hangar',
      // У Hangar нет короткого id проекта в публичной выдаче — адресуемся
      // парой owner/slug, как это делает и сам сайт.
      id: `${p.namespace.owner}/${p.namespace.slug}`,
      slug: p.namespace.slug,
      title: p.name,
      description: p.description ?? '',
      author: p.namespace.owner,
      downloads: p.stats?.downloads ?? 0,
      iconUrl: p.avatarUrl ?? null,
      categories: p.category ? [p.category] : [],
      gameVersions,
      loaders: Object.keys(platforms).map((k) => k.toLowerCase()),
      updatedAt: p.lastUpdated ?? p.createdAt ?? null,
      pageUrl: `https://hangar.papermc.io/${p.namespace.owner}/${p.namespace.slug}`,
      projectType: 'plugin',
    };
  }

  // --------------------------------------------------------------- SpiGet

  /**
   * SpigotMC через SpiGet.
   *
   * ЭТОТ ИСТОЧНИК ОСОБЕННЫЙ, и обращаться с ним надо соответственно. У самого
   * SpigotMC публичного API нет вовсе; SpiGet — сторонняя обёртка, которую
   * поддерживает один человек, и она периодически отдаёт 5xx, таймаутит или
   * возвращает частичные данные. Поэтому:
   *   - таймаут ему выставлен короче остальных;
   *   - любая его ошибка ловится в search() и превращается в строку
   *     «источник не ответил», не задевая Modrinth и Hangar;
   *   - разбор ответа не верит ни одному полю: id может прийти строкой,
   *     testedVersions — отсутствовать, вместо массива прилететь объект.
   *
   * ЧЕГО ЗДЕСЬ НЕТ. Ни ядер, ни загрузчиков: SpigotMC их не хранит. Поэтому
   * loaders остаётся пустым, и фильтр по ядру такие результаты не отсеивает —
   * иначе одна галочка «paper» спрятала бы весь источник целиком.
   */
  private async searchSpiget(
    query: string,
    limit: number,
    offset: number,
    { sort }: MarketSearchOptions,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    const size = Math.max(1, Math.min(limit, 50));
    // Страницы у SpiGet считаются с единицы, а наружу панель отдаёт offset.
    const page = Math.floor(offset / size) + 1;

    const params = new URLSearchParams({
      size: String(size),
      page: String(page),
      // Просим ровно то, что показываем. Без этого SpiGet присылает и
      // base64-описание ресурса целиком — сотни килобайт на каждый.
      fields: 'id,name,tag,downloads,testedVersions,icon,external,premium,updateDate,version,file',
    });
    const sortField = SPIGET_SORT[sort];
    if (sortField) params.set('sort', sortField);

    // Без строки поиска берём список бесплатных: платные всё равно нельзя
    // установить, и показывать их первым экраном значит предлагать то, чего
    // панель сделать не сможет.
    const url = query
      ? `${SPIGET_API}/search/resources/${encodeURIComponent(query)}?field=name&${params}`
      : `${SPIGET_API}/resources/free?${params}`;

    const { body, headers } = await this.fetchJsonWithHeaders<unknown>(url, SPIGET_TIMEOUT_MS);
    if (!Array.isArray(body)) {
      throw new Error('SpiGet вернул не список ресурсов');
    }

    const hits = body
      .map((raw) => spigetHit(raw as SpigetResource))
      .filter((hit): hit is MarketHitDto => hit !== null);

    // Точного числа найденного SpiGet не отдаёт — только количество страниц.
    // Оценка честнее нуля, но это именно оценка.
    const pages = Number(headers['x-page-count']);
    const total = Number.isFinite(pages) && pages > 0 ? pages * size : hits.length;

    return { hits, total };
  }

  // ------------------------------------------------------- Справочники

  /**
   * Версии игры для галочек фильтра — из справочника Modrinth.
   *
   * ПОЧЕМУ НЕ СПИСКОМ В КОДЕ. Такой список устаревает к следующему релизу
   * Minecraft, и человек, у которого сервер уже на новой версии, не найдёт её
   * в фильтре. Справочник у Modrinth ровно для этого и существует.
   *
   * Снапшоты отброшены намеренно: их сотни, и в списке галочек они
   * похоронили бы релизы, ради которых список и открывают.
   *
   * Кэш на сутки: справочник меняется несколько раз в год, а фильтр
   * открывают часто. Если Modrinth молчит — отдаём последнее, что знали, а
   * если не знали ничего, пустой список. Пустой список означает «фильтра по
   * версии сейчас нет», а не «ничего не найдено»: поиск при этом работает.
   */
  async listGameVersions(): Promise<string[]> {
    const now = Date.now();
    if (this.gameVersionsCache && now - this.gameVersionsCache.at < GAME_VERSIONS_TTL_MS) {
      return this.gameVersionsCache.value;
    }

    try {
      const tags = await this.fetchJson<{ version: string; version_type?: string }[]>(
        `${MODRINTH_API}/tag/game_version`,
      );
      const value = (Array.isArray(tags) ? tags : [])
        .filter((t) => t?.version_type === 'release' && typeof t.version === 'string')
        .map((t) => t.version)
        .slice(0, MAX_GAME_VERSIONS);
      this.gameVersionsCache = { at: now, value };
      return value;
    } catch (e) {
      this.logger.warn(`Справочник версий Modrinth не ответил: ${(e as Error).message}`);
      return this.gameVersionsCache?.value ?? [];
    }
  }

  // ------------------------------------------------------------- Карточка

  /** Карточка проекта: описание, все заявленные ядра и все версии игры. */
  async getPlugin(source: MarketSourceId, id: string): Promise<MarketPluginDto> {
    switch (source) {
      case 'modrinth':
        return this.getModrinthPlugin(id);
      case 'hangar':
        return this.getHangarPlugin(id);
      case 'spiget':
        return this.getSpigetPlugin(id);
    }
  }

  private async getModrinthPlugin(id: string): Promise<MarketPluginDto> {
    const p = await this.fetchJson<ModrinthProject>(
      `${MODRINTH_API}/project/${encodeURIComponent(id)}`,
    ).catch(() => null);
    if (!p) throw new NotFoundException('Проект не найден на Modrinth');

    const loaders = (p.loaders ?? []).map((l) => l.toLowerCase());
    const projectType = projectTypeOf(loaders);

    return {
      source: 'modrinth',
      projectType,
      id: p.id,
      slug: p.slug,
      title: p.title,
      description: p.description,
      body: p.body ?? null,
      iconUrl: p.icon_url ?? null,
      downloads: p.downloads ?? 0,
      followers: p.followers ?? null,
      loaders,
      gameVersions: p.game_versions ?? [],
      categories: p.categories ?? [],
      pageUrl: `https://modrinth.com/${projectType}/${p.slug}`,
      sourceUrl: p.source_url ?? null,
      issuesUrl: p.issues_url ?? null,
      wikiUrl: p.wiki_url ?? null,
      discordUrl: p.discord_url ?? null,
    };
  }

  private async getHangarPlugin(id: string): Promise<MarketPluginDto> {
    const p = await this.fetchJson<HangarProject>(`${HANGAR_API}/projects/${hangarPath(id)}`).catch(
      () => null,
    );
    if (!p) throw new NotFoundException('Плагин не найден на Hangar');

    const platforms = p.supportedPlatforms ?? {};
    const settings = (p.settings ?? {}) as Record<string, string | undefined>;
    return {
      source: 'hangar',
      projectType: 'plugin',
      id: `${p.namespace.owner}/${p.namespace.slug}`,
      slug: p.namespace.slug,
      title: p.name,
      description: p.description ?? '',
      // Полное описание Hangar отдаёт отдельным запросом страницы; на карточке
      // хватает короткого, а за подробностями есть ссылка на источник.
      body: null,
      iconUrl: p.avatarUrl ?? null,
      downloads: p.stats?.downloads ?? 0,
      followers: null,
      // Ядра у Hangar — это платформы: PAPER, WATERFALL, VELOCITY.
      loaders: Object.keys(platforms).map((k) => k.toLowerCase()),
      gameVersions: [...new Set(Object.values(platforms).flat())],
      categories: p.category ? [p.category] : [],
      pageUrl: `https://hangar.papermc.io/${p.namespace.owner}/${p.namespace.slug}`,
      sourceUrl: settings.source ?? null,
      issuesUrl: settings.issues ?? null,
      wikiUrl: settings.wiki ?? null,
      discordUrl: null,
    };
  }

  private async getSpigetPlugin(id: string): Promise<MarketPluginDto> {
    const resourceId = spigetId(id);
    const p = await this.fetchJson<SpigetResource>(
      `${SPIGET_API}/resources/${resourceId}`,
      SPIGET_TIMEOUT_MS,
    ).catch(() => null);
    if (!p) throw new NotFoundException('Ресурс не найден на SpigotMC');

    const hit = spigetHit(p);
    return {
      source: 'spiget',
      projectType: 'plugin',
      id: String(resourceId),
      slug: String(resourceId),
      title: hit?.title ?? `Ресурс ${resourceId}`,
      description: hit?.description ?? '',
      // Описание SpigotMC — это HTML в base64, а не markdown. Показать его как
      // markdown значит либо вывалить человеку разметку, либо вставить чужой
      // HTML в страницу панели. Ни то, ни другое не нужно: есть ссылка.
      body: null,
      iconUrl: hit?.iconUrl ?? null,
      downloads: typeof p.downloads === 'number' ? p.downloads : 0,
      // Подписчиков SpigotMC не считает; ближайшее по смыслу — лайки.
      followers: typeof p.likes === 'number' ? p.likes : null,
      // Ядер SpigotMC не хранит вовсе — честно пусто.
      loaders: [],
      gameVersions: Array.isArray(p.testedVersions) ? p.testedVersions.map(String) : [],
      categories: [],
      pageUrl: `https://www.spigotmc.org/resources/${resourceId}/`,
      sourceUrl: typeof p.sourceCodeLink === 'string' ? p.sourceCodeLink : null,
      issuesUrl: null,
      wikiUrl: null,
      discordUrl: null,
      premium: p.premium === true,
      externalFile: p.external === true,
    };
  }

  // --------------------------------------------------------------- Версии

  /**
   * ВСЕ опубликованные версии проекта — как на вкладке Versions источника.
   *
   * Ничего не отсеивается: ни по каналу (beta/alpha показываются наравне с
   * release), ни по игровой версии. Совпадение с сервером считается и уезжает
   * бейджем в каждой строке.
   */
  async getVersions(
    source: MarketSourceId,
    id: string,
    target: ServerTargetDto | null,
  ): Promise<MarketVersionDto[]> {
    const versions =
      source === 'modrinth'
        ? await this.getModrinthVersions(id)
        : source === 'hangar'
          ? await this.getHangarVersions(id)
          : await this.getSpigetVersions(id);
    return versions.map((v) => ({ ...v, compatibility: compatibilityOf(v, target) }));
  }

  private async getModrinthVersions(id: string): Promise<MarketVersionDto[]> {
    const list = await this.fetchJson<ModrinthVersion[]>(
      `${MODRINTH_API}/project/${encodeURIComponent(id)}/version`,
    );
    return list.map((v) => {
      const file = pickPrimary(v.files ?? []);
      return {
        id: v.id,
        name: v.name,
        versionNumber: v.version_number,
        channel: toChannel(v.version_type),
        loaders: v.loaders ?? [],
        gameVersions: v.game_versions ?? [],
        publishedAt: v.date_published,
        downloads: v.downloads ?? null,
        changelog: v.changelog?.trim() ? v.changelog : null,
        fileName: file?.filename ?? null,
        fileSizeBytes: file?.size ?? null,
      };
    });
  }

  private async getHangarVersions(id: string): Promise<MarketVersionDto[]> {
    const data = await this.fetchJson<{ result: HangarVersion[] }>(
      `${HANGAR_API}/projects/${hangarPath(id)}/versions?limit=25&offset=0`,
    );
    return (data.result ?? []).map((v) => {
      const platforms = v.platformDependencies ?? {};
      const download = Object.values(v.downloads ?? {})[0];
      return {
        id: String(v.id),
        name: v.name,
        versionNumber: v.name,
        channel: toChannel(v.channel?.name),
        loaders: Object.keys(platforms).map((k) => k.toLowerCase()),
        gameVersions: [...new Set(Object.values(platforms).flat())],
        publishedAt: v.createdAt,
        downloads: v.stats?.totalDownloads ?? null,
        changelog: v.description?.trim() ? v.description : null,
        fileName: download?.fileInfo?.name ?? null,
        fileSizeBytes: download?.fileInfo?.sizeBytes ?? null,
      };
    });
  }

  /**
   * Версии ресурса SpigotMC.
   *
   * ВАЖНОЕ ОТЛИЧИЕ ОТ ОСТАЛЬНЫХ ИСТОЧНИКОВ: SpigotMC хранит поддерживаемые
   * версии игры на ресурсе целиком, а не на конкретном релизе. Поэтому список
   * версий игры берётся из ресурса и проставляется каждой версии — иначе бейдж
   * совместимости у SpigotMC всегда показывал бы «не заявлено», хотя автор
   * как раз заявил.
   */
  private async getSpigetVersions(id: string): Promise<MarketVersionDto[]> {
    const resourceId = spigetId(id);
    const [list, resource] = await Promise.all([
      this.fetchJson<SpigetVersion[]>(
        `${SPIGET_API}/resources/${resourceId}/versions?size=25&sort=-releaseDate`,
        SPIGET_TIMEOUT_MS,
      ),
      this.fetchJson<SpigetResource>(
        `${SPIGET_API}/resources/${resourceId}?fields=id,testedVersions`,
        SPIGET_TIMEOUT_MS,
      ).catch(() => null),
    ]);

    if (!Array.isArray(list)) throw new NotFoundException('SpiGet вернул не список версий');
    const gameVersions = Array.isArray(resource?.testedVersions)
      ? resource.testedVersions.map(String)
      : [];

    return list.map((v) => ({
      id: String(v.id),
      name: cleanText(v.name) || `#${v.id}`,
      versionNumber: cleanText(v.name) || String(v.id),
      // Каналов у SpigotMC нет: каждая загрузка это релиз.
      channel: 'release' as const,
      loaders: [],
      gameVersions,
      publishedAt: unixToIso(v.releaseDate) ?? new Date(0).toISOString(),
      downloads: typeof v.downloads === 'number' ? v.downloads : null,
      changelog: null,
      fileName: null,
      fileSizeBytes: null,
    }));
  }

  // ----------------------------------------------------------------- Файл

  /**
   * Файл конкретной версии — то, что скачивает установщик.
   *
   * Отдельным запросом, а не из списка: список может быть закэширован в
   * интерфейсе, а ставить надо ровно то, что лежит у источника сейчас.
   */
  async getVersionFile(
    source: MarketSourceId,
    id: string,
    versionId: string,
  ): Promise<MarketVersionFile> {
    switch (source) {
      case 'modrinth':
        return this.getModrinthFile(versionId);
      case 'hangar':
        return this.getHangarFile(id, versionId);
      case 'spiget':
        return this.getSpigetFile(id, versionId);
    }
  }

  private async getModrinthFile(versionId: string): Promise<MarketVersionFile> {
    const v = await this.fetchJson<ModrinthVersion>(
      `${MODRINTH_API}/version/${encodeURIComponent(versionId)}`,
    ).catch(() => null);
    const file = v && pickPrimary(v.files ?? []);
    if (!v || !file) throw new NotFoundException('У этой версии нет файла для скачивания');
    const sha512 = file.hashes?.sha512;
    const sha1 = file.hashes?.sha1;

    return {
      url: file.url,
      fileName: file.filename,
      sizeBytes: file.size ?? null,
      hash: sha512
        ? { algo: 'sha512', value: sha512 }
        : sha1
          ? { algo: 'sha1', value: sha1 }
          : null,
      external: false,
      // Тип определяется загрузчиками ИМЕННО ЭТОЙ версии, а не проекта: у
      // одного проекта бывают релизы и под Fabric, и под Paper, и класть их
      // надо в разные папки.
      projectType: projectTypeOf((v.loaders ?? []).map((l) => l.toLowerCase())),
    };
  }

  private async getHangarFile(id: string, versionId: string): Promise<MarketVersionFile> {
    const v = await this.fetchJson<HangarVersion>(
      `${HANGAR_API}/projects/${hangarPath(id)}/versions/${encodeURIComponent(versionId)}`,
    ).catch(() => null);
    if (!v) throw new NotFoundException('Версия не найдена на Hangar');

    // Платформ у версии может быть несколько; берём ту, у которой есть файл.
    const entry = Object.entries(v.downloads ?? {}).find(
      ([, d]) => d?.downloadUrl || d?.externalUrl,
    );
    if (!entry) throw new NotFoundException('У этой версии нет файла для скачивания');
    const [platform, download] = entry;

    const external = !download.downloadUrl && !!download.externalUrl;
    if (external) assertSafeDownloadUrl(download.externalUrl!);

    return {
      // Прямая ссылка Hangar ведёт на его же CDN; если автор выложил релиз на
      // сторонний сайт, downloadUrl пустой, и качать надо оттуда.
      url:
        download.downloadUrl ??
        download.externalUrl ??
        `${HANGAR_API}/projects/${hangarPath(id)}/versions/${encodeURIComponent(
          v.name,
        )}/${platform}/download`,
      fileName: download.fileInfo?.name ?? `${v.name}.jar`,
      sizeBytes: download.fileInfo?.sizeBytes ?? null,
      hash: download.fileInfo?.sha256Hash
        ? { algo: 'sha256', value: download.fileInfo.sha256Hash }
        : null,
      external,
      projectType: 'plugin',
    };
  }

  /**
   * Файл версии со SpigotMC.
   *
   * ТРИ ОСОБЕННОСТИ, каждая из которых способна испортить установку молча:
   *
   * 1. Платные ресурсы. За premium стоит оплата на самом SpigotMC, и никакой
   *    ссылки на файл у панели нет. Отказываем сразу и внятно, а не качаем
   *    страницу с предложением купить и не кладём её в plugins/ как jar.
   *
   * 2. Внешние ресурсы. Автор вправе выложить jar куда угодно. Такой адрес
   *    произвольный, а панель идёт по нему из внутренней сети — поэтому он
   *    проверяется отдельно (assertSafeDownloadUrl).
   *
   * 3. Хэшей SpiGet не отдаёт ВООБЩЕ — ни для какого ресурса. Сверить
   *    скачанное по контрольной сумме нельзя, и на это опирается проверка
   *    сигнатуры архива при установке (см. PluginFilesService).
   */
  private async getSpigetFile(id: string, versionId: string): Promise<MarketVersionFile> {
    const resourceId = spigetId(id);
    const version = String(versionId).replace(/[^0-9a-zA-Z-]/g, '');
    if (!version) throw new NotFoundException('Не указана версия ресурса');

    const [resource, versionInfo] = await Promise.all([
      this.fetchJson<SpigetResource>(
        `${SPIGET_API}/resources/${resourceId}`,
        SPIGET_TIMEOUT_MS,
      ).catch(() => null),
      this.fetchJson<SpigetVersion>(
        `${SPIGET_API}/resources/${resourceId}/versions/${version}`,
        SPIGET_TIMEOUT_MS,
      ).catch(() => null),
    ]);
    if (!resource) throw new NotFoundException('Ресурс не найден на SpigotMC');

    if (resource.premium === true) {
      throw new BadRequestException(
        'Это платный ресурс SpigotMC — скачать его может только покупатель на сайте источника',
      );
    }

    const fileType = (resource.file?.type ?? '').toLowerCase();
    if (fileType && fileType !== '.jar' && fileType !== 'external') {
      throw new BadRequestException(
        `Файл ресурса имеет тип ${fileType}, а не .jar — установить его панель не может`,
      );
    }

    const fileName = `${slugForFile(resource.name ?? `resource-${resourceId}`)}-${slugForFile(
      cleanText(versionInfo?.name) || version,
    )}.jar`;

    if (resource.external === true) {
      const externalUrl = resource.file?.externalUrl;
      if (!externalUrl) {
        throw new BadRequestException(
          'Ресурс размещён вне SpigotMC, но ссылка на файл не указана — установите его вручную',
        );
      }
      assertSafeDownloadUrl(externalUrl);
      return {
        url: externalUrl,
        fileName,
        sizeBytes: null,
        hash: null,
        external: true,
        projectType: 'plugin',
      };
    }

    // Последнюю версию SpiGet отдаёт со своего CDN — это настоящий файл.
    // Для остальных есть только proxy-маршрут: обычный /download у версии
    // ведёт на страницу SpigotMC, откуда приедет HTML, а не jar.
    const latestId = resource.version?.id;
    const url =
      latestId !== undefined && String(latestId) === version
        ? `${SPIGET_API}/resources/${resourceId}/download`
        : `${SPIGET_API}/resources/${resourceId}/versions/${version}/download/proxy`;

    return { url, fileName, sizeBytes: null, hash: null, external: false, projectType: 'plugin' };
  }

  /**
   * Иконка проекта, забранная панелью и отданная браузеру со своего адреса.
   *
   * Прямая ссылка на CDN источника не работает и работать не должна: панель
   * стоит за nginx с Content-Security-Policy, где img-src ограничен своим
   * доменом и crafatar.com. Добавлять туда по домену на каждый источник —
   * значит править конфиг живого сервера при появлении каждого нового
   * маркета, а заодно показывать этим CDN адрес каждого администратора,
   * который открыл список плагинов.
   *
   * Поэтому картинку забирает панель и отдаёт со своего адреса: CSP не
   * трогается, чужие CDN видят один только сервер.
   */
  async getIcon(rawUrl: string): Promise<ProxiedIcon> {
    if (!isAllowedIconUrl(rawUrl)) {
      throw new NotFoundException('Иконка с этого адреса не отдаётся');
    }

    const res = await request(rawUrl, {
      method: 'GET',
      headers: { 'user-agent': USER_AGENT },
      maxRedirections: 3,
      headersTimeout: 8_000,
      bodyTimeout: 15_000,
    }).catch(() => null);

    if (!res || res.statusCode >= 400) {
      throw new NotFoundException('Источник не отдал иконку');
    }

    const contentType = String(res.headers['content-type'] ?? '')
      .split(';')[0]!
      .trim()
      .toLowerCase();
    if (!ICON_TYPES.has(contentType)) {
      throw new NotFoundException('По этому адресу не картинка');
    }

    const chunks: Buffer[] = [];
    let total = 0;
    for await (const chunk of res.body) {
      const buf = Buffer.from(chunk);
      total += buf.length;
      if (total > MAX_ICON_BYTES) throw new NotFoundException('Иконка слишком большая');
      chunks.push(buf);
    }

    return { body: Buffer.concat(chunks), contentType };
  }
}

/** Modrinth называет каналы release/beta/alpha, Hangar — Release/Beta/Alpha. */
function toChannel(raw: string | undefined): MarketChannel {
  const value = (raw ?? '').toLowerCase();
  if (value.includes('alpha')) return 'alpha';
  if (value.includes('beta') || value.includes('snapshot')) return 'beta';
  return 'release';
}

function pickPrimary<T extends { primary?: boolean }>(files: T[]): T | undefined {
  return files.find((f) => f.primary) ?? files[0];
}

/**
 * Мод это или плагин — по загрузчикам.
 *
 * Именно по ним, а не по project_type: у Modrinth плагин Bukkit тоже помечен
 * как «mod», и по одному этому полю мод от плагина не отличить. Загрузчик
 * отличает: fabric/forge/neoforge/quilt не бывает у плагина.
 */
function projectTypeOf(loaders: string[]): MarketProjectType {
  const mod = new Set((loadersFor('mod') as readonly string[]).map((l) => l.toLowerCase()));
  return loaders.some((l) => mod.has(l.toLowerCase())) ? 'mod' : 'plugin';
}

/**
 * Загрузчики из categories Modrinth.
 *
 * Отдельного поля loaders в выдаче поиска нет: по спецификации Modrinth
 * «loaders are lumped in with categories in search». Отделяем их по известному
 * списку — всё, что не загрузчик, остаётся категорией.
 */
function modrinthLoaders(categories: string[], type: MarketProjectType): string[] {
  const pool = new Set((loadersFor(type) as readonly string[]).map((l) => l.toLowerCase()));
  return categories.map((c) => c.toLowerCase()).filter((c) => pool.has(c));
}

/**
 * Проект Hangar адресуется парой owner/slug. Кодируем части по отдельности:
 * encodeURIComponent целиком превратил бы разделитель в %2F, и путь перестал
 * бы находиться.
 */
function hangarPath(id: string): string {
  return id
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/');
}

/** У SpigotMC идентификатор ресурса — целое число и ничего кроме. */
function spigetId(raw: string): number {
  const value = Number.parseInt(String(raw).trim(), 10);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new NotFoundException('Неверный идентификатор ресурса SpigotMC');
  }
  return value;
}

/**
 * Один результат SpiGet -> строка выдачи.
 *
 * Возвращает null для мусора вместо исключения: один битый ресурс не должен
 * стоить человеку всей страницы результатов.
 */
function spigetHit(raw: SpigetResource | null | undefined): MarketHitDto | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = Number(raw.id);
  if (!Number.isSafeInteger(id) || id <= 0) return null;

  const title = cleanText(raw.name);
  if (!title) return null;

  return {
    source: 'spiget',
    id: String(id),
    slug: String(id),
    title,
    description: cleanText(raw.tag),
    // Имя автора у SpiGet лежит отдельным запросом: в самом ресурсе только
    // его id. В списке из двадцати строк это двадцать лишних запросов ради
    // подписи — не стоит того.
    author: null,
    downloads: typeof raw.downloads === 'number' ? raw.downloads : 0,
    iconUrl: spigetIconUrl(raw.icon?.url),
    categories: [],
    gameVersions: Array.isArray(raw.testedVersions) ? raw.testedVersions.map(String) : [],
    // SpigotMC не хранит ядро плагина — честно пусто, см. searchSpiget.
    loaders: [],
    updatedAt: unixToIso(raw.updateDate),
    pageUrl: `https://www.spigotmc.org/resources/${id}/`,
    projectType: 'plugin',
    premium: raw.premium === true,
    externalFile: raw.external === true,
  };
}

/** Адрес иконки SpiGet отдаёт относительным — от корня spigotmc.org. */
function spigetIconUrl(relative: string | undefined): string | null {
  if (!relative || typeof relative !== 'string') return null;
  if (/^https?:\/\//i.test(relative)) return relative.startsWith('https://') ? relative : null;
  return `https://www.spigotmc.org/${relative.replace(/^\/+/, '')}`;
}

/** Секунды unix -> ISO. У SpiGet все даты такие, у остальных источников — ISO. */
function unixToIso(seconds: number | undefined): string | null {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) return null;
  return new Date(seconds * 1000).toISOString();
}

/**
 * Текст с SpigotMC как есть показывать нельзя: там встречаются и HTML-сущности,
 * и управляющие символы. Разворачиваем сущности и убираем всё, что не текст.
 */
function cleanText(raw: string | undefined | null): string {
  if (typeof raw !== 'string') return '';
  return raw
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&apos;/g, "'")
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 500);
}

/** Кусок имени файла из произвольного названия ресурса. */
function slugForFile(raw: string): string {
  const cleaned = raw
    .normalize('NFKD')
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  return cleaned || 'plugin';
}

/**
 * Хосты, с которых панель соглашается забирать иконки.
 *
 * Белый список, а не «любой https» — потому что прокси картинок это чужой
 * URL, который панель запрашивает сама, изнутри сети. Без ограничения по
 * хосту такой маршрут превращается в SSRF: подставив адрес вида
 * http://10.0.0.2:8085/… или http://169.254.169.254/…, снаружи можно было бы
 * читать то, до чего дотягивается панель, но не дотягивается браузер.
 *
 * Список закрытый и сверен с тем, что реально отдают источники: Modrinth
 * держит иконки на своём CDN, Hangar — на своём, а SpiGet отдаёт
 * относительный путь от корня spigotmc.org.
 */
const ICON_HOSTS = new Set([
  'cdn.modrinth.com',
  'hangarcdn.papermc.io',
  'hangar.papermc.io',
  'www.spigotmc.org',
  'static.spigotmc.org',
  'cdn.spiget.org',
]);

/** Больше иконки не весят, а память панели не резиновая. */
const MAX_ICON_BYTES = 2 * 1024 * 1024;

/** Что панель соглашается считать картинкой. */
const ICON_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/svg+xml']);

export interface ProxiedIcon {
  body: Buffer;
  contentType: string;
}

/**
 * Разрешён ли адрес иконки. Экспортируется ради тестов: проверка на SSRF
 * ломается тихо, а цена ошибки — доступ к внутренней сети.
 */
export function isAllowedIconUrl(raw: string): boolean {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  // Только https: по http адрес можно подменить на пути, а внутри сети
  // http — это как раз то, до чего дотягиваться нельзя.
  if (url.protocol !== 'https:') return false;
  return ICON_HOSTS.has(url.hostname.toLowerCase());
}

/**
 * Адреса, куда панель идёт скачивать чужой файл.
 *
 * Белого списка тут быть не может: внешний релиз лежит там, где его положил
 * автор, — на GitHub, на своём сайте, где угодно. Поэтому запрет не по списку
 * разрешённых, а по тому, куда ходить нельзя: только https и только не во
 * внутреннюю сеть. Иначе ссылка вида https://10.0.0.2:8085/… в метаданных
 * чужого ресурса превращала бы установку плагина в чтение внутренних сервисов
 * — а по ту сторону туннеля стоит домашний сервер с игрой.
 *
 * Это не полная защита от DNS rebinding: имя может указывать на приватный
 * адрес. Но она закрывает то, что закрывается дёшево и встречается реально.
 */
export function isSafeDownloadUrl(raw: string): boolean {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  if (url.protocol !== 'https:') return false;

  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.internal')) {
    return false;
  }
  // IPv6-петля и уникальные локальные адреса (fc00::/7).
  if (host === '::1' || /^f[cd][0-9a-f]{2}:/i.test(host)) return false;

  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (v4) {
    const a = Number(v4[1]);
    const b = Number(v4[2]);
    if (a === 10 || a === 127 || a === 0) return false;
    if (a === 172 && b >= 16 && b <= 31) return false;
    if (a === 192 && b === 168) return false;
    // 169.254.0.0/16 — link-local и метаданные облаков.
    if (a === 169 && b === 254) return false;
    // 100.64.0.0/10 — CGNAT, туда же попадают адреса части туннелей.
    if (a === 100 && b >= 64 && b <= 127) return false;
  }

  return true;
}

export function assertSafeDownloadUrl(raw: string): void {
  if (!isSafeDownloadUrl(raw)) {
    throw new BadRequestException(
      'Файл размещён по адресу, по которому панель не пойдёт: разрешены только внешние https-ссылки',
    );
  }
}
