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
    // Гильдии дают только плагин AurumGuilds: без него вкладка показывает,
    // чего не хватает, — так же, как вкладка инвентаря без companion.
    guilds: 'requires-plugin',
  },
  permissions: [
    {
      key: MINECRAFT_PERMISSIONS.playersView,
      description: 'Просмотр списка игроков: онлайн и всех, кто когда-либо заходил',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Без MODERATOR намеренно. Адрес — личные данные: по нему видно
      // провайдера, город и то, что два ника принадлежат одному человеку.
      // Модерации для кика, бана и разбора жалоб этого не нужно.
      key: MINECRAFT_PERMISSIONS.playerIps,
      description: 'Известные IP-адреса игрока (нужен плагин авторизации)',
      defaultRoles: ['ADMIN'],
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
      // Только ADMIN, без MODERATOR: полная очистка необратима — панель не
      // умеет вернуть стёртое, а из бэкапа мира это достаётся вместе со всем
      // остальным, что случилось с тех пор. Смотреть инвентарь модератору
      // по-прежнему можно.
      key: MINECRAFT_PERMISSIONS.inventoryEdit,
      description: 'Выдача предметов и очистка инвентаря игрока (нужен companion-плагин)',
      defaultRoles: ['ADMIN'],
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
      // Только ADMIN, без MODERATOR: выданный токен на двадцать минут даёт
      // доступ к чужому игровому аккаунту. Это ближе к смене пароля, чем к
      // кику, и раздавать его модераторам по умолчанию не стоит.
      key: MINECRAFT_PERMISSIONS.passwordReset,
      description: 'Сброс пароля игрока: выдача одноразового токена (AurumAuth)',
      defaultRoles: ['ADMIN'],
    },
    {
      key: MINECRAFT_PERMISSIONS.guildsView,
      description: 'Просмотр гильдий и их состава (нужен AurumGuilds)',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Роспуск необратим и уносит состав вместе с общаком, а передача
      // лидерства меняет, кто распоряжается чужими деньгами. Модератору по
      // умолчанию не даём: смотреть он может, вмешиваться — нет.
      key: MINECRAFT_PERMISSIONS.guildsManage,
      description: 'Роспуск гильдии, передача лидерства и исключение участника',
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
