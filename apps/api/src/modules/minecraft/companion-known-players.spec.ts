process.env.NODE_ENV = 'test';

// undici подменяется до импорта сервиса: настоящих запросов к плагину нет.
const requestMock = jest.fn();
jest.mock('undici', () => ({ request: (...args: unknown[]) => requestMock(...args) }));

import { CompanionService } from './companion.service';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';

/** Ответ в форме, которую отдаёт undici. */
function reply(statusCode: number, body: unknown) {
  return {
    statusCode,
    headers: { 'content-type': 'application/json' },
    body: { text: async () => JSON.stringify(body) },
  };
}

/** Сервис с настроенным (или не настроенным) companion-плагином. */
function setup(configured = true) {
  const config = {
    read: async () =>
      configured
        ? { companion: { baseUrl: 'http://10.0.0.2:8085', token: 'secret' } }
        : { companion: null },
  } as unknown as MinecraftConfigService;
  return new CompanionService(config);
}

/** Адрес, по которому ушёл n-й запрос. */
function urlOf(call: number): string {
  return String(requestMock.mock.calls[call]?.[0]);
}

describe('CompanionService — исторический список игроков', () => {
  beforeEach(() => requestMock.mockReset());

  it('переносит алиас, звёздочку оператора и признак регистрации', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        players: [
          {
            uuid: '8667ba71-b85a-4004-af54-457a9734eed7',
            name: 'Steve',
            alias: 'Стив',
            op: true,
            online: true,
            registered: true,
            lastSeen: 1_700_000_000_000,
          },
        ],
        total: 1,
        authAvailable: true,
      }),
    );

    const result = await setup().getKnownPlayers('srv-1');

    expect(result.available).toBe(true);
    expect(result.authAvailable).toBe(true);
    expect(result.total).toBe(1);
    expect(result.players[0]).toEqual({
      uuid: '8667ba71-b85a-4004-af54-457a9734eed7',
      name: 'Steve',
      alias: 'Стив',
      op: true,
      online: true,
      registered: true,
      lastSeen: new Date(1_700_000_000_000).toISOString(),
    });
  });

  it('null в registered остаётся null: «плагина авторизации нет» — не «не зарегистрирован»', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        players: [{ uuid: 'u-1', name: 'Alex', alias: null, op: false, online: false, registered: null }],
        total: 1,
        authAvailable: false,
      }),
    );

    const result = await setup().getKnownPlayers('srv-1');

    expect(result.authAvailable).toBe(false);
    expect(result.players[0]?.registered).toBeNull();
    // lastSeen не пришёл вовсе — это «сервер не помнит», а не эпоха ноль.
    expect(result.players[0]?.lastSeen).toBeNull();
  });

  it('алиас, совпадающий с настоящим именем, не показывается', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        players: [{ uuid: 'u-1', name: 'Steve', alias: 'Steve', op: false, online: false }],
        total: 1,
        authAvailable: false,
      }),
    );

    expect((await setup().getKnownPlayers('srv-1')).players[0]?.alias).toBeNull();
  });

  it('пустой алиас приравнивается к его отсутствию', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        players: [{ uuid: 'u-1', name: 'Steve', alias: '   ', op: false, online: false }],
        total: 1,
        authAvailable: true,
      }),
    );

    expect((await setup().getKnownPlayers('srv-1')).players[0]?.alias).toBeNull();
  });

  it('поиск и постраничность уходят в плагин параметрами запроса', async () => {
    requestMock.mockResolvedValue(reply(200, { players: [], total: 0, authAvailable: true }));

    await setup().getKnownPlayers('srv-1', { query: 'ste', offset: 50, limit: 25 });

    expect(urlOf(0)).toContain('/players/known?');
    expect(urlOf(0)).toContain('query=ste');
    expect(urlOf(0)).toContain('offset=50');
    expect(urlOf(0)).toContain('limit=25');
  });

  it('без companion-плагина — понятная причина, а не пустой список', async () => {
    const result = await setup(false).getKnownPlayers('srv-1');

    expect(result.available).toBe(false);
    expect(result.code).toBe('no-companion');
    expect(result.players).toEqual([]);
    expect(requestMock).not.toHaveBeenCalled();
  });

  it('молчащий плагин отличается от отсутствующего', async () => {
    requestMock.mockRejectedValue(new Error('ECONNREFUSED'));

    const result = await setup().getKnownPlayers('srv-1');

    expect(result.available).toBe(false);
    expect(result.code).toBe('plugin-unreachable');
  });

  it('записи без uuid или ника отбрасываются, а не рисуются пустой строкой', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        players: [{ name: 'Безуиида' }, { uuid: 'u-2' }, { uuid: 'u-3', name: 'Ok' }],
        total: 3,
        authAvailable: false,
      }),
    );

    const result = await setup().getKnownPlayers('srv-1');
    expect(result.players.map((p) => p.name)).toEqual(['Ok']);
  });
});

describe('CompanionService — известные IP', () => {
  beforeEach(() => requestMock.mockReset());

  it('отдаёт адреса с датами первого и последнего входа', async () => {
    requestMock.mockResolvedValue(
      reply(200, {
        addresses: [{ ip: '203.0.113.7', firstSeen: 1_600_000_000_000, lastSeen: 1_700_000_000_000 }],
      }),
    );

    const result = await setup().getIpHistory('srv-1', 'u-1');

    expect(result.available).toBe(true);
    expect(result.addresses).toEqual([
      {
        ip: '203.0.113.7',
        firstSeen: new Date(1_600_000_000_000).toISOString(),
        lastSeen: new Date(1_700_000_000_000).toISOString(),
      },
    ]);
    expect(urlOf(0)).toContain('/players/u-1/ips');
  });

  it('без плагина авторизации список пуст — это не ошибка', async () => {
    requestMock.mockResolvedValue(reply(200, { addresses: [] }));

    const result = await setup().getIpHistory('srv-1', 'u-1');

    expect(result.available).toBe(true);
    expect(result.addresses).toEqual([]);
  });

  it('без companion-плагина спрашивать некого', async () => {
    const result = await setup(false).getIpHistory('srv-1', 'u-1');

    expect(result.available).toBe(false);
    expect(result.code).toBe('no-companion');
    expect(requestMock).not.toHaveBeenCalled();
  });
});

describe('CompanionService — ник в UUID', () => {
  beforeEach(() => requestMock.mockReset());

  it('игрок в сети находится по списку онлайна, без второго запроса', async () => {
    requestMock.mockResolvedValue(reply(200, { players: [{ uuid: 'u-1', name: 'Steve' }] }));

    await expect(setup().resolveUuid('srv-1', 'steve')).resolves.toBe('u-1');
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('игрок не в сети находится по историческому списку', async () => {
    requestMock
      .mockResolvedValueOnce(reply(200, { players: [] }))
      .mockResolvedValueOnce(
        reply(200, {
          players: [{ uuid: 'u-9', name: 'Alex', op: false, online: false }],
          total: 1,
          authAvailable: false,
        }),
      );

    await expect(setup().resolveUuid('srv-1', 'ALEX')).resolves.toBe('u-9');
    expect(urlOf(1)).toContain('query=ALEX');
  });

  it('частичное совпадение по историческому списку не считается: «Ste» — не «Steve»', async () => {
    requestMock
      .mockResolvedValueOnce(reply(200, { players: [] }))
      .mockResolvedValueOnce(
        reply(200, {
          players: [{ uuid: 'u-1', name: 'Steve', op: false, online: false }],
          total: 1,
          authAvailable: false,
        }),
      );

    await expect(setup().resolveUuid('srv-1', 'Ste')).resolves.toBeNull();
  });
});
