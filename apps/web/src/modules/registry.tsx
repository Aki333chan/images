import type { ComponentType } from 'react';
import type { ModuleCapability } from '@aurum/shared';
import {
  DummyConsoleTab,
  DummyPlayersTab,
  DummyQuickCommandsTab,
  DummyTicketsTab,
} from './test-dummy/tabs';

export interface ModuleTabProps {
  serverId: string;
  moduleId: string;
}

interface CapabilityTab {
  label: string;
  /** Право, необходимое для показа вкладки (null — достаточно servers.view). */
  permission: string | null;
  component: ComponentType<ModuleTabProps>;
}

/**
 * Фронтенд-реестр модулей: id модуля -> компоненты вкладок по capability.
 * Вкладки на ServerDetail рендерятся пересечением: capabilities манифеста
 * активного модуля ∩ зарегистрированные компоненты ∩ права пользователя.
 */
export const MODULE_TAB_REGISTRY: Record<
  string,
  Partial<Record<ModuleCapability, CapabilityTab>>
> = {
  'test-dummy': {
    console: { label: 'Консоль', permission: 'test-dummy.console', component: DummyConsoleTab },
    playerList: { label: 'Игроки', permission: 'test-dummy.players', component: DummyPlayersTab },
    quickCommands: {
      label: 'Быстрые команды',
      permission: 'test-dummy.quick-commands',
      component: DummyQuickCommandsTab,
    },
    tickets: { label: 'Тикеты', permission: 'tickets.view', component: DummyTicketsTab },
  },
};
