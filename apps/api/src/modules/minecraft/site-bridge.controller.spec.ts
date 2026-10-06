import { ExecutionContext } from '@nestjs/common';
import { env } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { CompanionService } from './companion.service';
import { SiteBridgeController, SiteBridgeGuard } from './site-bridge.controller';

const serverId = '168cf9cf-dd90-4a6e-bd9b-3e51a50653f1';
const token = 'synthetic-site-bridge-token-only-32-characters';
const context = (ip: string, authorization?: unknown) =>
  ({
    switchToHttp: () => ({ getRequest: () => ({ ip, headers: { authorization } }) }),
  }) as ExecutionContext;

describe('player-scoped website bridge', () => {
  const previous = { token: env.SITE_BRIDGE_TOKEN, servers: env.SITE_BRIDGE_SERVERS };
  beforeEach(() => {
    env.SITE_BRIDGE_TOKEN = token;
    env.SITE_BRIDGE_SERVERS = serverId;
  });
  afterAll(() => {
    env.SITE_BRIDGE_TOKEN = previous.token;
    env.SITE_BRIDGE_SERVERS = previous.servers;
  });

  it('requires both the separate service token and a private source', () => {
    const guard = new SiteBridgeGuard();
    expect(guard.canActivate(context('10.0.0.1', `Bearer ${token}`))).toBe(true);
    expect(() => guard.canActivate(context('83.168.105.67', `Bearer ${token}`))).toThrow();
    expect(() => guard.canActivate(context('127.0.0.1', 'Bearer staff-token'))).toThrow();
    expect(() => guard.canActivate(context('127.0.0.1', ['Bearer', token]))).toThrow();
    env.SITE_BRIDGE_TOKEN = '';
    expect(() => guard.canActivate(context('127.0.0.1', 'Bearer '))).toThrow();
  });

  it('only lists allowlisted Minecraft servers and exposes no credentials', async () => {
    const findMany = jest.fn().mockResolvedValue([{ id: serverId, name: 'Minecraft' }]);
    const controller = new SiteBridgeController(
      { server: { findMany } } as unknown as PrismaService,
      {} as CompanionService,
    );
    expect(await controller.servers()).toEqual({ servers: [{ id: serverId, name: 'Minecraft' }] });
    expect(findMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { id: { in: [serverId] }, moduleId: 'minecraft' },
        select: { id: true, name: true },
      }),
    );
  });

  it('never consumes a token for an unpublished server', async () => {
    const consumeSiteLink = jest.fn();
    const controller = new SiteBridgeController(
      { server: { findMany: async () => [] } } as unknown as PrismaService,
      { consumeSiteLink } as unknown as CompanionService,
    );
    await expect(controller.consume({ serverId, code: 'ABCD2345' })).rejects.toThrow();
    expect(consumeSiteLink).not.toHaveBeenCalled();
  });

  it('returns only the proven identity and server, not arbitrary game operations', async () => {
    const consumeSiteLink = jest.fn().mockResolvedValue({ uuid: serverId, name: 'Steve' });
    const controller = new SiteBridgeController(
      {
        server: { findMany: async () => [{ id: serverId, name: 'Minecraft' }] },
      } as unknown as PrismaService,
      { consumeSiteLink } as unknown as CompanionService,
    );
    expect(await controller.consume({ serverId, code: 'ABCD2345' })).toEqual({
      serverId,
      serverName: 'Minecraft',
      playerUuid: serverId,
      playerName: 'Steve',
    });
    expect(consumeSiteLink).toHaveBeenCalledWith(serverId, 'ABCD2345');
  });

  it('checks publication before reading a profile or guilds', async () => {
    const getSiteProfile = jest.fn();
    const getGuilds = jest.fn();
    const controller = new SiteBridgeController(
      { server: { findMany: async () => [] } } as unknown as PrismaService,
      { getSiteProfile, getGuilds } as unknown as CompanionService,
    );
    await expect(controller.profile({ serverId, playerUuid: serverId })).rejects.toThrow();
    await expect(controller.guilds({ serverId, query: '' })).rejects.toThrow();
    expect(getSiteProfile).not.toHaveBeenCalled();
    expect(getGuilds).not.toHaveBeenCalled();
  });

  it('website guild writes are fixed player operations and require a published server', async () => {
    const executeSiteGuildAction = jest.fn().mockResolvedValue({ ok: true, message: 'done' });
    const findMany = jest.fn().mockResolvedValue([]);
    const controller = new SiteBridgeController(
      { server: { findMany } } as unknown as PrismaService,
      { executeSiteGuildAction } as unknown as CompanionService,
    );
    const action = { serverId, playerUuid: serverId, guildId: 1, action: 'join' };
    await expect(controller.guildAction(action)).rejects.toThrow();
    expect(executeSiteGuildAction).not.toHaveBeenCalled();
    findMany.mockResolvedValue([{ id: serverId, name: 'Minecraft' }]);
    await controller.guildAction(action);
    expect(executeSiteGuildAction).toHaveBeenCalledWith(serverId, {
      playerUuid: serverId,
      guildId: 1,
      action: 'join',
    });
  });

  it('guild directory explicitly excludes bank balances, members and leader UUID', async () => {
    const getGuilds = jest
      .fn()
      .mockResolvedValue([
        {
          id: 1,
          name: 'Guild',
          tag: 'TAG',
          leaderName: 'Steve',
          leaderUuid: serverId,
          bankBalance: 500,
          memberCount: 2,
          members: [{ uuid: serverId }],
        },
      ]);
    const controller = new SiteBridgeController(
      { server: { findMany: async () => [{ id: serverId, name: 'Minecraft' }] } } as unknown as PrismaService,
      { getGuilds } as unknown as CompanionService,
    );
    expect(await controller.guilds({ serverId, query: ' TAG ' })).toEqual({
      available: true,
      guilds: [{ id: 1, name: 'Guild', tag: 'TAG', leaderName: 'Steve', memberCount: 2 }],
      limit: 50,
    });
    expect(getGuilds).toHaveBeenCalledWith(serverId, 'TAG');
    getGuilds.mockResolvedValue(null);
    expect((await controller.guilds({ serverId, query: '' })).available).toBe(false);
  });
});
