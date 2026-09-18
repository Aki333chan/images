import { createServer, Server } from 'node:http';
import { AddressInfo } from 'node:net';
import { SevenDaysCompanionService, parseHandshake } from './sevendays-companion.service';
import { SevenDaysConfigService } from './sevendays-config.service';

describe('SevenDaysCompanionService transport', () => {
  let server: Server;
  let service: SevenDaysCompanionService;
  let seen: jest.Mock;
  let timers: NodeJS.Timeout[];
  beforeEach(async () => {
    timers = [];
    server = createServer();
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    seen = jest.fn().mockResolvedValue(undefined);
    const config = {
      readCompanion: jest.fn().mockResolvedValue({
        host: '127.0.0.1',
        port: (server.address() as AddressInfo).port,
        token: 'secret-token',
      }),
      markCompanionSeen: seen,
    } as unknown as SevenDaysConfigService;
    service = new SevenDaysCompanionService(config);
  });
  afterEach(async () => {
    timers.forEach(clearInterval);
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  it('parses split UTF8 and marks a completed response seen', async () => {
    server.on('request', (req, res) => {
      expect(req.headers.authorization).toBe('Bearer secret-token');
      const body = Buffer.from(
        JSON.stringify({ ok: true, mod: 'aurum-companion', version: 'żółć', contract: '1' }),
      );
      res.write(body.subarray(0, 14));
      res.end(body.subarray(14));
    });
    expect(await service.ping('id')).toEqual({
      version: 'żółć',
      contract: '1',
      compatible: true,
      capabilities: null,
      language: null,
    });
    expect(seen).toHaveBeenCalledTimes(1);
  });
  it('caps unknown-length bodies and closes the connection', async () => {
    server.on('request', (_req, res) => {
      res.write(Buffer.alloc(1024 * 1024));
      res.end(Buffer.alloc(1024));
    });
    expect(await service.ping('id')).toBeNull();
    expect(seen).not.toHaveBeenCalled();
  });
  it('does not mark an unrelated JSON service seen', async () => {
    server.on('request', (_req, res) => res.end('{"version":"1","contract":"1"}'));
    expect(await service.ping('id')).toBeNull();
    expect(seen).not.toHaveBeenCalled();
  });
  it('rejects malformed JSON without marking the mod seen', async () => {
    server.on('request', (_req, res) => res.end('broken'));
    expect(await service.ping('id')).toBeNull();
    expect(seen).not.toHaveBeenCalled();
  });
  it('does not follow redirects or forward tokens to a redirect destination', async () => {
    let calls = 0;
    server.on('request', (_req, res) => {
      calls++;
      res.writeHead(302, { location: '/other' });
      res.end('{}');
    });
    expect(await service.ping('id')).toBeNull();
    expect(calls).toBe(1);
    expect(seen).not.toHaveBeenCalled();
  });
  it('absolute deadline stops an endless trickle, without automatic retry', async () => {
    let calls = 0;
    server.on('request', (_req, res) => {
      calls++;
      res.write(' ');
      timers.push(setInterval(() => res.write(' '), 100));
    });
    const started = Date.now();
    expect(await service.ping('id')).toBeNull();
    expect(Date.now() - started).toBeLessThan(5500);
    expect(calls).toBe(1);
    expect(seen).not.toHaveBeenCalled();
  }, 7000);
});

describe('Companion handshake', () => {
  const base = { ok: true, mod: 'aurum-companion', version: '1.0.3-rc.1', contract: '1' };
  it('filters future capabilities, removes duplicates and recognizes language', () => {
    expect(
      parseHandshake({ ...base, language: 'pl', capabilities: ['tickets', 'future', 'tickets'] }),
    ).toEqual({
      version: base.version,
      contract: '1',
      compatible: true,
      capabilities: ['tickets'],
      language: 'pl',
    });
  });
  it('does not infer features for a future incompatible contract', () => {
    expect(parseHandshake({ ...base, contract: '2', capabilities: ['tickets'] })).toMatchObject({
      compatible: false,
      capabilities: [],
    });
  });
  it('keeps explicitly empty declarations distinct from legacy missing declarations', () => {
    expect(parseHandshake(base)?.capabilities).toBeNull();
    expect(parseHandshake({ ...base, capabilities: [] })?.capabilities).toEqual([]);
  });
  it.each([
    null,
    [],
    {},
    { ...base, ok: false },
    { ...base, mod: 'other' },
    { ...base, version: 3 },
    { ...base, contract: '' },
    { ...base, capabilities: 'tickets' },
    { ...base, capabilities: [null] },
    { ...base, capabilities: Array(65).fill('tickets') },
  ])('rejects malformed handshake %#', (value) => expect(parseHandshake(value)).toBeNull());
});
