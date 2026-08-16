import { MINECRAFT_PERMISSIONS, type MinecraftQuickCommandArg } from '@aurum/shared';

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
  template: string;
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
    label: 'Объявление',
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
    id: 'tp-spawn',
    label: 'Телепорт на спавн',
    description: 'Телепортирует игрока в точку спавна мира',
    template: 'spawnpoint {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: null,
    destructive: false,
  },
  {
    id: 'gamemode-survival',
    label: 'Режим выживания',
    description: 'Переводит игрока в режим выживания',
    template: 'gamemode survival {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [PLAYER_ARG],
    plugin: null,
    destructive: false,
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
    args: [
      {
        name: 'mode',
        label: 'Режим',
        required: true,
        placeholder: 'survival / creative / adventure / spectator',
      },
      PLAYER_ARG,
    ],
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
