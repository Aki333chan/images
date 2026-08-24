import { AddressInfo } from 'net';
import { createServer, Server, Socket } from 'net';
import { AUTH_FAILED_ID, decodePackets, encodePacket, RCON_PACKET_TYPE } from './rcon-packet';

export interface FakeRconOptions {
  password: string;
  /** Ответ на команду. Строка длиннее 4096 будет разбита на пакеты, как у Minecraft. */
  respond?: (command: string) => string;
  /** После авторизации не отвечать ни на что — для проверки таймаута. */
  hangOnCommand?: boolean;
  /**
   * Оборвать соединение после N команд В ЭТОМ соединении. Счётчик у каждого
   * подключения свой: после реконнекта отсчёт начинается заново, что и
   * моделирует «связь оборвалась, переподключились — работает».
   */
  dropAfterCommands?: number;
}

/**
 * Мини-сервер, повторяющий поведение RCON у Minecraft: авторизация, ответ
 * «Unknown request» на пакет неизвестного типа (это и есть маркер конца
 * многопакетного ответа) и разбиение длинных ответов.
 * Нужен только для тестов.
 */
export class FakeRconServer {
  private server: Server;
  /** Команды, которые сервер реально получил, — для проверок в тестах. */
  readonly received: string[] = [];

  constructor(private readonly options: FakeRconOptions) {
    this.server = createServer((socket) => this.handle(socket));
  }

  listen(): Promise<number> {
    return new Promise((resolve) => {
      this.server.listen(0, '127.0.0.1', () => {
        resolve((this.server.address() as AddressInfo).port);
      });
    });
  }

  close(): Promise<void> {
    return new Promise((resolve) => this.server.close(() => resolve()));
  }

  private handle(socket: Socket): void {
    let buffer: Buffer = Buffer.alloc(0);
    let authed = false;
    let commandCount = 0;

    socket.on('error', () => undefined);
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const { packets, rest } = decodePackets(buffer);
      buffer = rest;

      for (const packet of packets) {
        if (packet.type === RCON_PACKET_TYPE.AUTH) {
          const ok = packet.body === this.options.password;
          authed = ok;
          socket.write(
            encodePacket(ok ? packet.id : AUTH_FAILED_ID, RCON_PACKET_TYPE.AUTH_RESPONSE, ''),
          );
          if (!ok) socket.end();
          continue;
        }

        // «Зависший» сервер молчит вообще на всё, включая пакет-маркер, —
        // иначе клиент получил бы ответ и таймаут не наступил бы.
        if (this.options.hangOnCommand) continue;

        if (packet.type === RCON_PACKET_TYPE.EXEC_COMMAND) {
          if (!authed) {
            socket.end();
            return;
          }
          this.received.push(packet.body);
          commandCount += 1;
          if (
            this.options.dropAfterCommands !== undefined &&
            commandCount > this.options.dropAfterCommands
          ) {
            socket.destroy();
            return;
          }

          const body = this.options.respond?.(packet.body) ?? '';
          // Как у Minecraft: длинный ответ уезжает несколькими пакетами.
          for (let i = 0; i < body.length; i += 4096) {
            socket.write(
              encodePacket(packet.id, RCON_PACKET_TYPE.RESPONSE_VALUE, body.slice(i, i + 4096)),
            );
          }
          if (body.length === 0) {
            socket.write(encodePacket(packet.id, RCON_PACKET_TYPE.RESPONSE_VALUE, ''));
          }
          continue;
        }

        // Пакет неизвестного типа — Minecraft отвечает тем же id. На этом
        // клиент понимает, что многопакетный ответ закончился.
        socket.write(
          encodePacket(
            packet.id,
            RCON_PACKET_TYPE.RESPONSE_VALUE,
            `Unknown request ${packet.type.toString(16)}`,
          ),
        );
      }
    });
  }
}
