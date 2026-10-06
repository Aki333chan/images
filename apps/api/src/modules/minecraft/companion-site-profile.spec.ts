process.env.NODE_ENV = 'test';
const requestMock = jest.fn();
jest.mock('undici', () => ({ request: (...args: unknown[]) => requestMock(...args) }));
import { CompanionService } from './companion.service';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

const setup = () => new CompanionService({ read: async () => ({ companion: { baseUrl: 'http://10.0.0.2:8083', token: 'synthetic' } }) } as unknown as MinecraftConfigService);
const reply = (body: unknown, statusCode = 200) => ({ statusCode, body: { text: async () => JSON.stringify(body) } });

describe('website profile read-only projection', () => {
  beforeEach(() => requestMock.mockReset());
  it('uses exactly three fixed read endpoints and excludes staff or treasury data', async () => {
    requestMock.mockImplementation(async (url: string, init: { method: string }) => {
      expect(init.method).toBe('GET');
      if (url.includes('/site/players/')) return reply({ online: false, playTimeTicks: 72000, deaths: 0, playerKills: -1, ip: 'private', x: 99 });
      if (url.includes('/economy/native/balance/')) return reply({ balance: 0, formatted: '0 coins', currency: 'coins', postings: [] });
      if (url.endsWith('/guild')) return reply({ membership: { guildId: 1, guildName: 'Guild', guildTag: 'TAG', rank: 'LEADER', bankBalance: 500 } });
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
    expect(result.player).toBeNull(); expect(result.balance).toBeNull();
    expect(result.guild).toEqual({ available: false, membership: null });
    expect(requestMock.mock.calls.every(([url]) => !String(url).includes('/players/uuid/balance'))).toBe(true);
  });
  it('handles partial/empty integrations without inventing statistics or balances', async () => {
    requestMock.mockResolvedValue(reply({ membership: null }));
    const result = await setup().getSiteProfile('server', 'uuid');
    expect(result.player).toBeNull(); expect(result.balance).toBeNull();
    expect(result.guild).toEqual({ available: true, membership: null });
  });
});
