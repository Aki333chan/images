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
  /** Здоровье и позиция — только от companion-плагина. */
  health: number | null;
  maxHealth: number | null;
  world: string | null;
  position: { x: number; y: number; z: number } | null;
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
  /** Зачарования: ключ (напр. minecraft:sharpness) -> уровень. */
  enchantments: Record<string, number>;
  lore: string[];
}

/**
 * Почему инвентарь недоступен. Машиночитаемый код нужен, чтобы интерфейс не
 * путал «плагин не установлен» (показываем инструкцию) с «игрок сейчас офлайн»
 * (обычное сообщение).
 */
export type MinecraftInventoryUnavailableCode =
  'no-plugin' | 'player-offline' | 'plugin-unreachable';

/** Доступен ли инвентарь на этом сервере — без секретов, для любой роли с правом просмотра. */
export interface MinecraftInventoryStatusDto {
  companionConfigured: boolean;
  docsUrl: string;
}

export interface MinecraftInventoryResponse {
  available: boolean;
  code?: MinecraftInventoryUnavailableCode;
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

/**
 * Производительность игрового сервера: TPS и время тика.
 * Команды `tps` и `mspt` есть в Paper/Spigot; на ванильном сервере их нет,
 * поэтому поля обнуляются, а `supported` показывает, что именно недоступно.
 */
export interface MinecraftPerformanceDto {
  tps1m: number | null;
  tps5m: number | null;
  tps15m: number | null;
  /** Среднее время тика, мс. Норма — меньше 50. */
  mspt: number | null;
  /** false — сервер не знает команду (не Paper/Spigot). */
  tpsSupported: boolean;
  msptSupported: boolean;
}
