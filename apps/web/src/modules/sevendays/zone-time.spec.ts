import { zoneClock, zoneOffset, zoneDate, zoneDateUtc } from './zone-time';

describe('zone schedule form time', () => {
  it('uses the chosen offset, including a date rollover and fractional offsets', () => {
    const utc = Date.parse('2026-09-21T22:30:00Z') / 1000;
    expect(zoneDate(utc, 120)).toBe('2026-09-22T00:30');
    expect(zoneDateUtc('2026-09-22T00:30', 120)).toBe(utc);
    expect(zoneDateUtc(zoneDate(utc, -345), -345)).toBe(utc);
    expect(zoneClock(1439)).toBe('23:59');
    expect(zoneOffset(-345)).toBe('UTC-05:45');
    expect(zoneOffset(0)).toBe('UTC+00:00');
    expect(zoneDate(0, 120)).toBe('');
    expect(zoneDateUtc('', 120)).toBe(0);
    expect(Number.isNaN(zoneDateUtc('not-a-date', 120))).toBe(true);
  });
});
