process.env.NODE_ENV = 'test';
const requestMock = jest.fn();
jest.mock('undici', () => ({ request: (...args: unknown[]) => requestMock(...args) }));
import { CompanionService } from './companion.service';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

const setup = () =>
  new CompanionService({
    read: async () => ({ companion: { baseUrl: 'http://10.0.0.2:8083', token: 'synthetic' } }),
  } as unknown as MinecraftConfigService);
const reply = (body: unknown, statusCode = 200) => ({
  statusCode,
  body: { text: async () => JSON.stringify(body) },
});

describe('website profile read-only projection', () => {
  beforeEach(() => requestMock.mockReset());
  it('uses exactly three fixed read endpoints and excludes staff or treasury data', async () => {
    requestMock.mockImplementation(async (url: string, init: { method: string }) => {
      expect(init.method).toBe('GET');
      if (url.includes('/site/players/'))
        return reply({
          online: false,
          playTimeTicks: 72000,
          deaths: 0,
          playerKills: -1,
          ip: 'private',
          x: 99,
        });
      if (url.includes('/economy/native/balance/'))
        return reply({ balance: 0, formatted: '0 coins', currency: 'coins', postings: [] });
      if (url.endsWith('/guild'))
        return reply({
          membership: { guildId: 1, guildName: 'Guild', guildTag: 'TAG', rank: 'LEADER', bankBalance: 500 },
        });
      throw new Error('Unexpected endpoint');
    });
    const result = await setup().getSiteProfile('server', 'uuid');
    expect(requestMock).toHaveBeenCalledTimes(3);
    expect(result.player).toEqual({ online: false, playTimeTicks: 72000, deaths: 0, playerKills: null });
    expect(result.balance?.amount).toBe(0);
    expect(Object.keys(result.guild.membership!)).toEqual(['guildId', 'guildName', 'guildTag', 'rank']);
  });
  it('keeps unavailable sections distinct from zero or no guild, without Vault fallback', async () => {
    requestMock.mockResolvedValue(reply({ code: 'unavailable' }, 503));
    const result = await setup().getSiteProfile('server', 'uuid');
    expect(result.player).toBeNull();
    expect(result.balance).toBeNull();
    expect(result.guild).toEqual({ available: false, membership: null });
    expect(requestMock.mock.calls.every(([url]) => !String(url).includes('/players/uuid/balance'))).toBe(
      true,
    );
  });
  it('handles partial/empty integrations without inventing statistics or balances', async () => {
    requestMock.mockResolvedValue(reply({ membership: null }));
    const result = await setup().getSiteProfile('server', 'uuid');
    expect(result.player).toBeNull();
    expect(result.balance).toBeNull();
    expect(result.guild).toEqual({ available: true, membership: null });
  });
});

describe('website guild projection and player actions', () => {
  beforeEach(() => requestMock.mockReset());

  it('projects fresh membership and invitations without exposing admin fields', async () => {
    const service = setup();
    jest
      .spyOn(service, 'getGuild')
      .mockResolvedValue({
        id: 1,
        name: 'Guild',
        tag: 'TAG',
        leaderName: 'Steve',
        leaderUuid: 'private-leader',
        createdAt: '2026-10-06',
        bankBalance: 5,
        memberCount: 1,
        members: [{ uuid: 'uuid', name: 'Steve', rank: 'leader', joinedAt: '2026-10-06' }],
      } as never);
    requestMock.mockImplementation(async (url: string) =>
      url.includes('/site/players/')
        ? reply({ guilds: [{ id: 1 }] })
        : reply({ membership: { guildId: 1, rank: 'leader' } }),
    );
    const result = await service.getSiteGuild('server', 1, 'uuid');
    expect(result.actionsAvailable).toBe(true);
    expect(result.invited).toBe(true);
    expect(result.membership).toEqual({ guildId: 1, rank: 'leader' });
    expect(result.guild).not.toHaveProperty('leaderUuid');
    expect(requestMock).toHaveBeenCalledTimes(2);
    expect(requestMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it('keeps second acceptance explicit and never retries an uncertain write', async () => {
    const action = { playerUuid: 'uuid', guildId: 1, action: 'join' };
    requestMock.mockResolvedValue(
      reply({ ok: false, message: 'guild.join.confirmSwitch', values: { siteText: 'Confirm switch' } }, 409),
    );
    const service = setup();
    expect(await service.executeSiteGuildAction('server', action)).toEqual({
      ok: false,
      messageKey: 'guild.join.confirmSwitch',
      message: 'Confirm switch',
      requiresConfirmation: true,
    });
    expect(requestMock.mock.calls[0][0]).toBe('http://10.0.0.2:8083/site/guild-action');
    expect(requestMock.mock.calls[0][1].body).toBe(JSON.stringify(action));
    requestMock.mockReset().mockResolvedValue(reply({ error: 'Unavailable' }, 503));
    await expect(service.executeSiteGuildAction('server', action)).rejects.toThrow();
    expect(requestMock).toHaveBeenCalledTimes(1);
  });
});
