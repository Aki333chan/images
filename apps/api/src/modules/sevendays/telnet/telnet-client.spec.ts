process.env.NODE_ENV = 'test';

import { extractResponse, isLogLine, telnetCommand } from './telnet-client';

/**
 * Выделение ответа из telnet-потока 7 Days to Die.
 *
 * Это самое хрупкое место модуля: у telnet нет ни длины пакета, ни номера
 * запроса, а в тот же поток сервер пишет живой лог. Кадр держится на двух
 * отметках самого сервера — эхе команды и ответе на намеренно неизвестную
 * команду-метку. Тесты проверяют, что кадр не расползается.
 */

const MARKER = 'aurum1a2b3c4d';

describe('isLogLine', () => {
  it('узнаёт строку лога по дате, отметке времени и уровню', () => {
    expect(isLogLine('2026-03-14T19:43:54 432.501 INF Time: 12.34m FPS: 32.0')).toBe(true);
    expect(isLogLine('2026-03-14T19:43:55 433.102 WRN Player disconnected')).toBe(true);
    expect(isLogLine('2026-03-14T19:43:56 433.900 ERR NullReference')).toBe(true);
  });

  it('строку ответа за лог не принимает', () => {
    expect(isLogLine('0. id=171, Lost, pos=(342.4, 49.0, -541.9)')).toBe(false);
    expect(isLogLine('Total of 1 in the game')).toBe(false);
    // Дата сама по себе логом не делает: срок бана выглядит похоже.
    expect(isLogLine('Steam_76561198025499751  2026-04-01 12:00:00  Lost')).toBe(false);
    expect(isLogLine('')).toBe(false);
  });
});

describe('extractResponse', () => {
  it('берёт то, что между эхом команды и меткой', () => {
    const raw = [
      '2026-03-14T19:43:54 432.501 INF Executing command \'lp\' by Telnet from 127.0.0.1:46610',
      '0. id=171, Lost, pos=(342.4, 49.0, -541.9), ping=13',
      'Total of 1 in the game',
      `2026-03-14T19:43:54 432.700 INF Executing command '${MARKER}' by Telnet from 127.0.0.1:46610`,
      `*** ERROR: unknown command '${MARKER}'`,
    ].join('\r\n');

    expect(extractResponse(raw, 'lp', MARKER)).toBe(
      '0. id=171, Lost, pos=(342.4, 49.0, -541.9), ping=13\nTotal of 1 in the game',
    );
  });

  // Живой лог капает в тот же сокет всё время, пока идёт команда.
  it('выбрасывает строки лога, попавшие внутрь ответа', () => {
    const raw = [
      "2026-03-14T19:43:54 432.501 INF Executing command 'lp' by Telnet from 127.0.0.1:46610",
      '0. id=171, Lost, pos=(342.4, 49.0, -541.9), ping=13',
      '2026-03-14T19:43:54 432.610 INF GMSG: Player Ghost joined the game',
      'Total of 1 in the game',
      `*** ERROR: unknown command '${MARKER}'`,
    ].join('\r\n');

    expect(extractResponse(raw, 'lp', MARKER)).toBe(
      '0. id=171, Lost, pos=(342.4, 49.0, -541.9), ping=13\nTotal of 1 in the game',
    );
  });

  // В буфере может лежать хвост предыдущего выполнения той же команды —
  // например, если её только что запускал кто-то другой из игровой консоли.
  it('при двух эхо одной команды берёт последнее', () => {
    const raw = [
      "2026-03-14T19:43:10 400.000 INF Executing command 'lp' by Telnet from 127.0.0.1:46600",
      'СТАРЫЙ ОТВЕТ',
      "2026-03-14T19:43:54 432.501 INF Executing command 'lp' by Telnet from 127.0.0.1:46610",
      'Total of 0 in the game',
      `*** ERROR: unknown command '${MARKER}'`,
    ].join('\r\n');

    expect(extractResponse(raw, 'lp', MARKER)).toBe('Total of 0 in the game');
  });

  it('всё, что после метки, в ответ не попадает', () => {
    const raw = [
      "2026-03-14T19:43:54 432.501 INF Executing command 'version' by Telnet from 127.0.0.1:1",
      'Game version: V 2.0 (b28)',
      `*** ERROR: unknown command '${MARKER}'`,
      'ЭТО УЖЕ НЕ НАШ ОТВЕТ',
    ].join('\r\n');

    expect(extractResponse(raw, 'version', MARKER)).toBe('Game version: V 2.0 (b28)');
  });

  it('без эха команды отдаёт всё до метки, а не пустоту', () => {
    const raw = ['Game version: V 2.0 (b28)', `*** ERROR: unknown command '${MARKER}'`].join('\r\n');
    expect(extractResponse(raw, 'version', MARKER)).toBe('Game version: V 2.0 (b28)');
  });

  it('пустой ответ команды — пустая строка, а не мусор', () => {
    const raw = [
      "2026-03-14T19:43:54 432.501 INF Executing command 'saveworld' by Telnet from 127.0.0.1:1",
      `*** ERROR: unknown command '${MARKER}'`,
    ].join('\r\n');

    expect(extractResponse(raw, 'saveworld', MARKER)).toBe('');
  });
});

describe('telnetCommand: проверки до отправки', () => {
  const options = { host: '127.0.0.1', port: 8081, password: 'секрет' };

  // Перевод строки внутри аргумента — это вторая команда. Без этой проверки
  // ник «Lost\r\nshutdown» выключил бы сервер.
  it('команда с переводом строки отвергается, а не отправляется', async () => {
    await expect(telnetCommand(options, 'kick Lost\r\nshutdown')).rejects.toThrow(
      'перевод строки',
    );
    await expect(telnetCommand(options, 'say привет\nshutdown')).rejects.toThrow('перевод строки');
  });

  it('слишком длинная команда отвергается', async () => {
    await expect(telnetCommand(options, 'say ' + 'а'.repeat(1200))).rejects.toThrow('длиннее');
  });

  // Пароль не должен всплыть ни в одном сообщении об ошибке.
  it('пароль в текст ошибки не попадает', async () => {
    await expect(telnetCommand(options, 'say x\nshutdown')).rejects.not.toThrow(/секрет/);
  });
});
