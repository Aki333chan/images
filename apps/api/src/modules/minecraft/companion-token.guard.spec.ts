process.env.NODE_ENV = 'test';

import { ExecutionContext, ForbiddenException } from '@nestjs/common';
import { CompanionTokenGuard } from './companion-token.guard';
// Проверка приватной сети переехала в ядро: правило общее для всех модулей.
import { isPrivateAddress } from '../../common/private-network';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

describe('isPrivateAddress', () => {
  it.each(['10.0.0.2', '127.0.0.1', '192.168.1.5', '172.16.0.1', '172.31.255.254', '::1'])(
    'принимает приватный адрес %s',
    (ip) => expect(isPrivateAddress(ip)).toBe(true),
  );

  it('понимает IPv4-mapped адреса от Express', () => {
    expect(isPrivateAddress('::ffff:10.0.0.2')).toBe(true);
    expect(isPrivateAddress('::ffff:8.8.8.8')).toBe(false);
  });

  it.each(['8.8.8.8', '172.32.0.1', '172.15.0.1', '1.2.3.4', ''])(
    'отклоняет публичный адрес %s',
    (ip) => expect(isPrivateAddress(ip)).toBe(false),
  );
});

describe('CompanionTokenGuard', () => {
  const TOKEN = 'server-1-secret-token-EXAMPLE';

  function makeContext(opts: { ip?: string; auth?: string; serverId?: string }): ExecutionContext {
    const req = {
      ip: opts.ip ?? '10.0.0.2',
      headers: opts.auth ? { authorization: opts.auth } : {},
      params: { serverId: opts.serverId ?? 'srv-1' },
    };
    return {
      switchToHttp: () => ({ getRequest: () => req }),
    } as unknown as ExecutionContext;
  }

  function makeGuard(storedToken: string | null) {
    const config = {
      read: jest.fn().mockResolvedValue(storedToken ? { companion: { token: storedToken } } : {}),
    };
    return new CompanionTokenGuard(config as unknown as MinecraftConfigService);
  }

  it('пропускает плагин с верным токеном из приватной сети', async () => {
    const guard = makeGuard(TOKEN);
    await expect(guard.canActivate(makeContext({ auth: `Bearer ${TOKEN}` }))).resolves.toBe(true);
  });

  it('принимает токен и без префикса Bearer', async () => {
    const guard = makeGuard(TOKEN);
    await expect(guard.canActivate(makeContext({ auth: TOKEN }))).resolves.toBe(true);
  });

  it('отклоняет неверный токен', async () => {
    const guard = makeGuard(TOKEN);
    await expect(guard.canActivate(makeContext({ auth: 'Bearer другой' }))).rejects.toThrow(
      ForbiddenException,
    );
  });

  it('отклоняет токен другой длины (защита от подбора по длине)', async () => {
    const guard = makeGuard(TOKEN);
    await expect(guard.canActivate(makeContext({ auth: 'Bearer x' }))).rejects.toThrow(
      ForbiddenException,
    );
  });

  it('отклоняет запрос без токена', async () => {
    const guard = makeGuard(TOKEN);
    await expect(guard.canActivate(makeContext({}))).rejects.toThrow(ForbiddenException);
  });

  it('отклоняет запрос из публичной сети даже с верным токеном', async () => {
    const guard = makeGuard(TOKEN);
    await expect(
      guard.canActivate(makeContext({ ip: '8.8.8.8', auth: `Bearer ${TOKEN}` })),
    ).rejects.toThrow('только из внутренней сети');
  });

  it('отклоняет сервер без настроенного плагина', async () => {
    const guard = makeGuard(null);
    await expect(guard.canActivate(makeContext({ auth: `Bearer ${TOKEN}` }))).rejects.toThrow(
      'не настроен companion-плагин',
    );
  });
});
