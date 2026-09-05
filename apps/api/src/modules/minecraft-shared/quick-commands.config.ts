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
  /** Ключи словаря панели: подпись кнопки собирает браузер, а не сервер. */
  labelKey: string;
  descriptionKey: string;
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
  labelKey: 'mc.qc.arg.player',
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
  { value: 'survival', labelKey: 'mc.qc.mode.survival' },
  { value: 'creative', labelKey: 'mc.qc.mode.creative' },
  { value: 'adventure', labelKey: 'mc.qc.mode.adventure' },
  { value: 'spectator', labelKey: 'mc.qc.mode.spectator' },
];

const MODE_ARG: MinecraftQuickCommandArg = {
  name: 'mode',
  labelKey: 'mc.qc.arg.mode',
  required: true,
  options: GAMEMODE_OPTIONS,
};

/** Ванильные команды: работают на любом сервере, плагинов не требуют. */
const VANILLA: QuickCommandDefinition[] = [
  {
    id: 'save-all',
    labelKey: 'mc.qc.saveAll',
    descriptionKey: 'mc.qc.saveAll.d',
    template: 'save-all',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'day',
    labelKey: 'mc.qc.day',
    descriptionKey: 'mc.qc.day.d',
    template: 'time set day',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'weather-clear',
    labelKey: 'mc.qc.weather',
    descriptionKey: 'mc.qc.weather.d',
    template: 'weather clear',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
    plugin: null,
    destructive: false,
  },
  {
    id: 'broadcast',
    labelKey: 'mc.qc.broadcast',
    descriptionKey: 'mc.qc.broadcast.d',
    template: 'say {message}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      {
        name: 'message',
        labelKey: 'mc.qc.arg.message',
        required: true,
        placeholderKey: 'mc.qc.arg.message.p',
      },
    ],
    plugin: null,
    // Видят все игроки сервера — стоит переспросить.
    destructive: true,
  },
  {
    id: 'title-announce',
    labelKey: 'mc.qc.title',
    descriptionKey: 'mc.qc.title.d',
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
        labelKey: 'mc.qc.arg.message',
        required: true,
        placeholderKey: 'mc.qc.arg.message.p',
        escape: 'json',
      },
      {
        name: 'subtitle',
        labelKey: 'mc.qc.arg.subtitle',
        required: false,
        placeholderKey: 'mc.qc.arg.subtitle.p',
        escape: 'json',
      },
    ],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-gamemode',
    labelKey: 'mc.qc.gamemode',
    descriptionKey: 'mc.qc.gamemode.d',
    template: 'gamemode {mode} {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [MODE_ARG, PLAYER_ARG],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-kill',
    labelKey: 'mc.qc.kill',
    descriptionKey: 'mc.qc.kill.d',
    template: 'kill {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: null,
    destructive: true,
  },
  {
    id: 'vanilla-tp',
    labelKey: 'mc.qc.tp',
    descriptionKey: 'mc.qc.tp.d',
    template: 'tp {player} {target}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'player', labelKey: 'mc.qc.arg.who', required: true, placeholder: 'Steve' },
      { name: 'target', labelKey: 'mc.qc.arg.toWhom', required: true, placeholder: 'Alex' },
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
    labelKey: 'mc.qc.heal',
    descriptionKey: 'mc.qc.heal.d',
    template: 'heal {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    destructive: false,
  },
  {
    id: 'ess-god',
    labelKey: 'mc.qc.god',
    descriptionKey: 'mc.qc.god.d',
    template: 'god {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    // Меняет правила игры для конкретного человека — спрашиваем.
    destructive: true,
  },
  {
    id: 'ess-fly',
    labelKey: 'mc.qc.fly',
    descriptionKey: 'mc.qc.fly.d',
    template: 'fly {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: 'Essentials',
    destructive: true,
  },
  {
    id: 'ess-kit',
    labelKey: 'mc.qc.kit',
    descriptionKey: 'mc.qc.kit.d',
    template: 'kit {kit} {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'kit', labelKey: 'mc.qc.arg.kit', required: true, placeholder: 'starter' },
      PLAYER_ARG,
    ],
    plugin: 'Essentials',
    destructive: true,
  },
  /*
   * ЗДЕСЬ НАМЕРЕННО НЕТ gamemode И tp.
   *
   * Раньше они были продублированы: ванильные и «EssentialsX» с ровно теми же
   * шаблонами `gamemode {mode} {player}` и `tp {player} {target}`. На сервере
   * с EssentialsX человек видел две одинаковые кнопки с одинаковой подписью и
   * гадал, чем они отличаются, — а не отличались они ничем.
   *
   * EssentialsX перехватывает /gamemode и /tp прозрачно: ванильный вариант на
   * нём работает так же. Значит правильный вариант — ванильный: он есть на
   * любом сервере и не зависит от того, стоит ли плагин.
   *
   * Сюда стоит добавлять только то, чего в ванили НЕТ (heal, god, fly, kit).
   */
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
