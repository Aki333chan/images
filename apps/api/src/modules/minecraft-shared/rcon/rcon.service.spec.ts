process.env.NODE_ENV = 'test';

import { RconConnection, RconAuthError } from './rcon-connection';
import { RconService } from './rcon.service';
import { FakeRconServer } from './fake-rcon-server';

const PASSWORD = 'секретный-пароль';

describe('RconConnection (против настоящего TCP-сервера)', () => {
  let server: FakeRconServer;
  let port: number;

  afterEach(async () => {
    await server?.close();
  });

  it('авторизуется и выполняет команду', async () => {
    server = new FakeRconServer({
      password: PASSWORD,
      respond: (cmd) =>
        cmd === 'list' ? 'There are 1 of a max of 20 players online: Steve' : 'Unknown command',
    });
    port = await server.listen();

    const conn = new RconConnection({ host: '127.0.0.1', port, password: PASSWORD });
    await conn.connect();
    const output = await conn.send('list');
    expect(output).toContain('Steve');
    expect(server.received).toEqual(['list']);
    conn.close();
  });

  it('отклоняет неверный пароль и не раскрывает его в сообщении', async () => {
    server = new FakeRconServer({ password: PASSWORD });
    port = await server.listen();

    // Пароль намеренно не пересекается с текстом ошибки, иначе проверка ниже
    // прошла бы случайно.
    const wrongPassword = 'hunter2-QWERTY-zzz';
    const conn = new RconConnection({ host: '127.0.0.1', port, password: wrongPassword });
    const error = await conn.connect().catch((e: Error) => e);
    expect(error).toBeInstanceOf(RconAuthError);
    expect((error as Error).message).not.toContain(wrongPassword);
  });

  it('склеивает многопакетный ответ целиком', async () => {
    const long = 'x'.repeat(10_000);
    server = new FakeRconServer({ password: PASSWORD, respond: () => long });
    port = await server.listen();

    const conn = new RconConnection({ host: '127.0.0.1', port, password: PASSWORD });
    await conn.connect();
    // Ответ приходит тремя пакетами — клиент должен собрать их в одну строку.
    await expect(conn.send('whitelist list')).resolves.toHaveLength(10_000);
    conn.close();
  });

  it('падает по таймауту, если сервер не отвечает', async () => {
    server = new FakeRconServer({ password: PASSWORD, hangOnCommand: true });
    port = await server.listen();

    const conn = new RconConnection({
      host: '127.0.0.1',
      port,
      password: PASSWORD,
      commandTimeoutMs: 300,
    });
    await conn.connect();
    await expect(conn.send('list')).rejects.toThrow(/таймаут/);
    expect(conn.connected).toBe(false); // соединение сброшено, состояние не «залипло»
  });

  it('не пишет пароль в адрес для логов', async () => {
    server = new FakeRconServer({ password: PASSWORD });
    port = await server.listen();
    const conn = new RconConnection({ host: '127.0.0.1', port, password: PASSWORD });
    expect(conn.address).toBe(`127.0.0.1:${port}`);
    expect(conn.address).not.toContain(PASSWORD);
  });
});

describe('RconService (пул, очередь, реконнект)', () => {
  let server: FakeRconServer;
  let service: RconService;

  beforeEach(() => {
    service = new RconService();
  });

  afterEach(async () => {
    service.onModuleDestroy();
    await server?.close();
  });

  it('выполняет команды строго последовательно на одном соединении', async () => {
    server = new FakeRconServer({ password: PASSWORD, respond: (cmd) => `ok:${cmd}` });
    const port = await server.listen();
    const options = { host: '127.0.0.1', port, password: PASSWORD };

    const results = await Promise.all([
      service.execute('srv-1', options, 'one'),
      service.execute('srv-1', options, 'two'),
      service.execute('srv-1', options, 'three'),
    ]);

    expect(results).toEqual(['ok:one', 'ok:two', 'ok:three']);
    // Порядок на сервере сохранён — ответы не перепутались между собой.
    expect(server.received).toEqual(['one', 'two', 'three']);
  });

  it('переподключается и повторяет команду после обрыва', async () => {
    server = new FakeRconServer({
      password: PASSWORD,
      respond: (cmd) => `ok:${cmd}`,
      dropAfterCommands: 1,
    });
    const port = await server.listen();
    const options = { host: '127.0.0.1', port, password: PASSWORD };

    await expect(service.execute('srv-1', options, 'first')).resolves.toBe('ok:first');
    // Сервер рвёт соединение на второй команде — сервис должен переподключиться.
    await expect(service.execute('srv-1', options, 'second')).resolves.toBe('ok:second');
  });

  it('отдаёт понятную 503-ошибку, если сервер недоступен', async () => {
    server = new FakeRconServer({ password: PASSWORD });
    const port = await server.listen();
    await server.close();

    await expect(
      service.execute('srv-1', { host: '127.0.0.1', port, password: PASSWORD }, 'list'),
    ).rejects.toThrow(/недоступен по RCON/);
  });

  it('на неверный пароль не долбится повторно, а сообщает о настройках', async () => {
    server = new FakeRconServer({ password: PASSWORD });
    const port = await server.listen();

    await expect(
      service.execute('srv-1', { host: '127.0.0.1', port, password: 'нет' }, 'list'),
    ).rejects.toThrow(/отверг пароль/);
  });
});
