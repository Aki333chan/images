/**
 * Контракт модуля Minecraft (Java Edition) между apps/api и apps/web.
 * ВАЖНО: здесь намеренно нет ни RCON-пароля, ни хоста, ни адреса
 * companion-плагина — эти данные никогда не покидают бэкенд.
 */

export interface MinecraftPlayerDto {
  name: string;
  /** UUID приходит только от companion-плагина; по RCON `list` его нет. */
  uuid: string | null;
  /** Пинг в мс — только от companion-плагина. */
  ping: number | null;
}

export interface MinecraftPlayersResponse {
  players: MinecraftPlayerDto[];
  online: number;
  max: number | null;
  /** Откуда получены данные: разбор ответа RCON или структурированный плагин. */
  source: 'rcon' | 'companion';
}

export interface MinecraftBanDto {
  id: string;
  serverId: string;
  playerName: string;
  playerUuid: string | null;
  reason: string;
  /** null — бан навсегда. */
  expiresAt: string | null;
  createdAt: string;
  createdByName: string | null;
  pardonedAt: string | null;
  pardonedByName: string | null;
  /** false, если бан снят или истёк срок. */
  active: boolean;
}

export interface MinecraftWhitelistResponse {
  players: string[];
}

export interface MinecraftQuickCommandArg {
  name: string;
  label: string;
  required: boolean;
  placeholder?: string;
}

export interface MinecraftQuickCommandDto {
  id: string;
  label: string;
  description: string;
  /** Право, необходимое для запуска (кроме него всегда нужен доступ к серверу). */
  permission: string;
  args: MinecraftQuickCommandArg[];
}

export interface MinecraftCommandResultDto {
  /** Ответ сервера на команду (может быть пустым). */
  output: string;
}

export interface MinecraftInventoryItemDto {
  slot: number;
  id: string;
  count: number;
  displayName: string | null;
}

export interface MinecraftInventoryResponse {
  available: boolean;
  /** Причина недоступности, если available = false. */
  reason?: string;
  docsUrl?: string;
  player?: string;
  /** Основной инвентарь: 36 слотов (0-8 — хотбар). */
  items?: MinecraftInventoryItemDto[];
  /** Броня: 4 слота. */
  armor?: MinecraftInventoryItemDto[];
  offhand?: MinecraftInventoryItemDto | null;
}

/** Статус настройки модуля. Секреты сюда не попадают — только флаги. */
export interface MinecraftConfigStatusDto {
  /** Настроен ли RCON (хост/порт/пароль сохранены). */
  rconConfigured: boolean;
  /** Настроен ли companion-плагин (нужен для инвентаря и UUID/пинга). */
  companionConfigured: boolean;
  /** Последняя успешная RCON-команда, ISO-строка. */
  lastSeenAt: string | null;
}

export const MINECRAFT_PERMISSIONS = {
  playersView: 'minecraft.players.view',
  kick: 'minecraft.kick',
  ban: 'minecraft.ban',
  pardon: 'minecraft.ban.pardon',
  whitelist: 'minecraft.whitelist',
  quickCommands: 'minecraft.quick-commands',
  commandRaw: 'minecraft.command.raw',
  inventoryView: 'minecraft.inventory.view',
  configure: 'minecraft.configure',
} as const;
