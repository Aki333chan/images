import type { ComponentType } from 'react';
import type { PermissionKey } from '@aurum/shared';
import { IconArchive, IconClock, IconDatabase, IconFolder, IconNetwork, IconRocket } from '../components/icons';
import { FilesTab } from './FilesTab';
import { NetworkTab } from './NetworkTab';
import { StartupTab } from './StartupTab';
import { DatabasesTab } from './DatabasesTab';
import { BackupsTab } from './BackupsTab';
import { SchedulesTab } from './SchedulesTab';

export interface ServerTabProps {
  serverId: string;
}

export interface ServerTab {
  id: string;
  /** Ключ подписи: реестр строится до того, как известен язык. */
  labelKey: string;
  /** Право, без которого вкладка не показывается. */
  permission: PermissionKey;
  icon: ComponentType<{ size?: number }>;
  component: ComponentType<ServerTabProps>;
}

/**
 * Общие вкладки сервера.
 *
 * ЭТО НЕ ВОЗМОЖНОСТИ МОДУЛЯ. Всё перечисленное — свойства самого
 * Pterodactyl, одинаковые при любой игре: файл есть файл, а бэкап есть
 * бэкап. Поэтому вкладки не проходят через манифест модуля и показываются
 * независимо от того, какой модуль подключён к серверу, — и даже если не
 * подключён никакой.
 *
 * Порядок — по частоте использования: файлы открывают каждый день, а
 * расписания заводят однажды и забывают.
 */
export const SERVER_TABS: ServerTab[] = [
  { id: 'files', labelKey: 'tab.files', permission: 'files.view', icon: IconFolder, component: FilesTab },
  {
    id: 'backups',
    labelKey: 'tab.backups',
    permission: 'backups.view',
    icon: IconArchive,
    component: BackupsTab,
  },
  {
    id: 'network',
    labelKey: 'tab.network',
    permission: 'allocations.manage',
    icon: IconNetwork,
    component: NetworkTab,
  },
  {
    id: 'startup',
    labelKey: 'tab.startup',
    permission: 'startup.manage',
    icon: IconRocket,
    component: StartupTab,
  },
  {
    id: 'databases',
    labelKey: 'tab.databases',
    permission: 'databases.manage',
    icon: IconDatabase,
    component: DatabasesTab,
  },
  {
    id: 'schedules',
    labelKey: 'tab.schedules',
    permission: 'schedules.manage',
    icon: IconClock,
    component: SchedulesTab,
  },
];
