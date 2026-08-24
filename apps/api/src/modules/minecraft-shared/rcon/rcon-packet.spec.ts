process.env.NODE_ENV = 'test';

import { decodePackets, encodePacket, RCON_PACKET_TYPE, stripColorCodes } from './rcon-packet';

describe('RCON: кодирование пакетов', () => {
  it('формирует пакет по спецификации Source RCON', () => {
    const packet = encodePacket(7, RCON_PACKET_TYPE.EXEC_COMMAND, 'list');
    // size = id(4) + type(4) + body(4) + 2 нуля = 14; всего 18 байт
    expect(packet).toHaveLength(18);
    expect(packet.readInt32LE(0)).toBe(14);
    expect(packet.readInt32LE(4)).toBe(7);
    expect(packet.readInt32LE(8)).toBe(RCON_PACKET_TYPE.EXEC_COMMAND);
    expect(packet.subarray(12, 16).toString()).toBe('list');
    expect(packet[16]).toBe(0);
    expect(packet[17]).toBe(0);
  });

  it('корректно считает длину для многобайтового UTF-8', () => {
    const packet = encodePacket(1, RCON_PACKET_TYPE.EXEC_COMMAND, 'say привет');
    const byteLength = Buffer.from('say привет', 'utf8').length;
    expect(packet.readInt32LE(0)).toBe(4 + 4 + byteLength + 2);
  });
});

describe('RCON: декодирование потока', () => {
  it('читает пакет туда-обратно', () => {
    const { packets, rest } = decodePackets(encodePacket(3, 0, 'hello'));
    expect(rest).toHaveLength(0);
    expect(packets).toEqual([{ id: 3, type: 0, body: 'hello' }]);
  });

  it('разбирает несколько пакетов из одного чанка', () => {
    const buffer = Buffer.concat([encodePacket(1, 0, 'one'), encodePacket(2, 0, 'two')]);
    const { packets } = decodePackets(buffer);
    expect(packets.map((p) => p.body)).toEqual(['one', 'two']);
  });

  it('возвращает остаток, если пакет пришёл не целиком (TCP-фрагментация)', () => {
    const full = encodePacket(1, 0, 'fragmented body');
    const head = full.subarray(0, 10);
    const first = decodePackets(head);
    expect(first.packets).toHaveLength(0);
    expect(first.rest).toHaveLength(10);

    // Досылаем хвост — пакет должен собраться.
    const second = decodePackets(Buffer.concat([first.rest, full.subarray(10)]));
    expect(second.packets).toEqual([{ id: 1, type: 0, body: 'fragmented body' }]);
    expect(second.rest).toHaveLength(0);
  });

  it('читает пустое тело', () => {
    const { packets } = decodePackets(encodePacket(9, 0, ''));
    expect(packets[0]).toEqual({ id: 9, type: 0, body: '' });
  });

  it('бросает ошибку на повреждённом размере', () => {
    const bad = Buffer.alloc(8);
    bad.writeInt32LE(3, 0); // меньше минимально возможного
    expect(() => decodePackets(bad)).toThrow(/Некорректный размер/);
  });
});

describe('stripColorCodes', () => {
  it('убирает §-последовательности', () => {
    expect(stripColorCodes('§aЗелёный §lжирный§r')).toBe('Зелёный жирный');
  });
});
