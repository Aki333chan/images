/**
 * Кодирование/декодирование пакетов Source RCON (тот же протокол у Minecraft).
 *
 * Формат пакета:
 *   int32 LE  size   — длина всего, что идёт ДАЛЬШЕ (id + type + body + 2 нуля)
 *   int32 LE  id
 *   int32 LE  type
 *   bytes     body   — UTF-8
 *   byte      0x00   — терминатор строки
 *   byte      0x00   — терминатор пакета
 */

export const RCON_PACKET_TYPE = {
  /** Клиент -> сервер: авторизация паролем. */
  AUTH: 3,
  /** Сервер -> клиент: результат авторизации. Клиент -> сервер: выполнить команду. */
  AUTH_RESPONSE: 2,
  EXEC_COMMAND: 2,
  /** Сервер -> клиент: тело ответа. Клиент -> сервер: используется как маркер. */
  RESPONSE_VALUE: 0,
} as const;

/** id, которым сервер отвечает на неудачную авторизацию. */
export const AUTH_FAILED_ID = -1;

/** Заголовок: size(4) + id(4) + type(4); плюс два нуля в хвосте. */
const HEADER_SIZE = 12;
const TRAILER_SIZE = 2;

export interface RconPacket {
  id: number;
  type: number;
  body: string;
}

export function encodePacket(id: number, type: number, body: string): Buffer {
  const bodyBuf = Buffer.from(body, 'utf8');
  const size = 4 + 4 + bodyBuf.length + TRAILER_SIZE; // id + type + body + 2 нуля
  const packet = Buffer.alloc(4 + size);
  packet.writeInt32LE(size, 0);
  packet.writeInt32LE(id, 4);
  packet.writeInt32LE(type, 8);
  bodyBuf.copy(packet, HEADER_SIZE);
  // Последние два байта уже нули после Buffer.alloc.
  return packet;
}

/**
 * Вытаскивает из потока все полные пакеты. Возвращает распакованные пакеты и
 * непрочитанный остаток — TCP не гарантирует, что пакет придёт целиком.
 */
export function decodePackets(input: Buffer): { packets: RconPacket[]; rest: Buffer } {
  const packets: RconPacket[] = [];
  let offset = 0;

  while (input.length - offset >= 4) {
    const size = input.readInt32LE(offset);
    // Пакет меньше, чем id+type+терминаторы, — поток повреждён.
    if (size < 4 + 4 + TRAILER_SIZE) {
      throw new Error(`Некорректный размер RCON-пакета: ${size}`);
    }
    if (input.length - offset < 4 + size) break; // пакет ещё не дочитан

    const id = input.readInt32LE(offset + 4);
    const type = input.readInt32LE(offset + 8);
    const bodyStart = offset + HEADER_SIZE;
    const bodyEnd = offset + 4 + size - TRAILER_SIZE;
    packets.push({ id, type, body: input.subarray(bodyStart, bodyEnd).toString('utf8') });
    offset += 4 + size;
  }

  return { packets, rest: input.subarray(offset) };
}

/** Убирает цветовые коды Minecraft (§a, §l и т.п.) из ответа. */
export function stripColorCodes(text: string): string {
  return text.replace(/§[0-9a-fk-orA-FK-OR]/g, '');
}
