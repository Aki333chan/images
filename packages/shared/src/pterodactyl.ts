/**
 * Общие возможности Pterodactyl — файлы, сеть, запуск, базы, бэкапы,
 * расписания.
 *
 * НИЧЕГО ЗДЕСЬ НЕ ЗАВИСИТ ОТ ИГРОВОГО МОДУЛЯ. Это свойства самого
 * Pterodactyl, одинаковые для сервера Minecraft, Palworld и любого другого:
 * файл есть файл, а бэкап есть бэкап. Поэтому и живёт в ядре, а не в
 * манифесте модуля, и вкладки показываются независимо от того, какой модуль
 * подключён.
 *
 * Всё идёт через Client API одного служебного пользователя. Собственные
 * учётки Pterodactyl персоналу не заводятся: права у нас свои, и две
 * параллельные системы прав рано или поздно разойдутся.
 */

// ------------------------------------------------------------------ Файлы

/** Запись каталога — как её отдаёт Pterodactyl (FileObjectTransformer). */
export interface PteroFileDto {
  name: string;
  /** Права в виде «-rw-r--r--». */
  mode: string;
  size: number;
  isFile: boolean;
  isSymlink: boolean;
  mimetype: string;
  modifiedAt: string;
}

/**
 * Ответ на запрос содержимого каталога.
 *
 * Путь возвращается разобранным на части — чтобы интерфейс не разбирал
 * строку сам и не разошёлся с бэкендом в трактовке «..».
 */
export interface PteroDirectoryDto {
  /** Нормализованный путь: всегда начинается с «/», без хвостового слэша. */
  path: string;
  /** Хлебные крошки: [{ name: 'plugins', path: '/plugins' }, …]. */
  breadcrumbs: { name: string; path: string }[];
  entries: PteroFileDto[];
}

export interface PteroFileContentDto {
  path: string;
  content: string;
  /** true — файл обрезан по лимиту: показывать, но не давать сохранить. */
  truncated: boolean;
}

/**
 * Расширения, которые панель открывает в редакторе.
 *
 * Список нужен не для запрета, а для подсказки: всё остальное предлагается
 * скачать, а не открыть. Открыть мировые данные в текстовом редакторе и
 * сохранить их обратно — верный способ испортить мир.
 */
export const EDITABLE_EXTENSIONS = [
  'yml', 'yaml', 'json', 'properties', 'txt', 'log', 'conf', 'cfg', 'ini',
  'toml', 'xml', 'md', 'sh', 'env', 'lang', 'csv', 'js', 'ts', 'sql', 'html', 'css',
] as const;

/** Язык подсветки для CodeMirror по имени файла. */
export function highlightLanguage(
  fileName: string,
): 'yaml' | 'json' | 'xml' | 'properties' | 'plain' {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
  if (ext === 'yml' || ext === 'yaml') return 'yaml';
  if (ext === 'json') return 'json';
  if (ext === 'xml' || ext === 'html') return 'xml';
  if (ext === 'properties' || ext === 'ini' || ext === 'cfg' || ext === 'conf' || ext === 'env') {
    return 'properties';
  }
  return 'plain';
}

/** Порог редактирования — тот же, что у Pterodactyl (max_edit_size). */
export const MAX_EDITABLE_BYTES = 4 * 1024 * 1024;

/**
 * Потолок загрузки и скачивания файла через панель — 64 МиБ.
 *
 * ЗДЕСЬ, А НЕ ТОЛЬКО НА БЭКЕНДЕ, потому что предел должны знать все трое:
 * фронтенд — чтобы отказать сразу и объяснить, а не гнать файл впустую;
 * бэкенд — чтобы отказать по-настоящему; nginx — чтобы пропускать чуть больше
 * и дать бэкенду ответить по-человечески (см. deploy/nginx).
 *
 * Раньше пределов в цепочке было три, заданных независимо друг от друга, и
 * они разошлись: nginx рубил запрос раньше всех и отдавал голый 413, до
 * которого панель даже не доходила. Одно значение на всех — не аккуратность,
 * а единственный способ, чтобы отказ объяснял себя.
 *
 * Число не «сколько влезет»: файл целиком оказывается в памяти процесса
 * панели, и несколько одновременных загрузок мирового архива положили бы её.
 * Кому нужно больше — это уже бэкап или SFTP.
 */
export const MAX_TRANSFER_BYTES = 64 * 1024 * 1024;

/**
 * «64 МиБ» — одна формулировка на все сообщения о превышении.
 *
 * Переводчик аргументом — по той же причине, что и в resources.ts: функция
 * зовётся и из панели, и из бэкенда, хука здесь взять неоткуда. Умолчание
 * русское, чтобы вызовы, до которых перевод ещё не дошёл, работали как
 * раньше, а не показывали ключ.
 */
export function formatTransferLimit(t: (key: string) => string = () => 'МиБ'): string {
  return `${Math.round(MAX_TRANSFER_BYTES / 1024 / 1024)} ${t('size.mib')}`;
}

/** Можно ли предложить открыть файл в редакторе. */
export function isEditableFile(file: { name: string; isFile: boolean; size: number }): boolean {
  if (!file.isFile) return false;
  // Больший файл Pterodactyl и не отдаст — предлагать открыть его нечестно.
  if (file.size > MAX_EDITABLE_BYTES) return false;
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  // Файл без расширения (Dockerfile, README) чаще всего текстовый.
  if (!file.name.includes('.')) return true;
  return (EDITABLE_EXTENSIONS as readonly string[]).includes(ext);
}

// ------------------------------------------------------------------- Сеть

/** Аллокация: адрес и порт, по которым сервер доступен снаружи. */
export interface PteroAllocationDto {
  id: number;
  ip: string;
  /** Доменное имя вместо IP, если у ноды оно настроено. */
  ipAlias: string | null;
  port: number;
  /** Заметка администратора: зачем этот порт. */
  notes: string | null;
  /** Основная: именно её сервер сообщает игрокам. */
  isDefault: boolean;
}

// ----------------------------------------------------------------- Запуск

/**
 * Переменная egg.
 *
 * Что можно менять, решает сам egg: Pterodactyl отдаёт только переменные с
 * `user_viewable`, а править разрешает лишь `user_editable`. Панель этот
 * запрет не обходит и не дублирует — просто показывает как есть.
 */
export interface PteroVariableDto {
  name: string;
  description: string;
  /** Имя переменной окружения: SERVER_JARFILE, VERSION и т. п. */
  envVariable: string;
  defaultValue: string;
  serverValue: string;
  isEditable: boolean;
  /** Правила Laravel как есть — показываются подсказкой под полем. */
  rules: string;
}

export interface PteroStartupDto {
  /**
   * Команда запуска с уже подставленными переменными.
   *
   * ТОЛЬКО ДЛЯ ЧТЕНИЯ, и это ограничение самого Pterodactyl, а не панели:
   * Client API менять команду запуска не позволяет — она правится
   * администратором панели. Показываем, чтобы было видно, во что
   * складываются переменные.
   */
  startupCommand: string;
  /** Сырая команда с плейсхолдерами вида {{SERVER_JARFILE}}. */
  rawStartupCommand: string;
  /** Докер-образы, разрешённые egg. Ключ — подпись, значение — образ. */
  dockerImages: Record<string, string>;
  currentDockerImage: string | null;
  variables: PteroVariableDto[];
}

// ------------------------------------------------------------- Базы данных

export interface PteroDatabaseDto {
  id: string;
  name: string;
  username: string;
  host: { address: string; port: number };
  /** Маска адресов, с которых разрешено подключение. */
  connectionsFrom: string;
  maxConnections: number | null;
  /**
   * Пароль. Приходит ТОЛЬКО в ответе на создание и на явный запрос
   * креденшлов — в списке его нет, чтобы он не оседал в кэшах браузера при
   * каждом открытии вкладки.
   */
  password?: string;
}

// ----------------------------------------------------------------- Бэкапы

export interface PteroBackupDto {
  uuid: string;
  name: string;
  bytes: number;
  checksum: string | null;
  /** false — бэкап ещё делается либо не удался. */
  isSuccessful: boolean;
  /** Заблокированный нельзя удалить и он не вытесняется ротацией. */
  isLocked: boolean;
  createdAt: string;
  completedAt: string | null;
}

// ------------------------------------------------------------- Расписания

/** Что делает шаг расписания. Набор закрыт самим Pterodactyl. */
export const SCHEDULE_ACTIONS = ['command', 'power', 'backup'] as const;
export type ScheduleAction = (typeof SCHEDULE_ACTIONS)[number];

/** Допустимые значения payload у шага питания. */
export const SCHEDULE_POWER_ACTIONS = ['start', 'stop', 'restart', 'kill'] as const;

export interface PteroTaskDto {
  id: number;
  /** Порядок выполнения внутри расписания. */
  sequenceId: number;
  action: ScheduleAction;
  /** Команда — для command, сигнал — для power, пусто — для backup. */
  payload: string;
  /** Пауза перед шагом в секундах: даёт серверу время выключиться. */
  timeOffset: number;
  /** Продолжать ли, если шаг не удался. */
  continueOnFailure: boolean;
}

export interface PteroScheduleDto {
  id: number;
  name: string;
  cron: { minute: string; hour: string; dayOfMonth: string; month: string; dayOfWeek: string };
  isActive: boolean;
  /** true — выполняется прямо сейчас. */
  isProcessing: boolean;
  /** Пропускать запуск, если сервер выключен. */
  onlyWhenOnline: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  tasks: PteroTaskDto[];
}

/**
 * Готовые расписания под то, что заводят чаще всего.
 *
 * Cron знают не все, а «каждый день в 4 утра» понимают все. Поле для cron
 * при этом остаётся: пресеты — это ярлыки, а не замена.
 */
export const SCHEDULE_PRESETS: {
  id: string;
  /** Ключ подписи: список — константа, а язык известен только в интерфейсе. */
  labelKey: string;
  cron: { minute: string; hour: string; dayOfMonth: string; month: string; dayOfWeek: string };
}[] = [
  {
    id: 'daily-4am',
    labelKey: 'cron.daily4',
    cron: { minute: '0', hour: '4', dayOfMonth: '*', month: '*', dayOfWeek: '*' },
  },
  {
    id: 'daily-6am',
    labelKey: 'cron.daily6',
    cron: { minute: '0', hour: '6', dayOfMonth: '*', month: '*', dayOfWeek: '*' },
  },
  {
    id: 'every-6h',
    labelKey: 'cron.every6h',
    cron: { minute: '0', hour: '*/6', dayOfMonth: '*', month: '*', dayOfWeek: '*' },
  },
  {
    id: 'hourly',
    labelKey: 'cron.hourly',
    cron: { minute: '0', hour: '*', dayOfMonth: '*', month: '*', dayOfWeek: '*' },
  },
  {
    id: 'weekly-mon-5am',
    labelKey: 'cron.weeklyMon5',
    cron: { minute: '0', hour: '5', dayOfMonth: '*', month: '*', dayOfWeek: '1' },
  },
];

/**
 * Человеческая подпись расписания.
 *
 * Возвращает ключ перевода и значения к нему, а не готовую строку: сам
 * список пресетов — константа, живущая вне интерфейса, и языка она не знает.
 * null — описать не берёмся, интерфейс покажет само cron-выражение.
 */
export function describeCron(
  cron: PteroScheduleDto['cron'],
): { key: string; values?: Record<string, string> } | null {
  const preset = SCHEDULE_PRESETS.find(
    (p) =>
      p.cron.minute === cron.minute &&
      p.cron.hour === cron.hour &&
      p.cron.dayOfMonth === cron.dayOfMonth &&
      p.cron.dayOfWeek === cron.dayOfWeek,
  );
  if (preset) return { key: preset.labelKey };

  // Простой ежедневный случай описываем сами — он самый частый.
  if (
    cron.dayOfMonth === '*' &&
    cron.month === '*' &&
    cron.dayOfWeek === '*' &&
    /^\d+$/.test(cron.hour) &&
    /^\d+$/.test(cron.minute)
  ) {
    return {
      key: 'cron.dailyAt',
      values: { time: `${cron.hour.padStart(2, '0')}:${cron.minute.padStart(2, '0')}` },
    };
  }
  return null;
}
