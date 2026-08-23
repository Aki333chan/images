process.env.NODE_ENV = 'test';

import {
  consoleError,
  parseBans,
  parseGameTime,
  parsePlayers,
  parseVersion,
  parseWhitelist,
} from './sevendays-parsers';

/**
 * Разбор ответов telnet-консоли 7 Days to Die.
 *
 * Образцы взяты из вывода настоящего сервера. Формат строки игрока сверен с
 * двумя независимыми парсерами (zigawatt/sdtd и Kitsune7Den) — совпадает
 * до символа.
 *
 * Проверяется не только счастливый путь. У telnet нет ни длины пакета, ни
 * номера запроса: в тот же поток сервер пишет живой лог, поля бывают
 * «<unknown>», а ник игрока может содержать запятую — то есть ровно тот
 * символ, по которому строку соблазнительно порезать.
 */

// Строка ровно в том виде, в каком её печатает сервер.
const PLAYER_LINE =
  '0. id=171, Lost, pos=(342.4, 49.0, -541.9), rot=(0.0, 194.1, 0.0), remote=True, ' +
  'health=112, deaths=10, zombies=225, players=3, score=175, level=12, ' +
  'pltfmid=Steam_76561198025499751, crossid=EOS_000200a5aac44b81aa8521f6cf48f412, ' +
  'ip=94.226.230.80, ping=13';

describe('parsePlayers', () => {
  it('разбирает строку игрока целиком', () => {
    const { players, online } = parsePlayers(`${PLAYER_LINE}\nTotal of 1 in the game`);

    expect(online).toBe(1);
    expect(players).toEqual([
      {
        entityId: 171,
        name: 'Lost',
        platformId: 'Steam_76561198025499751',
        crossId: 'EOS_000200a5aac44b81aa8521f6cf48f412',
        ip: '94.226.230.80',
        ping: 13,
        health: 112,
        deaths: 10,
        zombieKills: 225,
        playerKills: 3,
        score: 175,
        level: 12,
        position: { x: 342.4, y: 49.0, z: -541.9 },
      },
    ]);
  });

  // Из-за этого случая строку нельзя резать по запятым: и ник, и координаты
  // содержат запятые сами.
  it('ник с запятой не разваливает строку', () => {
    const line = PLAYER_LINE.replace('id=171, Lost,', 'id=171, Lost, again,');
    const { players } = parsePlayers(line);

    expect(players).toHaveLength(1);
    expect(players[0]!.name).toBe('Lost, again');
    expect(players[0]!.position).toEqual({ x: 342.4, y: 49.0, z: -541.9 });
  });

  // Сервер отвечает так, когда клиент отвалился прямо во время команды.
  it('«<unknown>» — это не имя, а отсутствие данных', () => {
    const line = PLAYER_LINE.replace('Steam_76561198025499751', '<unknown>').replace(
      'ip=94.226.230.80',
      'ip=<unknown>',
    );
    const { players } = parsePlayers(line);

    expect(players[0]!.platformId).toBeNull();
    expect(players[0]!.ip).toBeNull();
    // Остальные поля при этом остаются на месте.
    expect(players[0]!.crossId).toBe('EOS_000200a5aac44b81aa8521f6cf48f412');
  });

  // Живой лог сервера идёт в тот же поток, что и ответ команды.
  it('строки лога и эхо команды в список не попадают', () => {
    const output = [
      "2026-03-14T19:43:54 432.501 INF Executing command 'lp' by Telnet from 127.0.0.1:46610",
      PLAYER_LINE,
      '2026-03-14T19:43:55 433.102 INF GMSG: Player Lost joined the game',
      'Total of 1 in the game',
    ].join('\r\n');

    const { players, online } = parsePlayers(output);
    expect(players).toHaveLength(1);
    expect(players[0]!.name).toBe('Lost');
    expect(online).toBe(1);
  });

  // Онлайн берётся из строки сервера, а не из длины списка: потерянную
  // строку лучше показать как расхождение, чем спрятать.
  it('счётчик онлайна берётся из «Total of N», а не из числа разобранных строк', () => {
    const { players, online } = parsePlayers(`${PLAYER_LINE}\nTotal of 3 in the game`);
    expect(players).toHaveLength(1);
    expect(online).toBe(3);
  });

  it('пустой сервер — пустой список без ошибки', () => {
    expect(parsePlayers('Total of 0 in the game')).toEqual({ players: [], online: 0 });
    expect(parsePlayers('')).toEqual({ players: [], online: 0 });
  });
});

/**
 * Раскладку таблиц `ban list` и `whitelist list` игра нигде не публикует,
 * поэтому разбор опирается на форму токенов, а не на номера колонок. Тесты
 * ниже намеренно подают одни и те же данные в разных раскладках: разбор
 * обязан дать одинаковый результат.
 */
describe('parseBans', () => {
  it('разбирает таблицу на вертикальных чертах', () => {
    const output = [
      'Banned players:',
      '  Id                       | Banned until        | Name  | Reason',
      '  Steam_76561198025499751  | 2026-04-01 12:00:00 | Lost  | griefing',
      'Total of 1 banned players',
    ].join('\n');

    expect(parseBans(output)).toEqual([
      {
        id: 'Steam_76561198025499751',
        until: '2026-04-01 12:00:00',
        displayName: 'Lost',
        reason: 'griefing',
      },
    ]);
  });

  it('разбирает те же данные, разложенные по пробелам с причиной в скобках', () => {
    const output = [
      'Banned players:',
      '  Steam_76561198025499751   2026-04-01 12:00:00   Lost (griefing)',
    ].join('\n');

    expect(parseBans(output)).toEqual([
      {
        id: 'Steam_76561198025499751',
        until: '2026-04-01 12:00:00',
        displayName: 'Lost',
        reason: 'griefing',
      },
    ]);
  });

  // Заголовок таблицы идентификатора не содержит — этого достаточно, чтобы
  // его пропустить, не зная его текста.
  it('заголовок, разделитель и итоговая строка записями не считаются', () => {
    const output = [
      'Banned players:',
      '  Id                       Banned until          Name',
      '  -----------------------------------------------',
      '  EOS_000200a5aac44b81aa8521f6cf48f412   2026-05-09T08:00   Ghost',
      'Total of 1 banned players',
    ].join('\n');

    expect(parseBans(output)).toEqual([
      {
        id: 'EOS_000200a5aac44b81aa8521f6cf48f412',
        until: '2026-05-09T08:00',
        displayName: 'Ghost',
        reason: null,
      },
    ]);
  });

  // «Навсегда» в игре нет, но пустой срок в serveradmin.xml — есть.
  it('бан без срока — until null, а не выдуманная дата', () => {
    expect(parseBans('  76561198025499751  Lost (cheating)')).toEqual([
      { id: '76561198025499751', until: null, displayName: 'Lost', reason: 'cheating' },
    ]);
  });

  // Перечислять платформы в разборе нельзя: игра уже завела вторую (EOS для
  // кроссплея) и может завести третью. Незнакомый префикс не должен приводить
  // к тому, что забаненный игрок тихо пропадёт из списка.
  it('запись с незнакомой платформой не теряется', () => {
    expect(parseBans('  Nintendo_0011223344556677  2026-06-01  Wanderer (спам)')).toEqual([
      {
        id: 'Nintendo_0011223344556677',
        until: '2026-06-01',
        displayName: 'Wanderer',
        reason: 'спам',
      },
    ]);
  });

  it('пустой список банов — пустой массив', () => {
    expect(parseBans('Banned players:\nTotal of 0 banned players')).toEqual([]);
    expect(parseBans('')).toEqual([]);
  });
});

describe('parseWhitelist', () => {
  it('разбирает записи белого списка', () => {
    const output = [
      'Whitelisted players:',
      '  Id                        | Name',
      '  Steam_76561198025499751   | Lost',
      '  EOS_000200a5aac44b81aa8521f6cf48f412 | Ghost',
    ].join('\n');

    expect(parseWhitelist(output)).toEqual([
      { id: 'Steam_76561198025499751', displayName: 'Lost' },
      { id: 'EOS_000200a5aac44b81aa8521f6cf48f412', displayName: 'Ghost' },
    ]);
  });

  it('запись без имени — displayName null', () => {
    expect(parseWhitelist('  Steam_76561198025499751')).toEqual([
      { id: 'Steam_76561198025499751', displayName: null },
    ]);
  });

  it('пустой белый список означает, что он выключен, и это не ошибка', () => {
    expect(parseWhitelist('Whitelisted players:')).toEqual([]);
  });
});

describe('parseGameTime', () => {
  it('разбирает «Day 12, 19:53»', () => {
    expect(parseGameTime('Day 12, 19:53')).toEqual({ day: 12, time: '19:53' });
  });

  it('находит время в строке с меткой лога', () => {
    expect(parseGameTime('2026-03-14T19:43:54 432.501 INF Day 7, 04:05')).toEqual({
      day: 7,
      time: '04:05',
    });
  });

  it('на неразобранный ответ отдаёт null, а не нули', () => {
    expect(parseGameTime('*** ERROR: unknown command')).toEqual({ day: null, time: null });
  });
});

describe('parseVersion', () => {
  // Обрывать на первом пробеле нельзя: в ответе версия записана через пробел
  // после «V», и от номера осталась бы одна буква.
  it('берёт версию целиком, отбрасывая вторую подпись', () => {
    expect(parseVersion('Game version: V 2.0 (b28) Compatibility Version: V 2.0')).toBe(
      'V 2.0 (b28)',
    );
  });

  it('разбирает версию без пробела после V', () => {
    expect(parseVersion('Game version: V2.0.1 (b28)')).toBe('V2.0.1 (b28)');
  });

  it('без строки «Game version:» находит номер по форме', () => {
    expect(parseVersion('7 Days To Die V 1.4.7 (b8)')).toBe('V 1.4.7 (b8)');
  });

  it('на пустой ответ — null', () => {
    expect(parseVersion('')).toBeNull();
  });
});

describe('consoleError', () => {
  // У telnet нет кода ответа: отказ приходит такой же строкой, как успех.
  it('узнаёт неизвестную команду', () => {
    expect(consoleError("*** ERROR: unknown command 'xyz'")).toBe("unknown command 'xyz'");
  });

  it('узнаёт неверную единицу срока бана', () => {
    expect(consoleError('"fortnights" is not an allowed duration unit.')).toBe(
      '"fortnights" is not an allowed duration unit.',
    );
  });

  it('узнаёт ненайденного игрока', () => {
    expect(consoleError('Playername or entity/steam id not found.')).toBe(
      'Playername or entity/steam id not found.',
    );
  });

  it('находит отказ даже под меткой лога', () => {
    expect(consoleError("2026-03-14T19:43:54 432.501 INF *** ERROR: unknown command 'zz'")).toBe(
      "unknown command 'zz'",
    );
  });

  it('на успешный ответ молчит', () => {
    expect(consoleError('Total of 1 in the game')).toBeNull();
    expect(consoleError('')).toBeNull();
  });
});
