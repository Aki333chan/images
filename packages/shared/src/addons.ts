/**
 * Наши собственные плагины — «аддоны Aurum».
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ МАРКЕТА. Маркет — это поиск по чужим каталогам: человек
 * набирает название, сравнивает версии, выбирает. Здесь ничего искать не надо:
 * список фиксирован, версия всегда последняя, а автор — мы. Смешать это с
 * маркетом значило бы завести четвёртый «источник» с одним-единственным
 * поисковым запросом, который всегда возвращает одно и то же.
 *
 * ЗАЧЕМ ЭТО ВООБЩЕ. Сервера заводят и третьи лица, не связанные с проектом.
 * Они не знают, что у панели есть свой companion и без него половина её
 * возможностей не работает, — и не должны узнавать об этом из документации,
 * которую никто не читает. Поэтому обязательное панель ставит молча, а
 * необязательное показывает один раз и больше не навязывает.
 */

/** Один наш плагин в репозитории аддонов. */
export interface AurumAddon {
  /** Стабильный id для API и чекбоксов. Не меняется никогда. */
  id: string;
  /**
   * Имя плагина, под которым он приходит от сервера и лежит в файле.
   *
   * По нему и решается «уже стоит»: сравнение нестрогое (по началу имени
   * файла), потому что в plugins/ он зовётся «AurumCompanion-0.1.0.jar».
   */
  pluginName: string;
  displayName: string;
  /** Ключ словаря панели: чем этот плагин полезен. */
  aboutKey: string;
  /**
   * Префикс тега релиза в репозитории аддонов: «companion-v0.3.1».
   *
   * Префикс, а не имя релиза: в одном репозитории живут все три плагина, и
   * различать их релизы надо по тегу, а не по порядку публикации.
   */
  tagPrefix: string;
}

/** Что панель ставит на сервер этого модуля. */
export interface ModuleAddons {
  /**
   * Ставится автоматически и без спроса, если его ещё нет.
   *
   * Не «рекомендация», а условие работы: без companion панель не видит ни
   * инвентарей, ни экономики, ни списка плагинов. Спрашивать разрешения на
   * то, без чего инструмент не работает, — вопрос ради вопроса.
   *
   * null — у модуля своего плагина нет, и автоустановка для него выключена.
   */
  required: AurumAddon | null;
  /** Предлагаются поп-апом и кнопкой «Рекомендуемые плагины». */
  optional: AurumAddon[];
}

export const AURUM_COMPANION: AurumAddon = {
  id: 'aurum-companion',
  pluginName: 'AurumCompanion',
  displayName: 'AurumCompanion',
  aboutKey: 'addons.about.companion',
  tagPrefix: 'companion-v',
};

export const AURUM_AUTH: AurumAddon = {
  id: 'aurum-auth',
  pluginName: 'AurumAuth',
  displayName: 'AurumAuth',
  aboutKey: 'addons.about.auth',
  tagPrefix: 'auth-v',
};

export const AURUM_GUILDS: AurumAddon = {
  id: 'aurum-guilds',
  pluginName: 'AurumGuilds',
  displayName: 'AurumGuilds',
  aboutKey: 'addons.about.guilds',
  tagPrefix: 'guilds-v',
};

export const AURUM_ARENA: AurumAddon = {
  id: 'aurum-arena',
  pluginName: 'AurumArena',
  displayName: 'AurumArena',
  aboutKey: 'addons.about.arena',
  tagPrefix: 'arena-v',
};

export const AURUM_SLOTS: AurumAddon = {
  id: 'aurum-slots',
  pluginName: 'AurumSlots',
  displayName: 'AurumSlots',
  aboutKey: 'addons.about.slots',
  tagPrefix: 'slots-v',
};

export const ADDONS_NPC: AurumAddon = {
  id: 'addons-npc',
  pluginName: 'AddonsNPC',
  displayName: 'AddonsNPC',
  aboutKey: 'addons.about.npc',
  tagPrefix: 'npc-v',
};

/**
 * Какие аддоны относятся к какому модулю.
 *
 * ПОЧЕМУ ЗДЕСЬ ТОЛЬКО PAPER. Все три плагина — обычные плагины Bukkit: они
 * загружаются из plugins/ и обращаются к Bukkit API. На Forge и NeoForge
 * такого API нет вовсе, и положенный туда jar просто не загрузится, а
 * Palworld и 7 Days to Die — вообще другие игры с другим форматом дополнений.
 * Пустой список честнее, чем предложение, которое не заработает.
 *
 * Модуль, которого здесь нет, ведёт себя как модуль с пустым списком: ничего
 * не ставится, поп-ап не показывается. Добавить игре свой пакет — это строка
 * здесь и релиз в репозитории аддонов, править больше нигде не нужно.
 */
export const MODULE_ADDONS: Record<string, ModuleAddons> = {
  minecraft: {
    required: AURUM_COMPANION,
    // Порядок — от того, без чего сервер обходится хуже всего, к развлечениям.
    // Он же порядок в поп-апе: первым читают то, что стоит первым.
    optional: [AURUM_AUTH, AURUM_GUILDS, ADDONS_NPC, AURUM_ARENA, AURUM_SLOTS],
  },
};

export function addonsForModule(moduleId: string | null | undefined): ModuleAddons {
  if (!moduleId) return { required: null, optional: [] };
  return MODULE_ADDONS[moduleId] ?? { required: null, optional: [] };
}

/** Все аддоны модуля одним списком — обязательный первым. */
export function allAddons(moduleId: string | null | undefined): AurumAddon[] {
  const { required, optional } = addonsForModule(moduleId);
  return required ? [required, ...optional] : [...optional];
}

// ------------------------------------------------------------------- DTO

/** Состояние одного аддона на конкретном сервере. */
export interface ServerAddonDto {
  id: string;
  displayName: string;
  aboutKey: string;
  /** true — файл плагина уже лежит в plugins/. */
  installed: boolean;
}

/**
 * Что панель знает об аддонах этого сервера.
 *
 * `canOffer` — единственное поле, по которому фронтенд решает, показывать ли
 * поп-ап сам. Собирается оно на бэке, а не из четырёх флагов в браузере:
 * условие показа одно, и держать его в двух местах — способ однажды показать
 * поп-ап после «не предлагать».
 */
export interface ServerAddonsDto {
  /** Фича включена глобально (настройка ГМ). */
  featureEnabled: boolean;
  /** Модуль сервера; null — модуль не назначен. */
  moduleId: string | null;
  /** Для этого сервера нажимали «не предлагать». */
  dismissed: boolean;
  /** У смотрящего есть право ставить плагины. */
  canInstall: boolean;
  /** Файловый API Pterodactyl ответил: без него состояние неизвестно. */
  filesAvailable: boolean;
  required: ServerAddonDto | null;
  optional: ServerAddonDto[];
  /**
   * Показывать ли поп-ап автоматически прямо сейчас.
   *
   * Все условия сразу: фича включена, модуль знает опциональные аддоны, хотя
   * бы один не установлен, есть право на установку, поп-ап не отклонён
   * навсегда и файлы сервера видны.
   */
  canOffer: boolean;
  /**
   * Что произошло с обязательным аддоном на этом обращении.
   *
   * 'installed' — панель только что его поставила; 'restart-required' — то же
   * самое, но сервер запущен и подхватит плагин лишь после перезапуска.
   * Присылается только в ответе bootstrap: это событие, а не состояние.
   */
  requiredInstall?: 'installed' | 'restart-required' | 'failed';
  /** Почему не получилось — ключ словаря. Только при 'failed'. */
  requiredError?: string;
}

/** Итог установки выбранных аддонов. */
export interface AddonInstallResultDto {
  id: string;
  displayName: string;
  ok: boolean;
  /** Ключ словаря: успех с рестартом, успех без него или причина отказа. */
  message: string;
  messageValues?: Record<string, string>;
  restartRequired: boolean;
}

export interface AddonInstallResponseDto {
  results: AddonInstallResultDto[];
}
