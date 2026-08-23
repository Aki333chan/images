import { MINECRAFT_PERMISSIONS, PLUGIN_PERMISSIONS, type GameModuleManifest } from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { MinecraftModule } from './minecraft.module';

/**
 * Модуль Minecraft (Paper): Paper / Spigot / Vanilla под Pterodactyl.
 *
 * console берётся из ядра (WebSocket Pterodactyl) и здесь не дублируется:
 * модуль лишь объявляет возможность, а вкладку рендерит общий компонент.
 * inventory помечен 'requires-plugin' — работает только с companion-плагином.
 */
export const minecraftManifest: GameModuleManifest = {
  id: 'minecraft',
  displayName: 'Minecraft (Paper)',
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
      defaultRoles: ['ADMIN', 'MODERATOR'],
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
      key: MINECRAFT_PERMISSIONS.permissionsView,
      description: 'Просмотр прав игрока (нужны companion-плагин и LuckPerms)',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Выдача прав — это раздача власти на сервере, модератору её не даём.
      key: MINECRAFT_PERMISSIONS.permissionsEdit,
      description: 'Изменение групп и прав игрока через LuckPerms',
      defaultRoles: ['ADMIN'],
    },
    {
      key: MINECRAFT_PERMISSIONS.economyView,
      description: 'Просмотр баланса игроков и экономики сервера (нужен Vault)',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Начисления и списания — это выдача ценностей, отдельное право от
      // просмотра: модератор видит баланс, но не правит его.
      key: MINECRAFT_PERMISSIONS.economyEdit,
      description: 'Начисление и списание валюты игрокам через Vault',
      defaultRoles: ['ADMIN'],
    },
    {
      // Установка плагина — это запуск произвольного кода на игровом сервере:
      // плагин Bukkit это обычная Java-программа без песочницы. Поэтому
      // Модератору не даём даже смотреть маркет — ставить он всё равно не
      // должен, а половинчатый доступ только запутывает.
      key: PLUGIN_PERMISSIONS.install,
      description: 'Маркет плагинов и установка плагинов на сервер',
      defaultRoles: ['ADMIN'],
    },
    {
      key: PLUGIN_PERMISSIONS.manage,
      description: 'Включение, выключение и удаление установленных плагинов',
      defaultRoles: ['ADMIN'],
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
