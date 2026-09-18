import { companionBloodMoonCountdown, SevenDaysService } from './sevendays.service';
import type { CompanionWorldState } from './sevendays-companion.service';

describe('Authoritative blood moon schedule', () => {
  const state = (extra: Partial<CompanionWorldState>): CompanionWorldState => ({
    ready: true,
    day: 10,
    hour: 12,
    minute: 0,
    bloodMoonActive: false,
    bloodMoonFrequency: 7,
    bloodMoonRange: 3,
    bloodMoonNextDay: 18,
    fps: 20,
    zombies: 1,
    maxZombies: 64,
    animals: 1,
    onlinePlayers: 1,
    maxPlayers: 24,
    version: 'V3.2.0 (b10)',
    ...extra,
  });
  it.each([
    [{}, 8],
    [{ bloodMoonNextDay: 10 }, 0],
    [{ bloodMoonNextDay: 9 }, null],
    [{ bloodMoonNextDay: null }, null],
    [{ bloodMoonNextDay: undefined }, null],
    [{ bloodMoonFrequency: 0 }, null],
    [{ bloodMoonFrequency: null, bloodMoonNextDay: null }, null],
    [{ bloodMoonActive: true, bloodMoonFrequency: 0 }, 0],
    [{ bloodMoonNextDay: NaN }, null],
    [{ day: 0 }, null],
  ] as [Partial<CompanionWorldState>, number | null][])(
    'uses actual schedule for %j',
    (extra, expected) => expect(companionBloodMoonCountdown(state(extra))).toBe(expected),
  );
  it('reports loading instead of an invented day-zero world', async () => {
    const companion = { state: jest.fn().mockResolvedValue(state({ ready: false, day: 0 })) };
    const config = { hasCompanion: jest.fn().mockResolvedValue(true) };
    const consoleService = { tryRun: jest.fn() };
    const service = new SevenDaysService(
      consoleService as never,
      config as never,
      companion as never,
    );
    expect(await service.getState('id')).toMatchObject({ available: false, source: 'companion' });
    expect(consoleService.tryRun).not.toHaveBeenCalled();
  });
  it('propagates actual settings and random scheduled day to the panel', async () => {
    const service = new SevenDaysService(
      {} as never,
      { hasCompanion: async () => true } as never,
      { state: async () => state({}) } as never,
    );
    expect(await service.getState('id')).toMatchObject({
      daysToBloodMoon: 8,
      bloodMoonRange: 3,
      bloodMoonNextDay: 18,
    });
  });
});
