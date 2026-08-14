process.env.NODE_ENV = 'test';

import { ExecutionContext, ForbiddenException, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { PermissionsGuard } from './permissions.guard';
import { PermissionsService } from './permissions.service';
import { PrismaService } from '../prisma/prisma.service';
import { PERMISSION_KEY, SERVER_SCOPE_PARAM } from './rbac.decorators';

type UserRow = {
  id: string;
  role: 'OWNER' | 'ADMIN' | 'MODERATOR' | 'VIEWER';
  isActive: boolean;
  serverAccess: { serverId: string }[];
};

function makeContext(opts: {
  metadata: Record<string, unknown>;
  user?: { id: string; sessionId: string };
  params?: Record<string, string>;
}): { ctx: ExecutionContext; reflector: Reflector; req: Record<string, unknown> } {
  const req: Record<string, unknown> = { user: opts.user, params: opts.params ?? {} };
  const ctx = {
    getHandler: () => 'handler',
    getClass: () => 'class',
    switchToHttp: () => ({ getRequest: () => req }),
  } as unknown as ExecutionContext;
  const reflector = {
    getAllAndOverride: (key: string) => opts.metadata[key],
  } as unknown as Reflector;
  return { ctx, reflector, req };
}

describe('PermissionsGuard (RBAC по состоянию БД)', () => {
  let prisma: { user: { findUnique: jest.Mock; findUniqueOrThrow: jest.Mock } };
  let permissions: PermissionsService;

  function setDbUser(user: UserRow | null) {
    prisma.user.findUnique.mockResolvedValue(user);
  }

  beforeEach(() => {
    prisma = { user: { findUnique: jest.fn(), findUniqueOrThrow: jest.fn() } };
    permissions = new PermissionsService(prisma as unknown as PrismaService);
  });

  function guardFor(metadata: Record<string, unknown>, user?: UserRow | null, params?: Record<string, string>) {
    const { ctx, reflector } = makeContext({
      metadata,
      user: user ? { id: user.id, sessionId: 's1' } : { id: 'ghost', sessionId: 's1' },
      params,
    });
    setDbUser(user ?? null);
    return new PermissionsGuard(reflector, permissions).canActivate(ctx);
  }

  it('пропускает роут без метаданных RBAC', async () => {
    await expect(guardFor({}, null)).resolves.toBe(true);
    expect(prisma.user.findUnique).not.toHaveBeenCalled();
  });

  it('OWNER имеет любые права, включая права модулей', async () => {
    const owner: UserRow = { id: 'u1', role: 'OWNER', isActive: true, serverAccess: [] };
    await expect(guardFor({ [PERMISSION_KEY]: 'users.manage' }, owner)).resolves.toBe(true);
    await expect(guardFor({ [PERMISSION_KEY]: 'test-dummy.console' }, owner)).resolves.toBe(true);
  });

  it('MODERATOR не имеет users.manage', async () => {
    const mod: UserRow = { id: 'u2', role: 'MODERATOR', isActive: true, serverAccess: [] };
    await expect(guardFor({ [PERMISSION_KEY]: 'users.manage' }, mod)).rejects.toThrow(
      ForbiddenException,
    );
  });

  it('MODERATOR имеет tickets.respond (право ядра по роли)', async () => {
    const mod: UserRow = { id: 'u2', role: 'MODERATOR', isActive: true, serverAccess: [] };
    await expect(guardFor({ [PERMISSION_KEY]: 'tickets.respond' }, mod)).resolves.toBe(true);
  });

  it('server-scope: ADMIN с привязкой к srv-1 не попадает на srv-2', async () => {
    const admin: UserRow = {
      id: 'u3',
      role: 'ADMIN',
      isActive: true,
      serverAccess: [{ serverId: 'srv-1' }],
    };
    await expect(
      guardFor(
        { [PERMISSION_KEY]: 'servers.view', [SERVER_SCOPE_PARAM]: 'serverId' },
        admin,
        { serverId: 'srv-1' },
      ),
    ).resolves.toBe(true);
    await expect(
      guardFor(
        { [PERMISSION_KEY]: 'servers.view', [SERVER_SCOPE_PARAM]: 'serverId' },
        admin,
        { serverId: 'srv-2' },
      ),
    ).rejects.toThrow(ForbiddenException);
  });

  it('server-scope: OWNER проходит на любой сервер без привязок', async () => {
    const owner: UserRow = { id: 'u1', role: 'OWNER', isActive: true, serverAccess: [] };
    await expect(
      guardFor({ [SERVER_SCOPE_PARAM]: 'serverId' }, owner, { serverId: 'any' }),
    ).resolves.toBe(true);
  });

  it('деактивированный пользователь отклоняется независимо от JWT', async () => {
    const inactive: UserRow = { id: 'u4', role: 'ADMIN', isActive: false, serverAccess: [] };
    await expect(guardFor({ [PERMISSION_KEY]: 'servers.view' }, inactive)).rejects.toThrow(
      UnauthorizedException,
    );
  });

  it('права читаются из БД на каждый вызов: смена роли действует сразу', async () => {
    const { ctx, reflector } = makeContext({
      metadata: { [PERMISSION_KEY]: 'servers.manage' },
      user: { id: 'u5', sessionId: 's1' },
    });
    const guard = new PermissionsGuard(reflector, permissions);

    prisma.user.findUnique.mockResolvedValueOnce({
      id: 'u5',
      role: 'ADMIN',
      isActive: true,
      serverAccess: [],
    });
    await expect(guard.canActivate(ctx)).resolves.toBe(true);

    // «Понизили» в БД — следующий же запрос отклоняется, JWT тот же.
    prisma.user.findUnique.mockResolvedValueOnce({
      id: 'u5',
      role: 'VIEWER',
      isActive: true,
      serverAccess: [],
    });
    await expect(guard.canActivate(ctx)).rejects.toThrow(ForbiddenException);
  });
});
