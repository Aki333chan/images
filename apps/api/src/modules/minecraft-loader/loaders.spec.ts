process.env.NODE_ENV = 'test';

import {
  LOADER_LABELS,
  MINECRAFT_FORGE_PERMISSIONS,
  MINECRAFT_NEOFORGE_PERMISSIONS,
  isMinecraftLoaderModule,
  isMinecraftModule,
  loaderPermissions,
  type Role,
} from '@aurum/shared';
import { minecraftManifest } from '../minecraft/minecraft.def';
import { minecraftForgeManifest, minecraftNeoForgeManifest } from './loaders.def';
import { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import type { RconService } from '../minecraft-shared/rcon/rcon.service';

/**
 * Forge и NeoForge — РАЗНЫЕ загрузчики, и эти тесты держат именно это.
 *
 * NeoForge отделился от Forge форком, а с Minecraft 1.20.2 переименовал
 * внутренние пакеты (net.minecraftforge.* -> net.neoforged.*): мод одного на
 * другом не загружается вовсе. Если кто-то соберётся «упростить» и слить их
 * в один модуль или дать им общие права, сломается что-то отсюда.
 */

const manifests = [minecraftForgeManifest, minecraftNeoForgeManifest];

describe('манифесты загрузчиков', () => {
  it('id и названия разные', () => {
    expect(minecraftForgeManifest.id).toBe('minecraft-forge');
    expect(minecraftNeoForgeManifest.id).toBe('minecraft-neoforge');
    expect(minecraftForgeManifest.displayName).toBe('Minecraft (Forge)');
    expect(minecraftNeoForgeManifest.displayName).toBe('Minecraft (NeoForge)');
  });

  it('у обоих ровно пять заявленных возможностей', () => {
    for (const manifest of manifests) {
      expect(Object.keys(manifest.capabilities).sort()).toEqual([
        'banKick',
        'console',
        'playerList',
        'quickCommands',
        'whitelist',
      ]);
    }
  });

  it('возможности, требующие плагина Bukkit, не объявлены вовсе', () => {
    // Не 'requires-plugin', а именно отсутствие ключа: плагина, который бы их
    // включил, на загрузчике модов не бывает в принципе, и обещать «поставьте
    // плагин» было бы неправдой.
    for (const manifest of manifests) {
      expect(manifest.capabilities.inventory).toBeUndefined();
      expect(manifest.capabilities.tickets).toBeUndefined();
    }
  });

  it('у Paper инвентарь по-прежнему есть — общий слой его не забрал', () => {
    expect(minecraftManifest.capabilities.inventory).toBe('requires-plugin');
    expect(minecraftManifest.capabilities.tickets).toBe(true);
  });
});

describe('изоляция прав между модулями', () => {
  it('ни один ключ не пересекается между тремя модулями', () => {
    // Самое важное свойство всей задачи: право на Forge-сервер не должно
    // открывать NeoForge-сервер, и ни то, ни другое — Paper.
    const groups = [minecraftManifest, ...manifests].map((m) => m.permissions.map((p) => p.key));
    const all = groups.flat();
    expect(new Set(all).size).toBe(all.length);
  });

  it('каждый ключ начинается с id своего модуля', () => {
    for (const manifest of [minecraftManifest, ...manifests]) {
      for (const permission of manifest.permissions) {
        expect(permission.key.startsWith(`${manifest.id}.`)).toBe(true);
      }
    }
  });

  it('«minecraft.» не считается префиксом «minecraft-forge.»', () => {
    // Ловушка на проверку доступа через startsWith: id Paper — это префикс
    // строки id загрузчиков. Ключи при этом обязаны различаться целиком.
    expect(MINECRAFT_FORGE_PERMISSIONS.ban).toBe('minecraft-forge.ban');
    expect(MINECRAFT_FORGE_PERMISSIONS.ban).not.toBe('minecraft.ban');
    expect(MINECRAFT_NEOFORGE_PERMISSIONS.ban).toBe('minecraft-neoforge.ban');
  });

  it('роли по умолчанию совпадают с Paper по смыслу действия', () => {
    const roleOf = (keys: { key: string; defaultRoles: Role[] }[], suffix: string) =>
      keys.find((p) => p.key.endsWith(suffix))?.defaultRoles;

    for (const manifest of manifests) {
      // Смотреть и наказывать может модератор...
      expect(roleOf(manifest.permissions, '.players.view')).toEqual(['ADMIN', 'MODERATOR']);
      expect(roleOf(manifest.permissions, '.kick')).toEqual(['ADMIN', 'MODERATOR']);
      // ...снимать бан и лезть в консоль сервера — нет.
      expect(roleOf(manifest.permissions, '.ban.pardon')).toEqual(['ADMIN']);
      expect(roleOf(manifest.permissions, '.command.raw')).toEqual(['ADMIN']);
      // Настройка хранит RCON-пароль — только ГМ.
      expect(roleOf(manifest.permissions, '.configure')).toEqual([]);
    }
  });

  it('прав, требующих плагина Bukkit, у загрузчиков нет', () => {
    for (const manifest of manifests) {
      const keys = manifest.permissions.map((p) => p.key);
      for (const forbidden of ['inventory', 'economy', 'permissions', 'plugins']) {
        expect(keys.filter((k) => k.includes(forbidden))).toEqual([]);
      }
    }
  });
});

describe('справочники загрузчиков', () => {
  it('оба id опознаются и как Minecraft, и как загрузчик', () => {
    for (const id of ['minecraft-forge', 'minecraft-neoforge']) {
      expect(isMinecraftModule(id)).toBe(true);
      expect(isMinecraftLoaderModule(id)).toBe(true);
    }
  });

  it('Paper — Minecraft, но не загрузчик модов', () => {
    expect(isMinecraftModule('minecraft')).toBe(true);
    expect(isMinecraftLoaderModule('minecraft')).toBe(false);
  });

  it('чужие игры не опознаются', () => {
    for (const id of ['palworld', 'sevendays', 'minecraft-fabric']) {
      expect(isMinecraftModule(id)).toBe(false);
    }
  });

  it('подписи загрузчиков различаются', () => {
    expect(LOADER_LABELS['minecraft-forge']).toBe('Forge');
    expect(LOADER_LABELS['minecraft-neoforge']).toBe('NeoForge');
  });

  it('набор действий у обоих одинаковый — различаются только префиксы', () => {
    const forge = Object.keys(loaderPermissions('minecraft-forge'));
    const neoforge = Object.keys(loaderPermissions('minecraft-neoforge'));
    expect(forge).toEqual(neoforge);
  });
});

describe('быстрые команды на загрузчике', () => {
  const vanilla = new VanillaRconService(
    {} as PrismaService,
    {} as MinecraftConfigService,
    {} as RconService,
  );

  it('без плагинов остаются только ванильные действия', () => {
    // Ровно то, что уходит контроллеру загрузчика: плагинов Bukkit там нет,
    // и список всегда строится с null.
    const commands = vanilla.listQuickCommands(null);
    expect(commands.length).toBeGreaterThan(0);
    expect(commands.every((c) => c.plugin === null)).toBe(true);
  });

  it('действия EssentialsX не просачиваются', () => {
    const ids = vanilla.listQuickCommands(null).map((c) => c.id);
    expect(ids.filter((id) => id.startsWith('ess-'))).toEqual([]);
  });

  it('ключ права подменяется на ключ модуля-владельца', () => {
    // В каталоге записан ключ Paper. Не подменив его, фронтенд спрятал бы
    // кнопки от того, у кого право на этот сервер как раз есть.
    const commands = vanilla.listQuickCommands(null, MINECRAFT_FORGE_PERMISSIONS.quickCommands);
    expect(commands.every((c) => c.permission === 'minecraft-forge.quick-commands')).toBe(true);
  });

  it('без подмены остаётся ключ каталога — Paper', () => {
    const commands = vanilla.listQuickCommands(null);
    expect(commands.every((c) => c.permission === 'minecraft.quick-commands')).toBe(true);
  });

  it('ванильные действия — это команды самого сервера, а не Bukkit', () => {
    // Проверка по существу: всё, что предлагается на загрузчике, обязано
    // работать на голом сервере Minecraft.
    const ids = vanilla.listQuickCommands(null).map((c) => c.id);
    expect(ids).toContain('save-all');
    expect(ids).toContain('day');
    expect(ids).toContain('broadcast');
    expect(ids).toContain('vanilla-gamemode');
  });
});
