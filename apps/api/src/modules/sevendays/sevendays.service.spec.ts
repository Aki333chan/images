process.env.NODE_ENV = 'test';

import { BadRequestException } from '@nestjs/common';
import { arg, optionalArg } from './sevendays-console.service';
import { buildCommand } from './sevendays.service';
import { SEVENDAYS_ACTIONS } from './sevendays-actions.config';

/**
 * Сборка команд для консоли 7 Days to Die.
 *
 * У telnet нет отдельного места для аргументов: команда — это строка, и
 * разбирает её сервер сам, по пробелам и кавычкам. Поэтому склейка команды
 * здесь — то же самое, что склейка SQL: ошибка означает выполнение чужой
 * команды, а не кривой вывод.
 */
describe('arg', () => {
  it('берёт значение в кавычки, чтобы ник с пробелом остался одним аргументом', () => {
    expect(arg('Lost Soul')).toBe('"Lost Soul"');
    expect(arg('Lost')).toBe('"Lost"');
  });

  it('обрезает пробелы по краям', () => {
    expect(arg('  Lost  ')).toBe('"Lost"');
  });

  // Своих кавычек внутри значения консоль не понимает никак — ни
  // экранирования, ни удвоения. Тихо вырезать их нельзя: изменённый ник
  // забанил бы не того человека.
  it('значение с кавычкой отвергается, а не «чинится»', () => {
    expect(() => arg('Lost" ban add Ghost 1 years')).toThrow(BadRequestException);
    expect(() => arg('Lost"', 'Игрок')).toThrow(/двойные кавычки/);
  });

  it('перевод строки внутри значения отвергается: это вторая команда', () => {
    expect(() => arg('Lost\nshutdown')).toThrow(/перевод строки/);
    expect(() => arg('Lost\r\nshutdown')).toThrow(/перевод строки/);
  });

  it('пустое значение отвергается с указанием поля', () => {
    expect(() => arg('   ', 'Игрок')).toThrow(/Игрок не может быть пустым/);
  });

  it('необязательное пустое значение просто исчезает', () => {
    expect(optionalArg('')).toBeNull();
    expect(optionalArg(undefined)).toBeNull();
    expect(optionalArg('  ')).toBeNull();
    expect(optionalArg('грифинг')).toBe('"грифинг"');
  });
});

describe('buildCommand', () => {
  const announce = SEVENDAYS_ACTIONS.find((a) => a.id === 'announce')!;
  const save = SEVENDAYS_ACTIONS.find((a) => a.id === 'save')!;

  it('подставляет аргумент в шаблон в кавычках', () => {
    expect(buildCommand(announce, { message: 'Рестарт через 5 минут' })).toBe(
      'say "Рестарт через 5 минут"',
    );
  });

  it('действие без аргументов остаётся голой командой', () => {
    expect(buildCommand(save, {})).toBe('saveworld');
  });

  it('незаполненный обязательный аргумент — понятная ошибка, а не пустая команда', () => {
    expect(() => buildCommand(announce, {})).toThrow(/Не заполнено поле «Текст объявления»/);
    expect(() => buildCommand(announce, { message: '  ' })).toThrow(/Не заполнено поле/);
  });

  // Лишнее поле в теле запроса не должно превращаться в лишнее слово в
  // команде игрового сервера.
  it('незаявленные аргументы в команду не попадают', () => {
    expect(buildCommand(save, { message: 'shutdown', anything: 'ban add Ghost 1 years' })).toBe(
      'saveworld',
    );
  });

  it('попытка вырваться из кавычек через аргумент отвергается', () => {
    expect(() => buildCommand(announce, { message: 'привет" ; shutdown' })).toThrow(
      BadRequestException,
    );
  });

  it('все объявленные действия собираются без незакрытых подстановок', () => {
    for (const action of SEVENDAYS_ACTIONS) {
      const args = Object.fromEntries(action.args.map((a) => [a.name, 'значение']));
      expect(buildCommand(action, args)).not.toMatch(/[{}]/);
    }
  });
});
