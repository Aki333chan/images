import type { GameModuleManifest } from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { TestDummyModule } from './test-dummy.module';

/**
 * Фейковый тестовый модуль — проверка механизма реестра:
 * capabilities рендерят вкладки на ServerDetail, роуты монтируются под
 * /api/modules/test-dummy/..., права попадают в RBAC.
 */
export const testDummyManifest: GameModuleManifest = {
  id: 'test-dummy',
  displayName: 'Тестовый модуль',
  capabilities: {
    playerList: true,
    quickCommands: true,
    tickets: true,
  },
  permissions: [
    {
      key: 'test-dummy.console',
      description: 'Просмотр тестовой консоли',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: 'test-dummy.players',
      description: 'Просмотр списка игроков',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: 'test-dummy.quick-commands',
      description: 'Быстрые команды',
      defaultRoles: ['ADMIN'],
    },
  ],
};

export const testDummyModule: BackendGameModule = {
  manifest: testDummyManifest,
  nestModule: TestDummyModule,
};
