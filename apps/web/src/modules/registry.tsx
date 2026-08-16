import type { ComponentType } from 'react';
import { MINECRAFT_PERMISSIONS, type CapabilityState, type ModuleCapability } from '@aurum/shared';
import { ConsoleTab } from '../components/ConsoleTab';
import {
  DummyPlayersTab,
  DummyQuickCommandsTab,
  DummyTicketsTab,
} from './test-dummy/tabs';
import {
  MinecraftBansTab,
  MinecraftInventoryTab,
  MinecraftPlayersTab,
  MinecraftQuickCommandsWidget,
  MinecraftWhitelistTab,
} from './minecraft/tabs';
import { MinecraftSettingsTab } from './minecraft/SettingsTab';

export interface ModuleTabProps {
  serverId: string;
  moduleId: string;
  /** Состояние capability из манифеста: true или 'requires-plugin'. */
  capabilityState: CapabilityState;
}

export interface CapabilityTab {
  label: string;
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
  settings?: { label: string; permission: string; component: ComponentType<ModuleTabProps> };
}

/**
 * Вкладки, которые ядро предоставляет само. Модулю достаточно объявить
 * capability в манифесте — своей реализации он не пишет (консоль идёт
 * через WebSocket Pterodactyl, а не через RCON модуля).
 */
const CORE_TABS: Partial<Record<ModuleCapability, CapabilityTab>> = {
  console: { label: 'Консоль', permission: 'servers.view', component: ConsoleTab },
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
        label: 'Игроки',
        permission: 'minecraft.players.view',
        component: MinecraftPlayersTab,
      },
      banKick: { label: 'Баны', permission: 'minecraft.ban', component: MinecraftBansTab },
      whitelist: {
        label: 'Whitelist',
        permission: 'minecraft.whitelist',
        component: MinecraftWhitelistTab,
      },
      inventory: {
        label: 'Инвентарь',
        permission: 'minecraft.inventory.view',
        component: MinecraftInventoryTab,
      },
    },
    dashboard: {
      permission: 'minecraft.quick-commands',
      component: MinecraftQuickCommandsWidget,
    },
    settings: {
      label: 'Настройки',
      permission: MINECRAFT_PERMISSIONS.configure,
      component: MinecraftSettingsTab,
    },
  },
  'test-dummy': {
    tabs: {
      playerList: { label: 'Игроки', permission: 'test-dummy.players', component: DummyPlayersTab },
      quickCommands: {
        label: 'Быстрые команды',
        permission: 'test-dummy.quick-commands',
        component: DummyQuickCommandsTab,
      },
      tickets: { label: 'Тикеты', permission: 'tickets.view', component: DummyTicketsTab },
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
