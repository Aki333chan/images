process.env.NODE_ENV = 'test';

import { BadRequestException, NotFoundException } from '@nestjs/common';
import { MinecraftService } from './minecraft.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { MinecraftConfigService } from './minecraft-config.service';
import type { RconService } from './rcon/rcon.service';
import type { CompanionService } from './companion.service';

describe('MinecraftService.buildQuickCommand', () => {
  const service = new MinecraftService(
    {} as PrismaService,
    {} as MinecraftConfigService,
    {} as RconService,
    {} as CompanionService,
  );

  it('собирает команду без аргументов', () => {
    expect(service.buildQuickCommand('save-all', {}).commands).toEqual(['save-all']);
  });

  it('подставляет ник в шаблон', () => {
    expect(service.buildQuickCommand('ess-heal', { player: 'Steve' }).commands).toEqual([
      'heal Steve',
    ]);
  });

  it('подставляет текст объявления', () => {
    expect(service.buildQuickCommand('broadcast', { message: 'Рестарт в 22:00' }).commands).toEqual(
      ['say Рестарт в 22:00'],
    );
  });

  it('отклоняет попытку дописать команду через ник', () => {
    // gamemode-survival убрали, проверяем на действии, где ник тоже есть.
    expect(() => service.buildQuickCommand('ess-heal', { player: 'Steve op Evil' })).toThrow(
      BadRequestException,
    );
  });

  it('вырезает перевод строки из текстового аргумента', () => {
    const { commands } = service.buildQuickCommand('broadcast', {
      message: 'привет\nop Evil',
    });
    expect(commands).toEqual(['say привет op Evil']);
  });

  it('требует обязательный аргумент', () => {
    expect(() => service.buildQuickCommand('broadcast', {})).toThrow(BadRequestException);
  });

  it('не знает неизвестных команд', () => {
    expect(() => service.buildQuickCommand('rm-rf', {})).toThrow(NotFoundException);
  });

  it('в списке команд для фронта нет самих шаблонов', () => {
    const commands = service.listQuickCommands(['Essentials']);
    expect(commands.length).toBeGreaterThan(0);
    for (const command of commands) {
      expect(command).not.toHaveProperty('template');
    }
  });

  it('без списка плагинов остаются только ванильные действия', () => {
    // null = список получить не удалось. Показывать кнопки чужих плагинов
    // наугад нельзя: они привели бы в «Unknown command».
    const commands = service.listQuickCommands(null);
    expect(commands.length).toBeGreaterThan(0);
    for (const command of commands) {
      expect(command.plugin).toBeNull();
    }
  });

  it('действия плагина появляются, только если он установлен', () => {
    const without = service.listQuickCommands(['LuckPerms']);
    expect(without.some((c) => c.id === 'ess-heal')).toBe(false);

    const withEssentials = service.listQuickCommands(['LuckPerms', 'Essentials']);
    expect(withEssentials.some((c) => c.id === 'ess-heal')).toBe(true);
  });

  it('имя плагина сверяется без учёта регистра', () => {
    // Bukkit отдаёт имя как есть; полагаться на его регистр не стоит.
    const commands = service.listQuickCommands(['essentials']);
    expect(commands.some((c) => c.id === 'ess-heal')).toBe(true);
  });

  // ---------------------------------------------- Объявление через /title

  it('строит две команды: подзаголовок раньше заголовка', () => {
    const { commands } = service.buildQuickCommand('title-announce', {
      message: 'Рестарт',
      subtitle: 'через 5 минут',
    });

    expect(commands).toHaveLength(2);
    // Порядок значим: vanilla показывает подзаголовок, только если он
    // отправлен ДО заголовка.
    expect(commands[0]).toContain('subtitle');
    expect(commands[1]).toContain('title @a title');
    expect(commands[0]).toContain('через 5 минут');
    expect(commands[1]).toContain('Рестарт');
  });

  it('без подзаголовка остаётся одна команда, а не пустая надпись', () => {
    const { commands } = service.buildQuickCommand('title-announce', { message: 'Рестарт' });

    expect(commands).toHaveLength(1);
    expect(commands[0]).toContain('title @a title');
    expect(commands[0]).not.toContain('subtitle');
  });

  it('кавычки в тексте экранируются и JSON остаётся разбираемым', () => {
    const { commands } = service.buildQuickCommand('title-announce', {
      message: 'Сервер "Аурум" ждёт',
    });

    // Вытаскиваем JSON-литерал и разбираем его: если экранирование неверное,
    // JSON.parse упадёт — ровно так же, как упал бы игровой сервер.
    const json = commands[0]!.slice(commands[0]!.indexOf('{'));
    expect(() => JSON.parse(json)).not.toThrow();
    expect(JSON.parse(json).text).toBe('Сервер "Аурум" ждёт');
    expect(JSON.parse(json).color).toBe('gold');
    expect(JSON.parse(json).bold).toBe(true);
  });

  it('обратный слэш в тексте тоже не ломает JSON', () => {
    const { commands } = service.buildQuickCommand('title-announce', { message: 'путь C:\\srv' });
    const json = commands[0]!.slice(commands[0]!.indexOf('{'));
    expect(() => JSON.parse(json)).not.toThrow();
  });

  it('перевод строки в тексте не разрывает команду на две', () => {
    // Иначе вторая половина уехала бы в RCON отдельной командой.
    const { commands } = service.buildQuickCommand('title-announce', {
      message: 'первая\nвторая',
    });
    expect(commands).toHaveLength(1);
    expect(commands[0]).not.toContain('\n');
  });

  it('убранные действия больше не предлагаются', () => {
    // Регрессия на правку по итогам эксплуатации: их убрали намеренно.
    const ids = service.listQuickCommands(['Essentials']).map((c) => c.id);
    expect(ids).not.toContain('tp-spawn');
    expect(ids).not.toContain('gamemode-survival');
  });
});
