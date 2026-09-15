import 'reflect-metadata';
import { MinecraftController } from './minecraft.controller';
import { PERMISSION_KEY, SERVER_SCOPE_PARAM } from '../../rbac/rbac.decorators';
import { CompanionService } from './companion.service';
import { MinecraftService } from './minecraft.service';
import { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import { PrismaService } from '../../prisma/prisma.service';
import { JailsService } from './jails.service';

describe('starting balance editor boundary', () => {
  it('keeps reads server-scoped and both mutation phases admin-only', () => {
    for (const [method, permission] of [
      ['economyRules', 'minecraft.economy.view'],
      ['previewEconomyRule', 'minecraft.economy.admin'],
      ['applyEconomyRule', 'minecraft.economy.admin'],
    ] as const) {
      const handler = MinecraftController.prototype[method];
      expect(Reflect.getMetadata(PERMISSION_KEY, handler)).toEqual([permission]);
      expect(Reflect.getMetadata(SERVER_SCOPE_PARAM, handler)).toBe('serverId');
    }
  });
  it('accepts the singleton resource type without any Vault fallback', async () => {
    const getEconomyRules = jest.fn().mockResolvedValue([{ type: 'starting_balance', id: 'global', revision: 1,
      fields: { enabled: 'false', currency: 'coins', amount: '100' } }]);
    const controller = new MinecraftController({} as MinecraftService, {} as MinecraftConfigService,
      { getEconomyRules } as unknown as CompanionService, {} as PrismaService, {} as JailsService);
    const result = await controller.economyRules('server-one', 'starting_balance');
    expect(result[0]?.id).toBe('global');
    expect(getEconomyRules).toHaveBeenCalledWith('server-one', 'starting_balance');
    getEconomyRules.mockResolvedValue(null);
    await expect(controller.economyRules('server-one', 'starting_balance')).rejects.toThrow();
  });
});
