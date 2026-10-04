process.env.NODE_ENV = 'test';
import { AiSettingsService } from './ai-settings.service';
import type { PrismaService } from '../prisma/prisma.service';
import type { CryptoService } from '../common/crypto.service';

function setup() {
  const values = new Map<string, string>();
  const secrets = new Map<string, string>();
  const prisma = {
    appSetting: {
      findUnique: ({ where }: { where: { key: string } }) =>
        Promise.resolve(values.has(where.key) ? { value: values.get(where.key) } : null),
      upsert: ({ where, create }: { where: { key: string }; create: { value: string } }) => {
        values.set(where.key, create.value);
        return Promise.resolve(create);
      },
    },
    integrationSecret: {
      findUnique: ({ where }: { where: { key: string } }) =>
        Promise.resolve(secrets.has(where.key) ? { valueEnc: secrets.get(where.key) } : null),
      upsert: ({ where, create }: { where: { key: string }; create: { valueEnc: string } }) => {
        secrets.set(where.key, create.valueEnc);
        return Promise.resolve(create);
      },
      deleteMany: ({ where }: { where: { key: string } }) => {
        secrets.delete(where.key);
        return Promise.resolve({ count: 1 });
      },
    },
  } as unknown as PrismaService;
  prisma.$transaction = ((callback: (tx: unknown) => unknown) => callback(prisma)) as never;
  const crypto = {
    encrypt: (value: string) => `encrypted:${value}`,
    decrypt: (value: string) => value.slice(10),
  } as CryptoService;
  return { service: new AiSettingsService(prisma, crypto), values, secrets };
}

it('preserves the existing encrypted DeepSeek key and legacy selected model', async () => {
  const { service, values, secrets } = setup();
  values.set('ai.model', 'deepseek-v4-flash');
  values.set('ai.enabled', 'true');
  secrets.set('ai.deepseek.apiKey', 'encrypted:legacy-key');
  expect(await service.get()).toMatchObject({
    provider: 'deepseek',
    model: 'deepseek-v4-flash',
    hasApiKey: true,
  });
  expect(await service.getRuntime()).toMatchObject({ apiKey: 'legacy-key' });
  expect(JSON.stringify(await service.get())).not.toContain('legacy-key');
});

it('keeps keys and models separate, even after deleting the active key', async () => {
  const { service, secrets } = setup();
  await service.update({ provider: 'deepseek', apiKey: 'ds-test', model: 'deepseek-v4-pro' });
  await service.update({ provider: 'gemini', apiKey: 'gm-test', model: 'gemini-3.5-flash-lite' });
  expect(await service.get()).toMatchObject({ providerKeys: { deepseek: true, gemini: true } });
  await service.update({ provider: 'gemini', apiKey: '   ' });
  expect(secrets.get('ai.gemini.apiKey')).toBe('encrypted:gm-test');
  await service.update({ provider: 'gemini', apiKey: '' });
  expect(secrets.get('ai.gemini.apiKey')).toBe('encrypted:gm-test');
  await service.update({ provider: 'gemini', apiKey: null });
  expect(secrets.get('ai.deepseek.apiKey')).toBe('encrypted:ds-test');
  expect(await service.update({ provider: 'deepseek' })).toMatchObject({
    model: 'deepseek-v4-pro',
    hasApiKey: true,
  });
});

it('rejects unknown models and invalid limits before writing settings', async () => {
  const { service, values } = setup();
  await expect(service.update({ provider: 'gemini', model: 'deepseek-flash' })).rejects.toThrow(
    'ai.err.unsupportedModel',
  );
  await expect(service.update({ dailyBudgetUsd: -1 })).rejects.toThrow('ai.err.limits');
  await expect(service.update({ maxOutputTokens: 999999 })).rejects.toThrow('ai.err.limits');
  expect(values.size).toBe(0);
});
