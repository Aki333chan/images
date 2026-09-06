import type { ComponentType } from 'react';
import {
  MINECRAFT_FORGE_PERMISSIONS,
  MINECRAFT_NEOFORGE_PERMISSIONS,
  MINECRAFT_PERMISSIONS,
  PALWORLD_PERMISSIONS,
  SEVENDAYS_PERMISSIONS,
  type CapabilityState,
  type ModuleCapability,
} from '@aurum/shared';
import { ConsoleTab } from '../components/ConsoleTab';
import {
  DummyPlayersTab,
  DummyQuickCommandsTab,
  DummyTicketsTab,
} from './test-dummy/tabs';
import {
  MinecraftBansTab,
  MinecraftPlayersTab,
  MinecraftQuickCommandsWidget,
  MinecraftWhitelistTab,
} from './minecraft/tabs';
import { MinecraftGuildsTab } from './minecraft/GuildsTab';
import { MinecraftSettingsTab } from './minecraft/SettingsTab';
import {
  PalworldBansTab,
  PalworldPlayersTab,
  PalworldQuickActionsWidget,
} from './palworld/tabs';
import { PalworldSettingsTab } from './palworld/SettingsTab';
import {
  SevenDaysBansTab,
  SevenDaysPlayersTab,
  SevenDaysQuickActionsWidget,
  SevenDaysWhitelistTab,
} from './sevendays/tabs';
import { SevenDaysSettingsTab } from './sevendays/SettingsTab';

export interface ModuleTabProps {
  serverId: string;
  moduleId: string;
  /** Состояние capability из манифеста: true или 'requires-plugin'. */
  capabilityState: CapabilityState;
}

export interface CapabilityTab {
  /**
   * Ключ словаря, а не подпись.
   *
   * Реестр собирается один раз при загрузке модуля, а язык у каждого свой и
   * меняется на ходу; готовая подпись здесь застыла бы на языке того, кто
   * открыл вкладку первым.
   */
  labelKey: string;
  /** Право, необходимое для показа вкладки (null — достаточно servers.view). */
  permission: string | null;
  component: ComponentType<ModuleTabProps>;
}

export interface ModuleFrontend {
  tabs: Partial<Record<ModuleCapability, CapabilityTab>>;
  /** Блок, который рисуется на дашборде сервера над вкладками. */
  dashboard?: { permission: string | null; component: ComponentType<ModuleTabProps> };
  /**
   * Экран настроек подключения модуля. Отдельно от tabs: настройки — не
   * capability из манифеста, а свойство самого модуля, и показываются они
   * по праву настройки, а не по праву просмотра данных.
   */
  settings?: { labelKey: string; permission: string; component: ComponentType<ModuleTabProps> };
}

/**
 * Вкладки, которые ядро предоставляет само. Модулю достаточно объявить
 * capability в манифесте — своей реализации он не пишет (консоль идёт
 * через WebSocket Pterodactyl, а не через RCON модуля).
 */
const CORE_TABS: Partial<Record<ModuleCapability, CapabilityTab>> = {
  console: { labelKey: 'tab.console', permission: 'servers.view', component: ConsoleTab },
};

/**
 * Фронтенд-реестр модулей: id модуля -> вкладки по capability и виджет дашборда.
 * Вкладки на ServerDetail = capabilities манифеста ∩ (вкладки модуля ∪ вкладки
 * ядра) ∩ права пользователя.
 */
export const MODULE_REGISTRY: Record<string, ModuleFrontend> = {
  minecraft: {
    tabs: {
      playerList: {
        labelKey: 'tab.players',
        permission: 'minecraft.players.view',
        component: MinecraftPlayersTab,
      },
      banKick: { labelKey: 'tab.bans', permission: 'minecraft.ban', component: MinecraftBansTab },
      whitelist: {
        labelKey: 'tab.whitelist',
        permission: 'minecraft.whitelist',
        component: MinecraftWhitelistTab,
      },
      // Только у Paper: гильдии даёт плагин Bukkit, которого на загрузчиках
      // модов не существует — там эта capability в манифесте не объявлена.
      guilds: {
        labelKey: 'tab.guilds',
        permission: 'minecraft.guilds.view',
        component: MinecraftGuildsTab,
      },
    },
    dashboard: {
      permission: 'minecraft.quick-commands',
      component: MinecraftQuickCommandsWidget,
    },
    settings: {
      labelKey: 'tab.settings',
      permission: MINECRAFT_PERMISSIONS.configure,
      component: MinecraftSettingsTab,
    },
  },
  /**
   * Minecraft на загрузчиках модов — Forge и NeoForge.
   *
   * ТЕ ЖЕ САМЫЕ КОМПОНЕНТЫ, что и у Paper, а не их копии: за вкладками стоят
   * команды самого сервера Minecraft (`list`, `kick`, `ban`, `whitelist`),
   * одинаковые на любом ядре и загрузчике. Отличаются только адрес API —
   * компоненты берут его из moduleId — и ключи прав.
   *
   * ЗАПИСИ ДВЕ, А НЕ ОДНА, И ЭТО ПРИНЦИПИАЛЬНО. Forge и NeoForge — разные
   * загрузчики: с Minecraft 1.20.2 NeoForge переименовал внутренние пакеты, и
   * мод одного на другом не загружается вовсе. Раздельные права позволяют
   * пустить модератора на один сервер, не пуская на другой; общая запись
   * лишила бы ГМ этой возможности молча.
   *
   * Вкладок инвентаря и тикетов здесь нет: за ними стоит companion-плагин
   * Bukkit, которого на загрузчиках модов не существует (см. манифесты).
   */
  'minecraft-forge': {
    tabs: {
      playerList: {
        labelKey: 'tab.players',
        permission: MINECRAFT_FORGE_PERMISSIONS.playersView,
        component: MinecraftPlayersTab,
      },
      banKick: {
        labelKey: 'tab.bans',
        permission: MINECRAFT_FORGE_PERMISSIONS.ban,
        component: MinecraftBansTab,
      },
      whitelist: {
        labelKey: 'tab.whitelist',
        permission: MINECRAFT_FORGE_PERMISSIONS.whitelist,
        component: MinecraftWhitelistTab,
      },
    },
    dashboard: {
      permission: MINECRAFT_FORGE_PERMISSIONS.quickCommands,
      component: MinecraftQuickCommandsWidget,
    },
    settings: {
      labelKey: 'tab.settings',
      permission: MINECRAFT_FORGE_PERMISSIONS.configure,
      component: MinecraftSettingsTab,
    },
  },
  'minecraft-neoforge': {
    tabs: {
      playerList: {
        labelKey: 'tab.players',
        permission: MINECRAFT_NEOFORGE_PERMISSIONS.playersView,
        component: MinecraftPlayersTab,
      },
      banKick: {
        labelKey: 'tab.bans',
        permission: MINECRAFT_NEOFORGE_PERMISSIONS.ban,
        component: MinecraftBansTab,
      },
      whitelist: {
        labelKey: 'tab.whitelist',
        permission: MINECRAFT_NEOFORGE_PERMISSIONS.whitelist,
        component: MinecraftWhitelistTab,
      },
    },
    dashboard: {
      permission: MINECRAFT_NEOFORGE_PERMISSIONS.quickCommands,
      component: MinecraftQuickCommandsWidget,
    },
    settings: {
      labelKey: 'tab.settings',
      permission: MINECRAFT_NEOFORGE_PERMISSIONS.configure,
      component: MinecraftSettingsTab,
    },
  },
  /**
   * Palworld. Вкладок две — по числу реальных возможностей REST API игры.
   * Консоль берётся из ядра (CORE_TABS), своей реализации модуль не пишет.
   * Whitelist, инвентаря и тикетов у Palworld нет вовсе — см. манифест.
   */
  palworld: {
    tabs: {
      playerList: {
        labelKey: 'tab.players',
        permission: PALWORLD_PERMISSIONS.playersView,
        component: PalworldPlayersTab,
      },
      banKick: { labelKey: 'tab.bans', permission: PALWORLD_PERMISSIONS.ban, component: PalworldBansTab },
    },
    dashboard: {
      // Право проверяет сам виджет: действия под разными правами, и одного
      // ключа на весь блок не хватает.
      permission: null,
      component: PalworldQuickActionsWidget,
    },
    settings: {
      labelKey: 'tab.settings',
      permission: PALWORLD_PERMISSIONS.configure,
      component: PalworldSettingsTab,
    },
  },
  /**
   * 7 Days to Die. Вкладок три — по числу возможностей ванильного сервера:
   * игроки, баны и белый список. Консоль берётся из ядра (CORE_TABS).
   * Инвентарь и тикеты требовали бы серверного мода — см. манифест.
   */
  sevendays: {
    tabs: {
      playerList: {
        labelKey: 'tab.players',
        permission: SEVENDAYS_PERMISSIONS.playersView,
        component: SevenDaysPlayersTab,
      },
      banKick: {
        labelKey: 'tab.bans',
        permission: SEVENDAYS_PERMISSIONS.ban,
        component: SevenDaysBansTab,
      },
      whitelist: {
        labelKey: 'tab.whitelist',
        permission: SEVENDAYS_PERMISSIONS.whitelist,
        component: SevenDaysWhitelistTab,
      },
    },
    dashboard: {
      // Право проверяет сам виджет: действия под разными правами, и одного
      // ключа на весь блок не хватает.
      permission: null,
      component: SevenDaysQuickActionsWidget,
    },
    settings: {
      labelKey: 'tab.settings',
      permission: SEVENDAYS_PERMISSIONS.configure,
      component: SevenDaysSettingsTab,
    },
  },
  'test-dummy': {
    tabs: {
      playerList: { labelKey: 'tab.players', permission: 'test-dummy.players', component: DummyPlayersTab },
      quickCommands: {
        labelKey: 'tab.quick',
        permission: 'test-dummy.quick-commands',
        component: DummyQuickCommandsTab,
      },
      tickets: { labelKey: 'tab.tickets', permission: 'tickets.view', component: DummyTicketsTab },
    },
  },
};

/** Экран настроек модуля, если он у него есть. */
export function resolveSettings(moduleId: string): ModuleFrontend['settings'] | null {
  return MODULE_REGISTRY[moduleId]?.settings ?? null;
}

/** Вкладка для capability: сначала своя у модуля, иначе — общая из ядра. */
export function resolveTab(moduleId: string, capability: ModuleCapability): CapabilityTab | null {
  return MODULE_REGISTRY[moduleId]?.tabs[capability] ?? CORE_TABS[capability] ?? null;
}
