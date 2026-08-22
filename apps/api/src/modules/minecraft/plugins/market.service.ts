import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import { request } from 'undici';
import {
  compatibilityOf,
  type MarketChannel,
  type MarketHitDto,
  type MarketPluginDto,
  type MarketSearchResponseDto,
  type MarketSourceId,
  type MarketSourceStatusDto,
  type MarketVersionDto,
  type ServerTargetDto,
} from '@aurum/shared';
import { env } from '../../../config/env';

/**
 * Маркет плагинов: Modrinth и Hangar.
 *
 * ЭНДПОИНТЫ СВЕРЕНЫ С ОФИЦИАЛЬНЫМИ СПЕЦИФИКАЦИЯМИ, а не подобраны на глаз:
 *   - Modrinth: openapi.yaml из modrinth/code (apps/docs/public/openapi.yaml).
 *     База https://api.modrinth.com/v2, ключ не нужен, лимит 300 запросов в
 *     минуту на IP, но User-Agent обязателен и должен опознавать приложение —
 *     без него Modrinth вправе отказать.
 *   - Hangar: frontend/shared/types/backend/api.json из HangarMC/Hangar.
 *     База https://hangar.papermc.io/api/v1, публичные GET работают без ключа.
 *
 * ПРО СОВМЕСТИМОСТЬ. Ни один запрос отсюда не фильтрует выдачу по версии
 * сервера. В Modrinth есть facet `versions:` — он намеренно НЕ используется:
 * плагин, заявленный для 1.20, обычно работает и на 1.21, а спрятав его,
 * панель приняла бы решение за человека. Совпадение считается отдельно и
 * уезжает наружу как бейдж.
 */

const MODRINTH_API = env.MODRINTH_BASE_URL;
const HANGAR_API = env.HANGAR_BASE_URL;

/**
 * Modrinth требует User-Agent, по которому можно опознать приложение и
 * связаться с автором. Обезличенный (`node`, `undici`) — прямое нарушение их
 * правил и повод для блокировки.
 */
const USER_AGENT = 'Aki333chan/aurum-panel (game server admin panel)';

/** Дольше ждать нет смысла: человек стоит перед пустым списком. */
const TIMEOUT_MS = 10_000;

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

/** Файл выбранной версии — то, что нужно установщику. */
export interface MarketVersionFile {
  url: string;
  fileName: string;
  sizeBytes: number | null;
  /** Хэш от источника, чтобы сверить скачанное. Алгоритм зависит от источника. */
  hash: { algo: 'sha512' | 'sha256' | 'sha1'; value: string } | null;
  /** Внешняя ссылка вместо прямой — Hangar так отдаёт часть релизов. */
  external: boolean;
}

@Injectable()
export class MarketService {
  private readonly logger = new Logger(MarketService.name);

  private async fetchJson<T>(url: string): Promise<T> {
    const res = await request(url, {
      method: 'GET',
      headers: { 'user-agent': USER_AGENT, accept: 'application/json' },
      headersTimeout: TIMEOUT_MS,
      bodyTimeout: TIMEOUT_MS,
      maxRedirections: 3,
    });
    const text = await res.body.text();
    if (res.statusCode >= 400) {
      throw new Error(`${res.statusCode}: ${text.slice(0, 200)}`);
    }
    return JSON.parse(text) as T;
  }

  /**
   * Поиск сразу по двум источникам.
   *
   * Источники опрашиваются параллельно и НЕЗАВИСИМО: если один лежит, выдача
   * второго всё равно доезжает, а про упавший честно сказано в `sources`.
   * Иначе одна недоступная площадка обнуляла бы весь маркет.
   */
  async search(query: string, limit: number, offset: number): Promise<MarketSearchResponseDto> {
    const [modrinth, hangar] = await Promise.all([
      this.searchModrinth(query, limit, offset).catch((e: Error) => e),
      this.searchHangar(query, limit, offset).catch((e: Error) => e),
    ]);

    const hits: MarketHitDto[] = [];
    const sources: MarketSourceStatusDto[] = [];

    for (const [source, result] of [
      ['modrinth', modrinth],
      ['hangar', hangar],
    ] as const) {
      if (result instanceof Error) {
        this.logger.warn(`Источник ${source} не ответил: ${result.message}`);
        sources.push({ source, ok: false, error: 'источник не ответил', total: 0 });
        continue;
      }
      hits.push(...result.hits);
      sources.push({ source, ok: true, total: result.total });
    }

    // Перемешиваем по загрузкам: иначе выдача одного источника всегда была бы
    // сверху просто потому, что он опрошен первым.
    hits.sort((a, b) => b.downloads - a.downloads);

    return { hits, sources, offset, limit };
  }

  private async searchModrinth(
    query: string,
    limit: number,
    offset: number,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    // Единственный facet — тип проекта. По версиям и ядрам НЕ фильтруем
    // намеренно: см. пояснение в шапке файла.
    const facets = encodeURIComponent(JSON.stringify([['project_type:plugin']]));
    const url =
      `${MODRINTH_API}/search?facets=${facets}&limit=${limit}&offset=${offset}` +
      (query ? `&query=${encodeURIComponent(query)}` : '&index=downloads');

    const data = await this.fetchJson<{ hits: ModrinthHit[]; total_hits: number }>(url);
    return {
      total: data.total_hits ?? data.hits.length,
      hits: data.hits.map((h) => ({
        source: 'modrinth' as const,
        id: h.project_id,
        slug: h.slug,
        title: h.title,
        description: h.description,
        author: h.author ?? null,
        downloads: h.downloads ?? 0,
        iconUrl: h.icon_url ?? null,
        categories: h.categories ?? [],
        gameVersions: h.versions ?? [],
        updatedAt: h.date_modified ?? null,
        pageUrl: `https://modrinth.com/plugin/${h.slug}`,
      })),
    };
  }

  private async searchHangar(
    query: string,
    limit: number,
    offset: number,
  ): Promise<{ hits: MarketHitDto[]; total: number }> {
    const url =
      `${HANGAR_API}/projects?limit=${limit}&offset=${offset}` +
      (query ? `&q=${encodeURIComponent(query)}` : '&sort=downloads');

    const data = await this.fetchJson<{
      pagination?: { count?: number };
      result: HangarProject[];
    }>(url);

    return {
      total: data.pagination?.count ?? data.result.length,
      hits: (data.result ?? []).map((p) => this.hangarHit(p)),
    };
  }

  private hangarHit(p: HangarProject): MarketHitDto {
    // supportedPlatforms — это карта «платформа -> список версий игры».
    const gameVersions = [...new Set(Object.values(p.supportedPlatforms ?? {}).flat())];
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
      updatedAt: p.lastUpdated ?? p.createdAt ?? null,
      pageUrl: `https://hangar.papermc.io/${p.namespace.owner}/${p.namespace.slug}`,
    };
  }

  /** Карточка плагина: описание, все заявленные ядра и все версии игры. */
  async getPlugin(source: MarketSourceId, id: string): Promise<MarketPluginDto> {
    return source === 'modrinth' ? this.getModrinthPlugin(id) : this.getHangarPlugin(id);
  }

  private async getModrinthPlugin(id: string): Promise<MarketPluginDto> {
    const p = await this.fetchJson<ModrinthProject>(
      `${MODRINTH_API}/project/${encodeURIComponent(id)}`,
    ).catch(() => null);
    if (!p) throw new NotFoundException('Плагин не найден на Modrinth');

    return {
      source: 'modrinth',
      id: p.id,
      slug: p.slug,
      title: p.title,
      description: p.description,
      body: p.body ?? null,
      iconUrl: p.icon_url ?? null,
      downloads: p.downloads ?? 0,
      followers: p.followers ?? null,
      loaders: p.loaders ?? [],
      gameVersions: p.game_versions ?? [],
      categories: p.categories ?? [],
      pageUrl: `https://modrinth.com/plugin/${p.slug}`,
      sourceUrl: p.source_url ?? null,
      issuesUrl: p.issues_url ?? null,
      wikiUrl: p.wiki_url ?? null,
      discordUrl: p.discord_url ?? null,
    };
  }

  private async getHangarPlugin(id: string): Promise<MarketPluginDto> {
    const p = await this.fetchJson<HangarProject>(
      `${HANGAR_API}/projects/${hangarPath(id)}`,
    ).catch(() => null);
    if (!p) throw new NotFoundException('Плагин не найден на Hangar');

    const platforms = p.supportedPlatforms ?? {};
    const settings = (p.settings ?? {}) as Record<string, string | undefined>;
    return {
      source: 'hangar',
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

  /**
   * ВСЕ опубликованные версии плагина — как на вкладке Versions источника.
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
      source === 'modrinth' ? await this.getModrinthVersions(id) : await this.getHangarVersions(id);
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
    if (source === 'modrinth') {
      const v = await this.fetchJson<ModrinthVersion>(
        `${MODRINTH_API}/version/${encodeURIComponent(versionId)}`,
      ).catch(() => null);
      const file = v && pickPrimary(v.files ?? []);
      if (!file) throw new NotFoundException('У этой версии нет файла для скачивания');
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
      };
    }

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
    };
  }

  /**
   * Иконка плагина, забранная панелью и отданная браузеру со своего адреса.
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

/**
 * Хосты, с которых панель соглашается забирать иконки плагинов.
 *
 * Белый список, а не «любой https» — потому что прокси картинок это чужой
 * URL, который панель запрашивает сама, изнутри сети. Без ограничения по
 * хосту такой маршрут превращается в SSRF: подставив адрес вида
 * http://10.0.0.2:8085/… или http://169.254.169.254/…, снаружи можно было бы
 * читать то, до чего дотягивается панель, но не дотягивается браузер.
 *
 * Список закрытый и сверен с тем, что реально отдают источники: Modrinth
 * держит иконки на своём CDN, Hangar — на своём.
 */
const ICON_HOSTS = new Set([
  'cdn.modrinth.com',
  'hangarcdn.papermc.io',
  'hangar.papermc.io',
]);

/** Больше иконки не весят, а память панели не резиновая. */
const MAX_ICON_BYTES = 2 * 1024 * 1024;

/** Что панель соглашается считать картинкой. */
const ICON_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif',
  'image/svg+xml',
]);

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
