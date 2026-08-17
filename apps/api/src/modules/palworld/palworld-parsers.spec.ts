process.env.NODE_ENV = 'test';

import {
  isValidUserId,
  parseInfo,
  parseMetrics,
  parsePlayers,
  sanitizeMessage,
} from './palworld-parsers';

/**
 * Разбор ответов REST API Palworld.
 *
 * Образцы взяты из спецификации API (поля servername, serverfps,
 * location_x/location_y, userId вида steam_...). Тесты проверяют не только
 * счастливый путь, но и терпимость: сервер может отдать меньше полей, чем
 * ожидается, и панель должна показать «—», а не NaN и не упасть.
 */
describe('parsePlayers', () => {
  const sample = {
    players: [
      {
        name: 'PalUser',
        playerId: '123456789',
        userId: 'steam_00000000000000000',
        ip: '127.0.0.1',
        ping: 3.14,
        location_x: 123.45,
        location_y: 67.89,
        level: 12,
      },
    ],
  };

  it('разбирает ответ по спецификации', () => {
    expect(parsePlayers(sample)).toEqual([
      {
        name: 'PalUser',
        playerId: '123456789',
        userId: 'steam_00000000000000000',
        ping: 3.14,
        level: 12,
        position: { x: 123.45, y: 67.89 },
      },
    ]);
  });

  it('пустой сервер — пустой список, а не ошибка', () => {
    expect(parsePlayers({ players: [] })).toEqual([]);
  });

  // Сервер отвечает так, пока мир ещё грузится, и в момент перезапуска.
  it('ответ без поля players не роняет разбор', () => {
    expect(parsePlayers({})).toEqual([]);
    expect(parsePlayers(null)).toEqual([]);
    expect(parsePlayers('не json вовсе')).toEqual([]);
  });

  it('запись без имени отбрасывается', () => {
    expect(parsePlayers({ players: [{ userId: 'steam_1' }, { name: '   ' }] })).toEqual([]);
  });

  it('недостающие поля становятся null, а не NaN', () => {
    const [player] = parsePlayers({ players: [{ name: 'Solo' }] });
    expect(player).toEqual({
      name: 'Solo',
      userId: null,
      playerId: null,
      ping: null,
      level: null,
      position: null,
    });
  });

  // Координата имеет смысл только в паре: одна половина ничего не говорит
  // о положении на карте, и показывать её как позицию нельзя.
  it('половина координат — это отсутствие координат', () => {
    const [player] = parsePlayers({ players: [{ name: 'Solo', location_x: 10 }] });
    expect(player!.position).toBeNull();
  });

  it('числа строками принимаются — версии сервера отличаются', () => {
    const [player] = parsePlayers({
      players: [{ name: 'Solo', ping: '42', level: '7', location_x: '1.5', location_y: '2.5' }],
    });
    expect(player).toMatchObject({ ping: 42, level: 7, position: { x: 1.5, y: 2.5 } });
  });

  it('мусор вместо числа не превращается в NaN', () => {
    const [player] = parsePlayers({ players: [{ name: 'Solo', ping: 'много' }] });
    expect(player!.ping).toBeNull();
  });
});

describe('parseMetrics', () => {
  it('разбирает ответ по спецификации', () => {
    expect(
      parseMetrics({
        serverfps: 57,
        currentplayernum: 10,
        serverframetime: 16.7671,
        maxplayernum: 32,
        uptime: 3600,
      }),
    ).toEqual({
      fps: 57,
      frameTimeMs: 16.7671,
      onlineCount: 10,
      maxPlayers: 32,
      uptimeSeconds: 3600,
    });
  });

  it('пустой ответ даёт null по всем показателям', () => {
    expect(parseMetrics({})).toEqual({
      fps: null,
      frameTimeMs: null,
      onlineCount: null,
      maxPlayers: null,
      uptimeSeconds: null,
    });
  });

  it('ноль — это значение, а не отсутствие', () => {
    // Сервер на паузе честно отдаёт 0 fps; показать «—» здесь было бы враньём.
    expect(parseMetrics({ serverfps: 0, currentplayernum: 0 })).toMatchObject({
      fps: 0,
      onlineCount: 0,
    });
  });
});

describe('parseInfo', () => {
  it('разбирает ответ по спецификации', () => {
    expect(
      parseInfo({
        version: 'v0.1.5.0',
        servername: 'Palworld example Server',
        description: 'This is a Palworld server.',
      }),
    ).toEqual({
      version: 'v0.1.5.0',
      serverName: 'Palworld example Server',
      description: 'This is a Palworld server.',
    });
  });

  // Регрессия на самую вероятную опечатку: поле называется servername
  // в одно слово, и serverName вернул бы null на рабочем сервере.
  it('имя сервера читается из поля servername', () => {
    expect(parseInfo({ serverName: 'не то поле' }).serverName).toBeNull();
    expect(parseInfo({ servername: 'то поле' }).serverName).toBe('то поле');
  });

  it('пустые строки считаются отсутствием', () => {
    expect(parseInfo({ servername: '   ', version: '' })).toEqual({
      serverName: null,
      version: null,
      description: null,
    });
  });
});

describe('isValidUserId', () => {
  it('принимает идентификатор Steam', () => {
    expect(isValidUserId('steam_0110000100000000')).toBe(true);
  });

  it('принимает форматы других платформ', () => {
    // Xbox и прочие дают свои виды; запрещать их наугад значит ломать
    // работу панели на этих серверах.
    expect(isValidUserId('xuid_2535465768')).toBe(true);
    expect(isValidUserId('a1b2-c3d4.e5')).toBe(true);
  });

  it('отвергает пробелы и управляющие символы', () => {
    // Значение уходит в тело запроса к игровому серверу.
    expect(isValidUserId('steam_1 steam_2')).toBe(false);
    expect(isValidUserId('steam_1\n')).toBe(false);
    expect(isValidUserId('')).toBe(false);
  });

  it('отвергает слишком длинное значение', () => {
    expect(isValidUserId('s'.repeat(129))).toBe(false);
  });
});

describe('sanitizeMessage', () => {
  it('схлопывает переводы строк и лишние пробелы', () => {
    expect(sanitizeMessage('Рестарт\nчерез   5 минут')).toBe('Рестарт через 5 минут');
  });

  it('кавычки не трогает — они уедут полем JSON', () => {
    // В отличие от RCON, где команда это одна строка, здесь сообщение
    // передаётся значением JSON, и экранирование сделает JSON.stringify.
    expect(sanitizeMessage('Сервер "Аурум" ждёт')).toBe('Сервер "Аурум" ждёт');
  });

  it('ограничивает длину', () => {
    expect(sanitizeMessage('а'.repeat(500))).toHaveLength(200);
    expect(sanitizeMessage('а'.repeat(500), 10)).toHaveLength(10);
  });
});
