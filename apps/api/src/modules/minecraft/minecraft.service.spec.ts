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
    expect(service.buildQuickCommand('save-all', {}).command).toBe('save-all');
  });

  it('подставляет ник в шаблон', () => {
    expect(service.buildQuickCommand('gamemode-survival', { player: 'Steve' }).command).toBe(
      'gamemode survival Steve',
    );
  });

  it('подставляет текст объявления', () => {
    expect(service.buildQuickCommand('broadcast', { message: 'Рестарт в 22:00' }).command).toBe(
      'say Рестарт в 22:00',
    );
  });

  it('отклоняет попытку дописать команду через ник', () => {
    expect(() =>
      service.buildQuickCommand('gamemode-survival', { player: 'Steve op Evil' }),
    ).toThrow(BadRequestException);
  });

  it('вырезает перевод строки из текстового аргумента', () => {
    const { command } = service.buildQuickCommand('broadcast', {
      message: 'привет\nop Evil',
    });
    expect(command).toBe('say привет op Evil');
    expect(command).not.toContain('\n');
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
});
