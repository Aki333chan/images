/**
 * Алерты по перегрузке ресурсов и метрики серверов для списка.
 */

/**
 * Тип алерта.
 *
 * НАМЕРЕННО ОБЩЕЕ ПОЛЕ, хотя сейчас значений всего два. Алерты — это про
 * «что-то с сервером не так», и поводов для такого письма со временем
 * набирается много: сервер упал, кончается диск, бэкап не собрался. Завести
 * тип сразу дешевле, чем потом разносить одну таблицу на несколько или
 * добавлять колонку в живую базу с уже накопленной историей.
 */
export const ALERT_TYPES = ['cpu', 'memory'] as const;
export type AlertType = (typeof ALERT_TYPES)[number];

export const ALERT_TYPE_LABELS: Record<AlertType, string> = {
  cpu: 'Загрузка CPU',
  memory: 'Использование памяти',
};

/**
 * Настройки алертов. Общие для панели, задаёт ГМ.
 *
 * Пороги — в процентах ОТ ЛИМИТА сервера, а не от абстрактных 100: сравнивать
 * сырой процент CPU не с чем, см. resources.ts.
 */
export interface AlertSettingsDto {
  /** false — не шлём ничего, даже если пороги заданы. */
  enabled: boolean;
  /** Порог CPU в % от лимита. null — по CPU не следим. */
  cpuThresholdPercent: number | null;
  /** Порог памяти в % от лимита. null — по памяти не следим. */
  memoryThresholdPercent: number | null;
  /**
   * Сколько минут превышение должно держаться подряд, прежде чем слать письмо.
   *
   * Мгновенный триггер бесполезен: сервер уходит в потолок на запуске, на
   * генерации чанков, на загрузке бэкапа — это норма, а не авария. Письмо
   * должно означать «так уже некоторое время», иначе на него перестанут
   * смотреть в первую же неделю.
   */
  sustainedMinutes: number;
  /**
   * Не чаще одного письма по одному серверу и типу за столько минут.
   *
   * Затянувшаяся перегрузка — это одна проблема, а не письмо каждую минуту.
   */
  cooldownMinutes: number;
}

export const DEFAULT_ALERT_SETTINGS: AlertSettingsDto = {
  // Выключено по умолчанию: рассылка писем сотрудникам — это то, что владелец
  // панели включает осознанно, а не обнаруживает postfactum.
  enabled: false,
  cpuThresholdPercent: 90,
  memoryThresholdPercent: 90,
  sustainedMinutes: 5,
  cooldownMinutes: 60,
};

export const ALERT_SETTINGS_LIMITS = {
  minThreshold: 50,
  maxThreshold: 100,
  minSustainedMinutes: 1,
  maxSustainedMinutes: 120,
  minCooldownMinutes: 5,
  maxCooldownMinutes: 24 * 60,
} as const;

// ------------------------------------------------- Метрики для списка

/**
 * Снимок состояния сервера для списка.
 *
 * Собирается кроном и читается из базы: на десятки серверов поход в
 * Pterodactyl за каждым при каждом открытии списка — это десятки запросов на
 * одно нажатие, и список открывался бы секундами.
 */
export interface ServerMetricsDto {
  serverId: string;
  /** running / offline / starting / stopping. null — снимка ещё нет. */
  state: string | null;
  /** Сырое значение Pterodactyl: 200 = два ядра целиком. */
  cpuAbsolutePercent: number | null;
  /** Лимит CPU в тех же единицах; 0 — без ограничения. */
  cpuLimitPercent: number;
  memoryBytes: number | null;
  memoryLimitBytes: number;
  /** Игроки онлайн — от игрового модуля. null — модуль не назначен или молчит. */
  playersOnline: number | null;
  playersMax: number | null;
  /** Когда снят замер. null — ни одного ещё не было. */
  sampledAt: string | null;
}

// ------------------------------------------------- Сортировка списка

/**
 * Критерии сортировки списка серверов.
 *
 * 'manual' стоит особняком: он не сортирует, а показывает порядок, который
 * человек выставил себе сам перетаскиванием. Поэтому перетаскивание работает
 * только в нём — в остальных режимах порядок задан критерием, и тянуть
 * карточку было бы бессмысленным жестом, результат которого тут же пропадёт.
 */
export const SERVER_SORTS = ['status', 'players', 'name', 'game', 'manual'] as const;
export type ServerSort = (typeof SERVER_SORTS)[number];

export const SERVER_SORT_LABELS: Record<ServerSort, string> = {
  status: 'Сначала онлайн',
  players: 'По игрокам онлайн',
  name: 'По имени',
  game: 'По игре',
  manual: 'Свой порядок',
};

/** Личные настройки списка серверов. Свои у каждого, не общие для панели. */
export interface ServerListPrefsDto {
  sort: ServerSort;
  /**
   * Порядок карточек в режиме «Свой порядок» — просто список id.
   *
   * Список, а не поле `position` у сервера: позиция у каждого своя, и хранить
   * её в самом сервере значило бы, что перетаскивание у одного человека
   * переставляет карточки всем остальным.
   *
   * Серверы, которых в списке нет (новые, только что появившиеся после
   * синхронизации), показываются после упорядоченных. Пропавшие id
   * игнорируются — чистить их отдельно не нужно.
   */
  order: string[];
}

export const DEFAULT_SERVER_LIST_PREFS: ServerListPrefsDto = { sort: 'status', order: [] };
