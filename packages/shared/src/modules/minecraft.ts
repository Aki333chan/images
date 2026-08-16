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
  /**
   * Плагин, командой которого является действие. `null` — ванильная команда,
   * работает везде. Иначе кнопка показывается, только если этот плагин
   * действительно установлен на сервере (см. MinecraftPluginsDto).
   */
  plugin: string | null;
  /** Показать подтверждение перед запуском: действие заметно для игрока. */
  destructive: boolean;
}

/** Плагины, о которых панель знает и умеет что-то полезное. */
export interface KnownPluginDto {
  /** Имя, под которым плагин регистрируется в Bukkit. */
  id: string;
  /** Человеческое название — оно нередко другое, чем id. */
  displayName: string;
  /** Что панель умеет, если плагин установлен. */
  gives: string;
  installed: boolean;
  /** Версия с сервера; null, если не установлен. */
  version: string | null;
}

export interface MinecraftPluginsDto {
  /**
   * false — companion-плагин не настроен, список получить неоткуда.
   * Тогда known заполнен, но installed везде false, и это честно показано.
   */
  available: boolean;
  reason?: string;
  /** Всё, что стоит на сервере. Пусто, если available = false. */
  installed: { name: string; version: string; enabled: boolean }[];
  /** Плагины, поддерживаемые панелью, с отметкой «есть/нет». */
  known: KnownPluginDto[];
}

/** Права игрока, как их отдаёт LuckPerms. */
export interface MinecraftPermissionsDto {
  available: boolean;
  /** Причина недоступности: нет companion-плагина либо нет LuckPerms. */
  reason?: string;
  code?: 'no-companion' | 'requires-luckperms' | 'error';
  primaryGroup?: string;
  groups?: string[];
  permissions?: { permission: string; value: boolean }[];
}

/** Одно изменение прав: панель шлёт их по одному, чтобы аудит был читаемым. */
export interface MinecraftPermissionChangeDto {
  kind: 'group' | 'permission';
  key: string;
  value?: boolean;
  remove?: boolean;
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

/**
 * Сторонние плагины, с которыми панель умеет работать.
 *
 * id — имя, под которым плагин регистрируется в Bukkit, и оно совпадает не
 * всегда: EssentialsX зовётся Essentials (наследие старого Essentials),
 * InvSee++ — InvSeePlusPlus. Сверка идёт именно по id, поэтому менять их
 * нельзя, не сверившись с plugin.yml соответствующего плагина.
 */
export const KNOWN_PLUGINS = [
  {
    id: 'LuckPerms',
    displayName: 'LuckPerms',
    gives: 'вкладка «Права» у игрока: группы и права через API плагина',
  },
  {
    id: 'Essentials',
    displayName: 'EssentialsX',
    gives: 'быстрые действия: heal, god, fly, kit, режим игры, телепорт',
  },
  {
    id: 'InvSeePlusPlus',
    displayName: 'InvSee++',
    gives: 'инвентари игроков, которых нет в сети',
  },
] as const;

export type KnownPluginId = (typeof KNOWN_PLUGINS)[number]['id'];

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
  permissionsView: 'minecraft.permissions.view',
  permissionsEdit: 'minecraft.permissions.edit',
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
