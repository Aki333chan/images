import {
  MINECRAFT_PERMISSIONS,
  type MinecraftCommandArgKind,
  type MinecraftConsoleCommandDto,
  type MinecraftQuickCommandArg,
} from '@aurum/shared';

/**
 * Каталог быстрых действий: label → шаблон RCON-команды.
 *
 * Плейсхолдеры вида {name} подставляются из аргументов; каждое значение
 * проходит sanitizeCommandArgument, а ники дополнительно валидируются.
 *
 * Список правится здесь — это конфигурация, а не данные: он одинаков для всех
 * Minecraft-серверов и версионируется вместе с кодом.
 *
 * ГРУППИРОВКА ПО ПЛАГИНУ. Поле `plugin` — имя, под которым плагин
 * регистрируется в Bukkit, либо null для ванильных команд. Панель показывает
 * действие, только если такой плагин реально стоит на конкретном сервере:
 * кнопка «Heal», ведущая в «Unknown command», хуже, чем её отсутствие.
 *
 * ВАЖНО ПРО ИМЯ ESSENTIALSX. В Bukkit он зарегистрирован как «Essentials»,
 * а не «EssentialsX» — имя осталось от заброшенного предшественника, форк
 * его сохранил ради совместимости. Проверено по plugin.yml EssentialsX.
 * Если написать здесь «EssentialsX», кнопки не появятся никогда.
 */
export interface QuickCommandDefinition {
  id: string;
  label: string;
  description: string;
  /**
   * Одна команда или несколько, выполняемых по порядку.
   *
   * Несколько нужны там, где эффект складывается из пары команд: у /title
   * подзаголовок показывается только вместе с заголовком и только если
   * отправлен раньше него. Склеить их в одну строку нельзя — RCON принимает
   * ровно одну команду за запрос.
   */
  template: string | string[];
  permission: string;
  args: MinecraftQuickCommandArg[];
  /** Имя плагина в Bukkit; null — ванильная команда. */
  plugin: string | null;
  /** Заметное для игрока действие — панель спросит подтверждение. */
  destructive: boolean;
}

const PLAYER_ARG: MinecraftQuickCommandArg = {
  name: 'player',
  label: 'Ник игрока',
  required: true,
  placeholder: 'Steve',
};

/**
 * Режимы игры. Значения — то, что понимает команда gamemode; подписи —
 * то, как их называют по-русски.
 *
 * Закрытым списком, а не текстовым полем: вариантов четыре, они не меняются,
 * а опечатка в «adventure» даёт «Unknown game mode» вместо действия.
 */
const GAMEMODE_OPTIONS = [
  { value: 'survival', label: 'Выживание' },
  { value: 'creative', label: 'Творческий' },
  { value: 'adventure', label: 'Приключение' },
  { value: 'spectator', label: 'Наблюдатель' },
];

const MODE_ARG: MinecraftQuickCommandArg = {
  name: 'mode',
  label: 'Режим',
  required: true,
  options: GAMEMODE_OPTIONS,
};

/** Ванильные команды: работают на любом сервере, плагинов не требуют. */
const VANILLA: QuickCommandDefinition[] = [
  {
    id: 'save-all',
    label: 'Сохранить мир',
    description: 'Принудительно сохраняет мир на диск (save-all)',
    template: 'save-all',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'day',
    label: 'Сделать день',
    description: 'Устанавливает время в день',
    template: 'time set day',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'weather-clear',
    label: 'Ясная погода',
    description: 'Разгоняет дождь и грозу',
    template: 'weather clear',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'broadcast',
    label: 'Сообщение в чат',
    description: 'Отправляет сообщение всем игрокам в чат',
    template: 'say {message}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      {
        name: 'message',
        label: 'Текст объявления',
        required: true,
        placeholder: 'Рестарт через 5 минут',
      },
    ],
    plugin: null,
    // Видят все игроки сервера — стоит переспросить.
    destructive: true,
  },
  {
    id: 'title-announce',
    label: 'Объявление',
    description: 'Крупная надпись по центру экрана у всех игроков',
    // Текстовый компонент, а не голая строка: только так задаются цвет и
    // начертание. Значения подставляются с JSON-экранированием — иначе
    // кавычка в тексте разорвала бы литерал.
    //
    // Подзаголовок в vanilla показывается ТОЛЬКО вместе с заголовком и
    // только если отправлен раньше него, поэтому subtitle идёт первой
    // командой. Пустой — плейсхолдер вычищается, и остаётся один заголовок.
    template: [
      // Подзаголовок первым: строка с незаполненным {subtitle} отбрасывается
      // целиком, и тогда остаётся только заголовок.
      'title @a subtitle {"text":"{subtitle}","color":"yellow"}',
      'title @a title {"text":"{message}","color":"gold","bold":true}',
    ],
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      {
        name: 'message',
        label: 'Текст объявления',
        required: true,
        placeholder: 'Рестарт через 5 минут',
        escape: 'json',
      },
      {
        name: 'subtitle',
        label: 'Подзаголовок (необязательно)',
        required: false,
        placeholder: 'Сохраните постройки',
        escape: 'json',
      },
    ],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-gamemode',
    label: 'Сменить режим игры',
    description: 'Ванильная команда gamemode — работает без плагинов',
    template: 'gamemode {mode} {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [MODE_ARG, PLAYER_ARG],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-kill',
    label: 'Убить игрока',
    description: 'Ванильная команда kill',
    template: 'kill {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-tp',
    label: 'Телепорт к игроку',
    description: 'Ванильная команда tp — работает без плагинов',
    template: 'tp {player} {target}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'player', label: 'Кого телепортировать', required: true, placeholder: 'Steve' },
      { name: 'target', label: 'К кому', required: true, placeholder: 'Alex' },
    ],
    plugin: null,
    destructive: true,
  },
];

/**
 * EssentialsX.
 *
 * Все действия делаются обычными командами через RCON — API самого
 * EssentialsX (IEssentials) для них не нужен, а лишняя зависимость на сборке
 * companion-плагина стоила бы дороже пользы.
 *
 * heal/god/fly у EssentialsX — переключатели: команда без аргумента состояния
 * меняет его на противоположное. Поэтому в панели это одна кнопка «переключить»,
 * а не пара «включить/выключить»: подсмотреть текущее состояние по RCON нельзя,
 * и пара кнопок врала бы о том, что происходит.
 */
const ESSENTIALS_X: QuickCommandDefinition[] = [
  {
    id: 'ess-heal',
    label: 'Вылечить',
    description: 'EssentialsX: восстанавливает здоровье и сытость (heal)',
    template: 'heal {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    destructive: false,
  },
  {
    id: 'ess-god',
    label: 'Бессмертие',
    description: 'EssentialsX: переключает режим неуязвимости (god)',
    template: 'god {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    // Меняет правила игры для конкретного человека — спрашиваем.
    destructive: true,
  },
  {
    id: 'ess-fly',
    label: 'Полёт',
    description: 'EssentialsX: переключает возможность летать (fly)',
    template: 'fly {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    destructive: true,
  },
  {
    id: 'ess-kit',
    label: 'Выдать кит',
    description: 'EssentialsX: выдаёт игроку набор предметов (kit)',
    template: 'kit {kit} {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'kit', label: 'Название кита', required: true, placeholder: 'starter' },
      PLAYER_ARG,
    ],
    plugin: 'Essentials',
    destructive: true,
  },
  {
    id: 'ess-gamemode',
    label: 'Сменить режим игры',
    description: 'EssentialsX: survival, creative, adventure или spectator',
    template: 'gamemode {mode} {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [MODE_ARG, PLAYER_ARG],
    plugin: 'Essentials',
    destructive: true,
  },
  {
    id: 'ess-tp-to-player',
    label: 'Телепорт к игроку',
    description: 'EssentialsX: переносит игрока к другому игроку (tp)',
    template: 'tp {player} {target}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'player', label: 'Кого телепортировать', required: true, placeholder: 'Steve' },
      { name: 'target', label: 'К кому', required: true, placeholder: 'Alex' },
    ],
    plugin: 'Essentials',
    destructive: true,
  },
];

export const MINECRAFT_QUICK_COMMANDS: QuickCommandDefinition[] = [...VANILLA, ...ESSENTIALS_X];

/** Имена аргументов, которые обязаны быть валидным ником Minecraft. */
export const NICKNAME_ARG_NAMES = new Set(['player', 'target', 'nick']);

/**
 * Имена команд каталога — для автодополнения в консоли.
 *
 * Второго списка команд плагинов нет и быть не должно: он бы разъехался с
 * каталогом при первом же добавлении кнопки. Поэтому имена выводятся прямо
 * из шаблонов — первое слово шаблона и есть команда, — а виды аргументов
 * восстанавливаются по объявленным args: где аргумент назван ником
 * (NICKNAME_ARG_NAMES), панель подставит игроков онлайн.
 *
 * Ванильные команды каталога отбрасываются: они уже есть в
 * MINECRAFT_SERVER_COMMANDS, причём с более полным описанием аргументов.
 */
export function catalogConsoleCommands(): MinecraftConsoleCommandDto[] {
  const byName = new Map<string, MinecraftConsoleCommandDto>();

  for (const definition of MINECRAFT_QUICK_COMMANDS) {
    if (definition.plugin === null) continue;
    const lines = Array.isArray(definition.template) ? definition.template : [definition.template];
    for (const line of lines) {
      const name = line.trim().split(/\s+/)[0];
      // Плейсхолдер на месте команды означал бы шаблон вида «{cmd} …» —
      // такого в каталоге нет, но на всякий случай пропускаем.
      if (!name || name.startsWith('{')) continue;
      if (byName.has(name)) continue;
      byName.set(name, {
        name,
        args: argKindsOf(line, definition.args),
        plugin: definition.plugin,
      });
    }
  }

  return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/** Виды аргументов по позициям шаблона: {player} → ник, остальное — значение. */
function argKindsOf(
  template: string,
  args: QuickCommandDefinition['args'],
): MinecraftCommandArgKind[] {
  const declared = new Map(args.map((a) => [a.name, a]));
  return template
    .trim()
    .split(/\s+/)
    .slice(1)
    .map((token) => {
      const placeholder = /^\{(\w+)\}$/.exec(token)?.[1];
      if (!placeholder || !declared.has(placeholder)) return 'value' as const;
      return NICKNAME_ARG_NAMES.has(placeholder) ? ('player' as const) : ('value' as const);
    });
}
