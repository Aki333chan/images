import { MINECRAFT_PERMISSIONS, type GameModuleManifest } from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { MinecraftModule } from './minecraft.module';

/**
 * Модуль Minecraft (Java Edition): Paper / Spigot / Vanilla под Pterodactyl.
 *
 * console берётся из ядра (WebSocket Pterodactyl) и здесь не дублируется:
 * модуль лишь объявляет возможность, а вкладку рендерит общий компонент.
 * inventory помечен 'requires-plugin' — работает только с companion-плагином.
 */
export const minecraftManifest: GameModuleManifest = {
  id: 'minecraft',
  displayName: 'Minecraft (Java)',
  capabilities: {
    console: true,
    playerList: true,
    banKick: true,
    whitelist: true,
    inventory: 'requires-plugin',
    quickCommands: true,
    tickets: true,
  },
  permissions: [
    {
      key: MINECRAFT_PERMISSIONS.playersView,
      description: 'Просмотр списка игроков онлайн',
      defaultRoles: ['ADMIN', 'MODERATOR', 'VIEWER'],
    },
    {
      key: MINECRAFT_PERMISSIONS.kick,
      description: 'Кик игрока',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: MINECRAFT_PERMISSIONS.ban,
      description: 'Бан игрока и просмотр списка банов',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: MINECRAFT_PERMISSIONS.pardon,
      description: 'Снятие бана',
      defaultRoles: ['ADMIN'],
    },
    {
      key: MINECRAFT_PERMISSIONS.whitelist,
      description: 'Управление белым списком',
      defaultRoles: ['ADMIN'],
    },
    {
      key: MINECRAFT_PERMISSIONS.quickCommands,
      description: 'Запуск быстрых команд',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: MINECRAFT_PERMISSIONS.commandRaw,
      description: 'Произвольная RCON-команда',
      defaultRoles: ['ADMIN'],
    },
    {
      key: MINECRAFT_PERMISSIONS.inventoryView,
      description: 'Просмотр инвентаря игрока (нужен companion-плагин)',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Пустой список — право есть только у ГМ: здесь задаются RCON-пароль
      // и адрес companion-плагина.
      key: MINECRAFT_PERMISSIONS.configure,
      description: 'Настройка подключения к игровому серверу (RCON, плагин)',
      defaultRoles: [],
    },
  ],
};

export const minecraftModule: BackendGameModule = {
  manifest: minecraftManifest,
  nestModule: MinecraftModule,
};
