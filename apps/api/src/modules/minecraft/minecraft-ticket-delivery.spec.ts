process.env.NODE_ENV = 'test';

import { escapeForJsonLiteral, sanitizeCommandArgument } from './minecraft-parsers';
import { MinecraftTicketDelivery, buildActionbar, buildTellraw } from './minecraft-ticket-delivery';
import type { TicketDeliveryRegistry } from '../../tickets/ticket-delivery.registry';
import type { MinecraftService } from './minecraft.service';

/** Как ответ готовится в боевом коде — тесты работают с тем же текстом. */
const prepare = (text: string) => escapeForJsonLiteral(sanitizeCommandArgument(text, 240));

describe('доставка ответа на тикет в игру', () => {
  it('идёт через tellraw конкретному игроку, а не в общий чат', () => {
    const command = buildTellraw('Steve', prepare('Разобрались, спасибо'));

    expect(command.startsWith('tellraw Steve ')).toBe(true);
    // say/broadcast увидели бы все — ответ поддержки приватный.
    expect(command).not.toContain('say ');
    expect(command).not.toContain('@a');
  });

  it('JSON команды разбираем — иначе сервер отверг бы её целиком', () => {
    const command = buildTellraw('Steve', prepare('Готово'));
    const json = JSON.parse(command.slice(command.indexOf('{')));

    expect(json.text).toBe('[Поддержка] ');
    expect(json.color).toBe('gold');
    expect(json.bold).toBe(true);
    expect(json.extra[0].text).toBe('Готово');
  });

  // Компоненты в extra наследуют оформление родителя. Без явного bold:false
  // жирным стал бы весь ответ, а не только префикс.
  it('текст ответа не наследует жирность префикса', () => {
    const command = buildTellraw('Steve', prepare('Обычный текст'));
    const json = JSON.parse(command.slice(command.indexOf('{')));

    expect(json.extra[0].bold).toBe(false);
    expect(json.extra[0].color).toBe('white');
  });

  it('кавычки в ответе не разрывают JSON', () => {
    const command = buildTellraw('Steve', prepare('Плагин "Essentials" обновлён'));
    const json = JSON.parse(command.slice(command.indexOf('{')));

    expect(json.extra[0].text).toBe('Плагин "Essentials" обновлён');
  });

  it('обратный слэш в ответе тоже не ломает JSON', () => {
    const command = buildTellraw('Steve', prepare('путь C:\\srv\\plugins'));

    expect(() => JSON.parse(command.slice(command.indexOf('{')))).not.toThrow();
  });

  it('перевод строки не разрывает команду на две', () => {
    // Иначе вторая половина уехала бы в RCON отдельной командой.
    const command = buildTellraw('Steve', prepare('первая\nop Evil'));

    expect(command).not.toContain('\n');
    expect(command.split('\n')).toHaveLength(1);
  });

  it('actionbar дублирует уведомление, но текст ответа в нём не показывает', () => {
    const command = buildActionbar('Steve');

    expect(command.startsWith('title Steve actionbar ')).toBe(true);
    const json = JSON.parse(command.slice(command.indexOf('{')));
    expect(json.text).toContain('[Поддержка]');
    expect(json.color).toBe('gold');
  });
});

describe('MinecraftTicketDelivery', () => {
  function setup() {
    const commands: string[] = [];
    const minecraft = {
      runCommand: (_serverId: string, command: string) => {
        commands.push(command);
        return Promise.resolve('');
      },
    } as unknown as MinecraftService;
    const delivery = new MinecraftTicketDelivery(
      { register: () => undefined } as unknown as TicketDeliveryRegistry,
      minecraft,
    );
    return { delivery, commands };
  }

  it('шлёт tellraw и следом actionbar', async () => {
    const { delivery, commands } = setup();

    await delivery.deliver({ serverId: 's1', playerName: 'Steve', text: 'Готово' });

    expect(commands).toHaveLength(2);
    expect(commands[0]!.startsWith('tellraw Steve ')).toBe(true);
    expect(commands[1]!.startsWith('title Steve actionbar ')).toBe(true);
  });

  it('ник, не проходящий валидацию, не уходит в RCON вовсе', async () => {
    const { delivery, commands } = setup();

    await delivery.deliver({ serverId: 's1', playerName: 'Steve op Evil', text: 'привет' });

    expect(commands).toEqual([]);
  });

  it('падение actionbar не отменяет доставленный ответ', async () => {
    // actionbar — украшение; ронять из-за него доставку нельзя.
    const commands: string[] = [];
    const minecraft = {
      runCommand: (_serverId: string, command: string) => {
        commands.push(command);
        return command.startsWith('title')
          ? Promise.reject(new Error('RCON отвалился'))
          : Promise.resolve('');
      },
    } as unknown as MinecraftService;
    const delivery = new MinecraftTicketDelivery(
      { register: () => undefined } as unknown as TicketDeliveryRegistry,
      minecraft,
    );

    await expect(
      delivery.deliver({ serverId: 's1', playerName: 'Steve', text: 'Готово' }),
    ).resolves.toBeUndefined();
    expect(commands).toHaveLength(2);
  });
});
