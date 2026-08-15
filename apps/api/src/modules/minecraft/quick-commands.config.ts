import { MINECRAFT_PERMISSIONS, type MinecraftQuickCommandArg } from '@aurum/shared';

/**
 * Быстрые команды: label → шаблон RCON-команды.
 * Плейсхолдеры вида {name} подставляются из аргументов; каждое значение
 * проходит sanitizeCommandArgument, а ники дополнительно валидируются.
 *
 * Список правится здесь — это конфигурация, а не данные:
 * он одинаков для всех Minecraft-серверов и версионируется вместе с кодом.
 */
export interface QuickCommandDefinition {
  id: string;
  label: string;
  description: string;
  template: string;
  permission: string;
  args: MinecraftQuickCommandArg[];
}

export const MINECRAFT_QUICK_COMMANDS: QuickCommandDefinition[] = [
  {
    id: 'save-all',
    label: 'Сохранить мир',
    description: 'Принудительно сохраняет мир на диск (save-all)',
    template: 'save-all',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
  },
  {
    id: 'day',
    label: 'Сделать день',
    description: 'Устанавливает время в день',
    template: 'time set day',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
  },
  {
    id: 'weather-clear',
    label: 'Ясная погода',
    description: 'Разгоняет дождь и грозу',
    template: 'weather clear',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [],
  },
  {
    id: 'broadcast',
    label: 'Объявление',
    description: 'Отправляет сообщение всем игрокам в чат',
    template: 'say {message}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [
      { name: 'message', label: 'Текст объявления', required: true, placeholder: 'Рестарт через 5 минут' },
    ],
  },
  {
    id: 'tp-spawn',
    label: 'Телепорт на спавн',
    description: 'Телепортирует игрока в точку спавна мира',
    template: 'spawnpoint {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [{ name: 'player', label: 'Ник игрока', required: true, placeholder: 'Steve' }],
  },
  {
    id: 'gamemode-survival',
    label: 'Режим выживания',
    description: 'Переводит игрока в режим выживания',
    template: 'gamemode survival {player}',
    permission: MINECRAFT_PERMISSIONS.quickCommands,
    args: [{ name: 'player', label: 'Ник игрока', required: true, placeholder: 'Steve' }],
  },
];

/** Имена аргументов, которые обязаны быть валидным ником Minecraft. */
export const NICKNAME_ARG_NAMES = new Set(['player', 'target', 'nick']);
