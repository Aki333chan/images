import { Socket } from 'net';
import {
  AUTH_FAILED_ID,
  decodePackets,
  encodePacket,
  RCON_PACKET_TYPE,
  RconPacket,
} from './rcon-packet';

export class RconAuthError extends Error {}
export class RconTimeoutError extends Error {}
export class RconClosedError extends Error {}

export interface RconConnectionOptions {
  host: string;
  port: number;
  password: string;
  /** Таймаут установки TCP-соединения и авторизации. */
  connectTimeoutMs?: number;
  /** Таймаут ожидания ответа на команду. */
  commandTimeoutMs?: number;
}

interface PendingCommand {
  commandId: number;
  markerId: number;
  chunks: string[];
  resolve: (value: string) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

/**
 * Одно RCON-соединение. Команды отправляются строго по одной: следующая
 * уходит только после ответа на предыдущую (см. RconService — очередь).
 *
 * Многопакетные ответы: Minecraft на пакет неизвестного типа отвечает
 * «Unknown request <hex>» с ТЕМ ЖЕ id. Поэтому сразу за командой шлём пустой
 * пакет-маркер с другим id: всё, что пришло до ответа на маркер, — тело
 * ответа на команду. Это надёжнее, чем ждать по таймауту.
 *
 * Пароль хранится только в поле объекта и никогда не логируется.
 */
export class RconConnection {
  private socket: Socket | null = null;
  // Явный тип: Buffer.subarray возвращает Buffer<ArrayBufferLike>, а вывод из
  // Buffer.alloc дал бы более узкий Buffer<ArrayBuffer>.
  private buffer: Buffer = Buffer.alloc(0);
  private nextId = 1;
  private pending: PendingCommand | null = null;
  private authResolve: (() => void) | null = null;
  private authReject: ((e: Error) => void) | null = null;
  private authId = 0;
  private closedReason: Error | null = null;

  constructor(private readonly options: RconConnectionOptions) {}

  get connected(): boolean {
    return this.socket !== null && !this.socket.destroyed && this.closedReason === null;
  }

  /** Адрес для логов — без пароля. */
  get address(): string {
    return `${this.options.host}:${this.options.port}`;
  }

  connect(): Promise<void> {
    if (this.connected) return Promise.resolve();
    const connectTimeout = this.options.connectTimeoutMs ?? 5000;

    return new Promise<void>((resolve, reject) => {
      const socket = new Socket();
      this.socket = socket;
      this.buffer = Buffer.alloc(0);
      this.closedReason = null;

      const timer = setTimeout(() => {
        this.destroy(new RconTimeoutError(`RCON ${this.address}: таймаут подключения`));
      }, connectTimeout);

      const settleOk = () => {
        clearTimeout(timer);
        resolve();
      };
      const settleErr = (e: Error) => {
        clearTimeout(timer);
        reject(e);
      };

      this.authResolve = settleOk;
      this.authReject = settleErr;

      socket.on('data', (chunk) => this.onData(chunk));
      socket.on('error', (err) => {
        this.destroy(new RconClosedError(`RCON ${this.address}: ${err.message}`));
      });
      socket.on('close', () => {
        this.destroy(new RconClosedError(`RCON ${this.address}: соединение закрыто`));
      });

      socket.connect(this.options.port, this.options.host, () => {
        // Авторизация: пароль уходит в сокет, но нигде не логируется.
        this.authId = this.nextId++;
        socket.write(encodePacket(this.authId, RCON_PACKET_TYPE.AUTH, this.options.password));
      });
    });
  }

  /** Выполняет команду и возвращает полный (склеенный) ответ сервера. */
  send(command: string): Promise<string> {
    if (!this.connected || !this.socket) {
      return Promise.reject(this.closedReason ?? new RconClosedError('RCON: нет соединения'));
    }
    if (this.pending) {
      return Promise.reject(new Error('RCON: предыдущая команда ещё выполняется'));
    }

    const socket = this.socket;
    const commandId = this.nextId++;
    const markerId = this.nextId++;
    const timeoutMs = this.options.commandTimeoutMs ?? 5000;

    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        // Ответ мог прийти частично — состояние соединения больше не надёжно.
        this.destroy(new RconTimeoutError(`RCON ${this.address}: таймаут ответа на команду`));
      }, timeoutMs);

      this.pending = { commandId, markerId, chunks: [], resolve, reject, timer };
      socket.write(encodePacket(commandId, RCON_PACKET_TYPE.EXEC_COMMAND, command));
      socket.write(encodePacket(markerId, RCON_PACKET_TYPE.RESPONSE_VALUE, ''));
    });
  }

  close(): void {
    this.destroy(new RconClosedError('RCON: соединение закрыто клиентом'));
  }

  private onData(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    let packets: RconPacket[];
    try {
      const decoded = decodePackets(this.buffer);
      packets = decoded.packets;
      this.buffer = decoded.rest;
    } catch (e) {
      this.destroy(new RconClosedError(`RCON ${this.address}: ${(e as Error).message}`));
      return;
    }
    for (const packet of packets) this.onPacket(packet);
  }

  private onPacket(packet: RconPacket): void {
    // Этап авторизации.
    if (this.authResolve || this.authReject) {
      if (packet.type === RCON_PACKET_TYPE.AUTH_RESPONSE) {
        const resolveAuth = this.authResolve;
        const rejectAuth = this.authReject;
        this.authResolve = null;
        this.authReject = null;
        if (packet.id === AUTH_FAILED_ID) {
          // Пароль в сообщение не попадает.
          const error = new RconAuthError(`RCON ${this.address}: неверный пароль`);
          this.destroy(error);
          rejectAuth?.(error);
        } else {
          resolveAuth?.();
        }
        return;
      }
      // Пустой RESPONSE_VALUE перед AUTH_RESPONSE — норма, игнорируем.
      return;
    }

    const pending = this.pending;
    if (!pending) return; // незапрошенный пакет — игнорируем

    if (packet.id === pending.commandId) {
      pending.chunks.push(packet.body);
      return;
    }
    if (packet.id === pending.markerId) {
      clearTimeout(pending.timer);
      this.pending = null;
      pending.resolve(pending.chunks.join(''));
    }
  }

  private destroy(error: Error): void {
    if (this.closedReason) return;
    this.closedReason = error;

    const pending = this.pending;
    this.pending = null;
    if (pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }

    // Если рвётся соединение на этапе авторизации — сообщаем ожидающему connect().
    const rejectAuth = this.authReject;
    this.authResolve = null;
    this.authReject = null;
    rejectAuth?.(error);

    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.removeAllListeners();
      socket.destroy();
    }
  }
}
