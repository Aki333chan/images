// Zone schedules use an explicit fixed offset, never the browser/server local timezone.
export const zoneClock = (minutes: number) =>
  `${Math.floor(minutes / 60)
    .toString()
    .padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}`;
export const zoneOffset = (minutes: number) =>
  `UTC${minutes < 0 ? '-' : '+'}${zoneClock(Math.abs(minutes))}`;
export const zoneDate = (utc: number, offset: number) =>
  utc && Number.isFinite(utc)
    ? new Date((utc + offset * 60) * 1000).toISOString().slice(0, 16)
    : '';
export const zoneDateUtc = (value: string, offset: number) =>
  value ? Math.floor(Date.parse(`${value}Z`) / 1000) - offset * 60 : 0;
