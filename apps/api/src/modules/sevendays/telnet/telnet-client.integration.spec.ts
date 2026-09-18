process.env.NODE_ENV = 'test';

import { createServer, Socket } from 'net';
import { once } from 'events';
import { telnetCommand, TelnetOptions } from './telnet-client';

const BANNER = "Press 'help' to get a list of all commands. Press 'exit' to end session.\r\n";
const PASSWORD = 'test-only-not-a-real-secret';

// Real TCP sockets, not socket mocks: exercise packet boundaries and half-close.
async function withServer(
  connected: (socket: Socket, later: (work: () => void, ms: number) => void) => void,
  check: (options: TelnetOptions) => Promise<void>,
): Promise<void> {
  const sockets = new Set<Socket>();
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const later = (work: () => void, ms: number) => {
    const timer = setTimeout(() => {
      timers.delete(timer);
      work();
    }, ms);
    timers.add(timer);
  };
  const server = createServer((socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {
      /* Some failure scenarios deliberately close the peer. */
    });
    connected(socket, later);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Expected TCP address');
  try {
    await check({ host: '127.0.0.1', port: address.port, password: PASSWORD, timeoutMs: 2000 });
  } finally {
    for (const timer of timers) clearTimeout(timer);
    for (const socket of sockets) socket.destroy();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

function lines(socket: Socket, receive: (line: string) => void): void {
  let buffer = '';
  socket.setEncoding('utf8');
  socket.on('data', (chunk) => {
    buffer += chunk;
    let end: number;
    while ((end = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, end).replace(/\r$/, '');
      buffer = buffer.slice(end + 1);
      receive(line);
    }
  });
}

function reply(marker: string, body = 'Total of 0 in the game'): string {
  return (
    "2026-09-18T12:04:45 205.392 INF Executing command 'lp' by Telnet from 127.0.0.1:1\r\n" +
    body +
    `\r\n*** ERROR: unknown command '${marker}'\r\n`
  );
}

describe('7DTD telnet real TCP lifecycle', () => {
  it('waits for delayed auth ACK and requests exit before closing', async () => {
    const received: string[] = [];
    let authenticated = false;
    let premature = false;
    let gotExit = false;
    let peerEndedBeforeExit = false;
    await withServer(
      (socket, later) => {
        socket.write('Please enter pass');
        later(() => socket.write('word:\r\n'), 10);
        socket.on('end', () => {
          peerEndedBeforeExit = !gotExit;
        });
        lines(socket, (line) => {
          received.push(line);
          if (line === PASSWORD) {
            later(() => {
              authenticated = true;
              socket.write('Logon successful.\r\n' + BANNER);
            }, 250);
          } else {
            if (!authenticated) premature = true;
            if (line.startsWith('aurum')) socket.write(reply(line));
            if (line === 'exit') {
              gotExit = true;
              // Game may still have trailing log data to flush before close.
              socket.write('2026-09-18T12:04:45 205.493 INF Time: trailing log\r\n');
              later(() => socket.end(), 30);
            }
          }
        });
      },
      async (options) => {
        expect(await telnetCommand(options, 'lp')).toBe('Total of 0 in the game');
        expect(premature).toBe(false);
        expect(gotExit).toBe(true);
        expect(peerEndedBeforeExit).toBe(false);
        expect(received.filter((line) => line === PASSWORD)).toHaveLength(1);
        expect(received.filter((line) => line === 'lp')).toHaveLength(1);
      },
    );
  });

  it('rejects a wrong password without sending a command', async () => {
    const received: string[] = [];
    await withServer(
      (socket) => {
        socket.write('Please enter password:\r\n');
        lines(socket, (line) => {
          received.push(line);
          socket.write('Password incorrect\r\n');
        });
      },
      async (options) => {
        await expect(telnetCommand(options, 'lp')).rejects.toThrow('Пароль');
        expect(received).toEqual([PASSWORD]);
      },
    );
  });

  it('times out silent authentication without the old 150ms blind dispatch', async () => {
    const received: string[] = [];
    await withServer(
      (socket) => {
        socket.write('Please enter password:\r\n');
        lines(socket, (line) => received.push(line));
      },
      async (options) => {
        await expect(telnetCommand({ ...options, timeoutMs: 250 }, 'lp')).rejects.toThrow(
          'авторизацию',
        );
        expect(received).toEqual([PASSWORD]);
      },
    );
  });

  it('supports a passwordless local welcome banner', async () => {
    const received: string[] = [];
    await withServer(
      (socket) => {
        socket.write(BANNER);
        lines(socket, (line) => {
          received.push(line);
          if (line.startsWith('aurum')) socket.write(reply(line));
          if (line === 'exit') socket.end();
        });
      },
      async (options) => {
        expect(await telnetCommand({ ...options, password: '' }, 'lp')).toContain('Total of 0');
        expect(received[0]).toBe('lp');
        expect(received[2]).toBe('exit');
      },
    );
  });

  it('does not send an empty password or silently bypass a configured password', async () => {
    let sent = false;
    await withServer(
      (socket) => {
        socket.on('data', () => {
          sent = true;
        });
        socket.write('Please enter password:\r\n');
      },
      async (options) => {
        await expect(telnetCommand({ ...options, password: '' }, 'lp')).rejects.toThrow(
          'требует пароль',
        );
        expect(sent).toBe(false);
      },
    );
    await withServer(
      (socket) => {
        socket.write(BANNER);
      },
      async (options) => {
        await expect(telnetCommand({ ...options, timeoutMs: 100 }, 'lp')).rejects.toThrow(
          'авторизацию',
        );
      },
    );
  });

  it('preserves split UTF-8 and waits for the complete marker line', async () => {
    let exitedEarly = false;
    let complete = false;
    await withServer(
      (socket, later) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line.startsWith('aurum')) {
            const payload = Buffer.from(reply(line, 'Игрок Żółw'));
            const split = payload.indexOf(Buffer.from('И')) + 1;
            socket.write(payload.subarray(0, split));
            later(() => socket.write(payload.subarray(split, payload.length - 2)), 10);
            later(() => {
              complete = true;
              socket.write('\r\n');
            }, 30);
          }
          if (line === 'exit') {
            exitedEarly = !complete;
            socket.end();
          }
        });
      },
      async (options) => {
        expect(await telnetCommand(options, 'lp')).toBe('Игрок Żółw');
        expect(exitedEarly).toBe(false);
      },
    );
  });

  it('does not treat marker text inside a log/chat line as response completion', async () => {
    let complete = false;
    let earlyExit = false;
    await withServer(
      (socket, later) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line.startsWith('aurum')) {
            socket.write(`2026-09-18T12:04:45 205.493 INF Chat: unknown command '${line}'\r\n`);
            later(() => {
              complete = true;
              socket.write(reply(line));
            }, 30);
          }
          if (line === 'exit') {
            earlyExit = !complete;
            socket.end();
          }
        });
      },
      async (options) => {
        expect(await telnetCommand(options, 'lp')).toBe('Total of 0 in the game');
        expect(earlyExit).toBe(false);
      },
    );
  });

  it('bounds closing time if the server ignores exit, preserving the confirmed result', async () => {
    let gotExit = false;
    await withServer(
      (socket) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line.startsWith('aurum')) socket.write(reply(line));
          if (line === 'exit') gotExit = true;
        });
      },
      async (options) => {
        expect(await telnetCommand(options, 'lp')).toBe('Total of 0 in the game');
        expect(gotExit).toBe(true);
      },
    );
  });

  it('rejects premature EOF and never retries the command', async () => {
    let commands = 0;
    await withServer(
      (socket) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line === 'lp') {
            commands++;
            socket.end('partial output\r\n');
          }
        });
      },
      async (options) => {
        await expect(telnetCommand(options, 'lp')).rejects.toThrow('до подтверждения');
        expect(commands).toBe(1);
      },
    );
  });

  it('times out after dispatch without retrying a possibly executed operation', async () => {
    let commands = 0;
    await withServer(
      (socket) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line === 'lp') commands++;
        });
      },
      async (options) => {
        await expect(telnetCommand({ ...options, timeoutMs: 100 }, 'lp')).rejects.toThrow(
          'не повторяйте',
        );
        expect(commands).toBe(1);
      },
    );
  });

  it('limits incoming data instead of retaining unlimited console output', async () => {
    await withServer(
      (socket) => {
        socket.write(Buffer.alloc(1024 * 1024 + 1, 'x'));
      },
      async (options) => {
        await expect(telnetCommand(options, 'lp')).rejects.toThrow('размер');
      },
    );
  });

  it('isolates concurrent requests and cleans up each session', async () => {
    const markers = new Set<string>();
    let exits = 0;
    await withServer(
      (socket) => {
        socket.write('Logon successful.\r\n');
        lines(socket, (line) => {
          if (line.startsWith('aurum')) {
            markers.add(line);
            socket.write(reply(line));
          }
          if (line === 'exit') {
            exits++;
            socket.end();
          }
        });
      },
      async (options) => {
        const results = await Promise.all([
          telnetCommand(options, 'lp'),
          telnetCommand(options, 'lp'),
        ]);
        expect(results).toEqual(['Total of 0 in the game', 'Total of 0 in the game']);
        expect(markers.size).toBe(2);
        expect(exits).toBe(2);
      },
    );
  });

  it('cleans up synchronous socket configuration failures without exposing connection details', async () => {
    const options = { host: 'private-host', port: -1, password: PASSWORD, timeoutMs: 2000 };
    await expect(telnetCommand(options, 'lp')).rejects.toThrow('открыть telnet');
    await expect(telnetCommand(options, 'lp')).rejects.not.toThrow(/private-host|test-only/);
  });
});
