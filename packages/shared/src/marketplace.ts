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

/**
 * Что ищем: плагин для серверного ядра или мод для загрузчика.
 *
 * РАЗДЕЛЬНО, А НЕ ОДНИМ СПИСКОМ, и это не вкусовщина. Плагин под Paper и мод
 * под Fabric ставятся в разные папки, требуют разного сервера и на чужом
 * сервере просто не загрузятся. Свалить их в одну выдачу значит предложить
 * человеку установить то, что заведомо не заработает.
 */
export type MarketProjectType = 'plugin' | 'mod';

/** Откуда результат. */
export type MarketSourceId = 'modrinth' | 'hangar' | 'spiget';

export const MARKET_SOURCES: {
  id: MarketSourceId;
  label: string;
  site: string;
  /**
   * Что источник вообще отдаёт.
   *
   * Проверено по их собственным спецификациям, а не по ощущениям:
   *   Hangar — перечисление Platform в его OpenAPI это ровно
   *   PAPER, WATERFALL, VELOCITY. Ни Forge, ни Fabric там нет;
   *   SpigotMC — площадка плагинов семейства Bukkit, модов на ней не бывает.
   *
   * Поэтому на вкладке «Моды» источник ровно один — Modrinth. Показывать
   * рядом с ним отключённые Hangar и SpigotMC с вечным «ничего не найдено»
   * было бы обманом.
   */
  provides: MarketProjectType[];
}[] = [
  { id: 'modrinth', label: 'Modrinth', site: 'https://modrinth.com', provides: ['plugin', 'mod'] },
  { id: 'hangar', label: 'Hangar', site: 'https://hangar.papermc.io', provides: ['plugin'] },
  { id: 'spiget', label: 'SpigotMC', site: 'https://spigotmc.org', provides: ['plugin'] },
];

/** Источники, у которых есть что показать для этого типа проекта. */
export function sourcesFor(type: MarketProjectType): MarketSourceId[] {
  return MARKET_SOURCES.filter((s) => s.provides.includes(type)).map((s) => s.id);
}

/**
 * Загрузчики модов и серверные ядра.
 *
 * Разделены потому же, почему разделены плагины и моды: под Fabric не
 * работает то, что собрано под Forge, и наоборот.
 */
export const MOD_LOADERS = ['forge', 'neoforge', 'fabric', 'quilt'] as const;
export const PLUGIN_LOADERS = ['paper', 'spigot', 'bukkit', 'purpur', 'folia', 'velocity'] as const;

export function loadersFor(type: MarketProjectType): readonly string[] {
  return type === 'mod' ? MOD_LOADERS : PLUGIN_LOADERS;
}

/**
 * Как сортировать выдачу.
 *
 * 'relevance' у каждого источника свой и считается им самим — панель его не
 * пересчитывает и не притворяется, что умеет лучше.
 */
export const MARKET_SORTS = ['relevance', 'downloads', 'updated', 'name'] as const;
export type MarketSort = (typeof MARKET_SORTS)[number];

/**
 * Ключи подписей сортировки, а не сами подписи.
 *
 * Список общий для панели и писем, а язык у каждого читателя свой; готовая
 * фраза здесь означала бы русский «По загрузкам» в польском выпадающем
 * списке.
 */
export const MARKET_SORT_KEYS: Record<MarketSort, string> = {
  relevance: 'market.sort.relevance',
  downloads: 'market.sort.downloads',
  updated: 'market.sort.updated',
  name: 'market.sort.name',
};

/**
 * Фильтры выдачи. Все КОМБИНИРУЕМЫЕ: внутри одного поля значения складываются
 * по «или», между полями — по «и».
 *
 * Пустой список означает «без ограничения», а не «ничего не показывать».
 * Это важное различие: галочки снимают, чтобы увидеть больше, а не чтобы
 * получить пустой экран.
 */
export interface MarketFilters {
  /** Версии игры: ['1.21', '1.20.4']. */
  gameVersions: string[];
  /** Ядра или загрузчики: ['fabric', 'forge']. */
  loaders: string[];
  /** Источники: ['modrinth', 'spiget']. */
  sources: MarketSourceId[];
}

export const EMPTY_FILTERS: MarketFilters = { gameVersions: [], loaders: [], sources: [] };

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
  /** Заявленные ядра или загрузчики — по ним же работает фильтр. */
  loaders: string[];
  updatedAt: string | null;
  /** Страница плагина на самом источнике. */
  pageUrl: string;
  /** Плагин или мод: от этого зависит папка установки. */
  projectType: MarketProjectType;
  /**
   * Платный ресурс. Бывает только на SpigotMC.
   *
   * Скачать его панель не может — за ним стоит оплата на самом сайте.
   * Прячем не результат, а кнопку установки: знать, что такой плагин
   * существует, полезно, а вот молча предложить установить то, что не
   * скачается, — нет.
   */
  premium?: boolean;
  /**
   * Файл лежит не у источника, а на стороннем сайте.
   *
   * Тоже про SpigotMC: автор вправе выложить jar куда угодно. Скачивание
   * такого файла — это поход по произвольному адресу, и хэша у него нет.
   */
  externalFile?: boolean;
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
  /** Плагин или мод: от этого зависит папка установки. */
  projectType: MarketProjectType;
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
  premium?: boolean;
  externalFile?: boolean;
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
  /** Ключ словаря панели: фразу собирает браузер, на языке того, кто ставил. */
  message: string;
  /** Подстановки к нему — имена и пути, которые не переводятся. */
  messageValues?: Record<string, string>;
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
  /**
   * true — плагин, на котором держится панель: companion либо один из
   * KNOWN_PLUGINS. Выключение, отключение файлом и удаление для него закрыты.
   */
  protected: boolean;
  /**
   * Почему закрыто — ключ словаря панели, а не готовая фраза.
   *
   * Подстановка в нём одна, `{name}`, и берётся она из поля `name` рядом:
   * имя плагина не переводится, а вот всё остальное в объяснении — да.
   */
  protectedReasonKey?: string;
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

// ------------------------------------------- Сортировка и фильтрация

/**
 * Ядро сервера -> что ему подходит.
 *
 * Нужно, чтобы, открыв маркет со страницы сервера, человек сразу оказался на
 * нужной вкладке: на Paper искать моды бессмысленно, на Fabric — плагины.
 * Неизвестное ядро оставляем на плагинах: их на свете больше, и Paper —
 * самый частый случай.
 */
export function defaultProjectTypeFor(loader: string | null | undefined): MarketProjectType {
  const value = (loader ?? '').toLowerCase();
  return (MOD_LOADERS as readonly string[]).includes(value) ? 'mod' : 'plugin';
}

/**
 * Подходит ли результат под фильтры.
 *
 * Пустое поле фильтра — «без ограничения». Сравнение версий по префиксу, как
 * и у бейджа совместимости: заявленный «1.21» покрывает «1.21.4», и
 * заставлять человека отмечать тридцать галочек ради этого незачем.
 */
export function passesFilters(hit: MarketHitDto, filters: MarketFilters): boolean {
  if (filters.sources.length > 0 && !filters.sources.includes(hit.source)) return false;

  if (filters.loaders.length > 0) {
    const declared = hit.loaders.map((l) => l.toLowerCase());
    // Результат без объявленных загрузчиков не отсеиваем: у SpigotMC их нет
    // вовсе, и фильтр по ядру спрятал бы весь источник целиком.
    if (declared.length > 0 && !filters.loaders.some((l) => declared.includes(l.toLowerCase()))) {
      return false;
    }
  }

  if (filters.gameVersions.length > 0) {
    const declared = hit.gameVersions;
    if (declared.length > 0) {
      const hit_ = filters.gameVersions.some((wanted) =>
        declared.some(
          (v) => v === wanted || wanted.startsWith(`${v}.`) || v.startsWith(`${wanted}.`),
        ),
      );
      if (!hit_) return false;
    }
  }

  return true;
}

/**
 * Сортировка сведённой выдачи.
 *
 * Считается на панели, а не у источника, потому что результаты приходят из
 * трёх мест сразу: каждый отсортировал СВОЙ кусок, а человек смотрит на
 * общий список. Без общей сортировки сверху всегда оказывался бы тот
 * источник, который опрошен первым.
 *
 * 'relevance' — исключение: своей меры релевантности у панели нет и быть не
 * может, поэтому порядок остаётся таким, каким его вернули источники,
 * с чередованием, чтобы ни один не занял всю первую страницу.
 */
export function sortHits(hits: MarketHitDto[], sort: MarketSort): MarketHitDto[] {
  const list = [...hits];
  switch (sort) {
    case 'downloads':
      return list.sort((a, b) => b.downloads - a.downloads);
    case 'updated':
      return list.sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''));
    case 'name':
      return list.sort((a, b) => a.title.localeCompare(b.title, 'ru', { sensitivity: 'base' }));
    case 'relevance':
    default:
      return interleaveBySource(list);
  }
}

/**
 * Чередование по источникам.
 *
 * Берём по одному результату от каждого источника по кругу, сохраняя порядок
 * внутри источника. Иначе при сортировке «по совпадению» первые двадцать
 * строк были бы из Modrinth просто потому, что его ответ пришёл первым, —
 * и человек решил бы, что остальные источники ничего не нашли.
 */
function interleaveBySource(hits: MarketHitDto[]): MarketHitDto[] {
  const bySource = new Map<MarketSourceId, MarketHitDto[]>();
  for (const hit of hits) {
    const list = bySource.get(hit.source);
    if (list) list.push(hit);
    else bySource.set(hit.source, [hit]);
  }

  const queues = [...bySource.values()];
  const out: MarketHitDto[] = [];
  let index = 0;
  while (out.length < hits.length) {
    let moved = false;
    for (const queue of queues) {
      const next = queue[index];
      if (next) {
        out.push(next);
        moved = true;
      }
    }
    if (!moved) break;
    index++;
  }
  return out;
}
