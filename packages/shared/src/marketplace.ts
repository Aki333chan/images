/**
 * Маркет плагинов Minecraft: контракт между apps/api и apps/web.
 *
 * ГЛАВНЫЙ ПРИНЦИП, который здесь закреплён типами: заявленная автором
 * совместимость — это ПОДСКАЗКА, а не фильтр. Панель никогда не прячет и не
 * блокирует версию плагина только потому, что автор не указал в релизе
 * поддержку конкретной версии сервера. Огромная часть плагинов прекрасно
 * работает на ядрах новее заявленных — просто автор не обновил метаданные.
 *
 * Поэтому в контракте нет и не должно появиться поля вида `compatible: boolean`,
 * по которому что-то отсеивается. Есть `MarketMatch` — признак для бейджа,
 * и решение всегда за человеком.
 */

/** Откуда результат. SpigotMC не включён: у него нет официального API. */
export type MarketSourceId = 'modrinth' | 'hangar';

export const MARKET_SOURCES: { id: MarketSourceId; label: string; site: string }[] = [
  { id: 'modrinth', label: 'Modrinth', site: 'https://modrinth.com' },
  { id: 'hangar', label: 'Hangar', site: 'https://hangar.papermc.io' },
];

/**
 * Насколько версия совпадает с текущим сервером — ТОЛЬКО для бейджа.
 *
 * 'match'        — автор заявил эту игровую версию/ядро;
 * 'not-declared' — не заявил. Это не значит «не работает»;
 * 'unknown'      — панель не знает версию сервера, сравнивать не с чем.
 */
export type MarketMatch = 'match' | 'not-declared' | 'unknown';

export interface MarketCompatibility {
  gameVersion: MarketMatch;
  loader: MarketMatch;
}

/** С чем сравниваем: то, что панель знает о текущем сервере. */
export interface ServerTargetDto {
  serverId: string;
  name: string;
  /** Версия игры, если её удалось определить. null — не знаем. */
  gameVersion: string | null;
  /** Ядро: paper, spigot, purpur… null — не знаем. */
  loader: string | null;
  /** Текущее состояние из Pterodactyl: running/offline/… null — не ответил. */
  status: string | null;
}

export interface MarketHitDto {
  source: MarketSourceId;
  /** Идентификатор внутри источника — с ним же идут запросы к деталям. */
  id: string;
  slug: string;
  title: string;
  description: string;
  author: string | null;
  downloads: number;
  iconUrl: string | null;
  categories: string[];
  /** Заявленные игровые версии — для бейджа в строке результата. */
  gameVersions: string[];
  updatedAt: string | null;
  /** Страница плагина на самом источнике. */
  pageUrl: string;
}

/**
 * Состояние одного источника в выдаче.
 *
 * Источник может лежать или отвечать ошибкой — это не повод обрушивать весь
 * поиск: показываем то, что нашлось, и честно говорим, что второй не ответил.
 */
export interface MarketSourceStatusDto {
  source: MarketSourceId;
  ok: boolean;
  error?: string;
  total: number;
}

export interface MarketSearchResponseDto {
  hits: MarketHitDto[];
  sources: MarketSourceStatusDto[];
  offset: number;
  limit: number;
}

export interface MarketPluginDto {
  source: MarketSourceId;
  id: string;
  slug: string;
  title: string;
  description: string;
  /** Полное описание в markdown, если источник его отдаёт. */
  body: string | null;
  iconUrl: string | null;
  downloads: number;
  followers: number | null;
  /** ВСЕ заявленные ядра: paper, spigot, bukkit, purpur, velocity… */
  loaders: string[];
  /** ВСЕ заявленные игровые версии. */
  gameVersions: string[];
  categories: string[];
  pageUrl: string;
  sourceUrl: string | null;
  issuesUrl: string | null;
  wikiUrl: string | null;
  discordUrl: string | null;
}

export type MarketChannel = 'release' | 'beta' | 'alpha';

/** Одна опубликованная версия плагина — как на вкладке Versions источника. */
export interface MarketVersionDto {
  id: string;
  /** Человеческое имя релиза. */
  name: string;
  /** Номер версии, если источник отдаёт его отдельно. */
  versionNumber: string;
  channel: MarketChannel;
  loaders: string[];
  gameVersions: string[];
  publishedAt: string;
  downloads: number | null;
  changelog: string | null;
  fileName: string | null;
  fileSizeBytes: number | null;
  /**
   * Совпадение с текущим сервером — если сервер передан в запросе.
   * Ещё раз: это бейдж. Установить можно ЛЮБУЮ версию из списка.
   */
  compatibility?: MarketCompatibility;
}

export interface MarketVersionsResponseDto {
  versions: MarketVersionDto[];
  /** С каким сервером сравнивались бейджи; null — сравнение не запрашивали. */
  comparedTo: ServerTargetDto | null;
}

/** Результат установки. */
export interface PluginInstallResultDto {
  ok: boolean;
  fileName: string;
  sizeBytes: number;
  /** true — сервер сейчас запущен, плагин подхватится только после рестарта. */
  restartRequired: boolean;
  message: string;
}

// ------------------------------------------------- Установленные плагины

/** Состояние установленного плагина на сервере. */
export type InstalledPluginState =
  /** Файл лежит в plugins/ и сервер сообщает, что плагин включён. */
  | 'enabled'
  /** Файл в plugins/, но плагин выключен в рантайме. */
  | 'disabled-runtime'
  /** Файл перенесён в plugins/.disabled/ — после рестарта не загрузится. */
  | 'disabled-file';

export interface InstalledPluginDto {
  name: string;
  version: string | null;
  state: InstalledPluginState;
  /** Имя файла в plugins/ — по нему идут перенос и удаление. */
  fileName: string | null;
  /** true — это сам companion-плагин, его трогать нельзя. */
  protected: boolean;
}

export interface InstalledPluginsResponseDto {
  /** false — companion-плагин не настроен, живого состояния не видно. */
  companionAvailable: boolean;
  /** false — файловый API Pterodactyl недоступен, файлами управлять нельзя. */
  filesAvailable: boolean;
  reason?: string;
  plugins: InstalledPluginDto[];
}

/** Куда складываем выключенные файлы. Точка в начале — папка служебная. */
export const DISABLED_PLUGINS_DIR = '.disabled';

export const PLUGIN_PERMISSIONS = {
  /** Смотреть маркет и ставить плагины. */
  install: 'minecraft.plugins.install',
  /** Включать, выключать и удалять уже установленные. */
  manage: 'minecraft.plugins.manage',
} as const;

/**
 * Бейдж совпадения. Чистая функция — считается одинаково на обеих сторонах и
 * проверяется тестами без сети.
 *
 * Сравнение игровой версии по префиксу: плагин, заявленный для «1.21», обычно
 * работает и на «1.21.4», и заставлять человека читать список из тридцати
 * номеров ради этого незачем.
 */
export function matchGameVersion(declared: string[], serverVersion: string | null): MarketMatch {
  if (!serverVersion) return 'unknown';
  if (declared.length === 0) return 'not-declared';
  const needle = serverVersion.trim();
  const hit = declared.some(
    (v) => v === needle || needle.startsWith(`${v}.`) || v.startsWith(`${needle}.`),
  );
  return hit ? 'match' : 'not-declared';
}

/**
 * Ядра сравниваем мягко: плагин под Bukkit работает на Spigot, под Spigot — на
 * Paper, под Paper — на Purpur. Обратное неверно, поэтому список направленный.
 */
const LOADER_ACCEPTS: Record<string, string[]> = {
  bukkit: ['bukkit'],
  spigot: ['bukkit', 'spigot'],
  paper: ['bukkit', 'spigot', 'paper'],
  purpur: ['bukkit', 'spigot', 'paper', 'purpur'],
  folia: ['folia', 'paper'],
};

export function matchLoader(declared: string[], serverLoader: string | null): MarketMatch {
  if (!serverLoader) return 'unknown';
  if (declared.length === 0) return 'not-declared';
  const accepted = LOADER_ACCEPTS[serverLoader.toLowerCase()] ?? [serverLoader.toLowerCase()];
  return declared.some((l) => accepted.includes(l.toLowerCase())) ? 'match' : 'not-declared';
}

export function compatibilityOf(
  version: { loaders: string[]; gameVersions: string[] },
  target: ServerTargetDto | null,
): MarketCompatibility {
  return {
    gameVersion: matchGameVersion(version.gameVersions, target?.gameVersion ?? null),
    loader: matchLoader(version.loaders, target?.loader ?? null),
  };
}
