process.env.NODE_ENV = 'test';

import { BadRequestException } from '@nestjs/common';
import { MinecraftService } from './minecraft.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { MinecraftConfigService } from '../minecraft-shared/minecraft-config.service';
import type { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';
import type { CompanionService } from './companion.service';
import type { AuditService } from '../../audit/audit.service';

describe('MinecraftService: словарь и автодополнение консоли', () => {
  /** Сервис с подменёнными зависимостями: ни RCON, ни плагина в тестах нет. */
  function setup(options: {
    plugins?: { name: string; version: string; enabled: boolean }[] | null;
    players?: string[];
    playersFail?: boolean;
    companionConfigured?: boolean;
    complete?: string[] | null;
  }) {
    const companion = {
      getInstalledPlugins: () => Promise.resolve(options.plugins ?? null),
      getPlayers: () => Promise.resolve(null),
      isConfigured: () => Promise.resolve(options.companionConfigured ?? false),
      complete: () => Promise.resolve(options.complete ?? null),
    } as unknown as CompanionService;

    const service = new MinecraftService(
      {} as PrismaService,
      {} as MinecraftConfigService,
      {
        // Общий слой подменяем целиком: сюда Paper-сервис уходит за всем,
        // что умеет любой сервер Minecraft.
        runCommand: () =>
          options.playersFail
            ? Promise.reject(new Error('RCON недоступен'))
            : Promise.resolve(
                `There are ${(options.players ?? []).length} of a max of 20 players online: ` +
                  (options.players ?? []).join(', '),
              ),
      } as unknown as VanillaRconService,
      companion,
      { log: () => Promise.resolve() } as unknown as AuditService,
    );
    return service;
  }

  it('в словаре есть команды сервера и ники игроков онлайн', async () => {
    const dictionary = await setup({ players: ['Steve', 'Alex'] }).getConsoleDictionary('s1');

    expect(dictionary.commands.some((c) => c.name === 'gamemode')).toBe(true);
    expect(dictionary.players).toEqual(['Steve', 'Alex']);
  });

  it('команды плагина появляются, только если плагин установлен', async () => {
    const withEssentials = await setup({
      plugins: [{ name: 'Essentials', version: '2.0', enabled: true }],
    }).getConsoleDictionary('s1');
    expect(withEssentials.commands.some((c) => c.name === 'heal')).toBe(true);

    const withLuckPerms = await setup({
      plugins: [{ name: 'LuckPerms', version: '5.4', enabled: true }],
    }).getConsoleDictionary('s1');
    expect(withLuckPerms.commands.some((c) => c.name === 'heal')).toBe(false);
  });

  // В консоли цена лишней подсказки — одна строка «Unknown command», а цена
  // недостающей — человек не нашёл нужную команду. Поэтому когда проверить
  // нечем, предлагаем всё. Это осознанно противоположно кнопкам быстрых
  // действий, где нерабочая кнопка вводит в заблуждение.
  it('без списка плагинов предлагаются все известные команды', async () => {
    const dictionary = await setup({ plugins: null }).getConsoleDictionary('s1');
    expect(dictionary.commands.some((c) => c.name === 'heal')).toBe(true);
  });

  it('недоступный RCON не ломает словарь — команды остаются', async () => {
    const dictionary = await setup({ playersFail: true }).getConsoleDictionary('s1');

    expect(dictionary.players).toEqual([]);
    expect(dictionary.commands.length).toBeGreaterThan(0);
  });

  it('имена команд в словаре не повторяются', async () => {
    // gamemode есть и в ванили, и в каталоге EssentialsX — дважды его быть
    // не должно, иначе Tab предложит один и тот же вариант два раза.
    const dictionary = await setup({
      plugins: [{ name: 'Essentials', version: '2.0', enabled: true }],
    }).getConsoleDictionary('s1');

    const names = dictionary.commands.map((c) => c.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it('сообщает, доступен ли продвинутый уровень', async () => {
    expect((await setup({}).getConsoleDictionary('s1')).companionAvailable).toBe(false);
    expect(
      (await setup({ companionConfigured: true }).getConsoleDictionary('s1')).companionAvailable,
    ).toBe(true);
  });

  it('без companion-плагина продвинутое автодополнение честно недоступно', async () => {
    const result = await setup({ complete: null }).completeConsoleCommand('s1', 'gam');

    expect(result).toEqual({ available: false, suggestions: [], source: 'static' });
  });

  it('с companion-плагином отдаются его варианты', async () => {
    const result = await setup({ complete: ['gamemode'] }).completeConsoleCommand('s1', 'gam');

    expect(result).toEqual({ available: true, suggestions: ['gamemode'], source: 'companion' });
  });

  it('пустой ответ плагина — это ответ, а не отказ', async () => {
    // available:true с пустым списком означает «сервер знает и предлагать
    // нечего». Панель на этом останавливается и не догадывает по словарю.
    const result = await setup({ complete: [] }).completeConsoleCommand('s1', 'zzz');

    expect(result).toEqual({ available: true, suggestions: [], source: 'companion' });
  });
});

describe('MinecraftService — валюта', () => {
  interface Recorded {
    action: string;
    metadata: Record<string, unknown>;
  }

  function setup(options: {
    change?: Awaited<ReturnType<CompanionService['changeBalance']>>;
    economy?: Awaited<ReturnType<CompanionService['getEconomy']>>;
    onEconomyCall?: () => void;
  }) {
    const logged: Recorded[] = [];
    const companion = {
      changeBalance: () =>
        Promise.resolve(
          options.change ?? {
            ok: true,
            change: { ok: true, balanceBefore: 100, balanceAfter: 150, formatted: '150' },
          },
        ),
      getEconomy: () => {
        options.onEconomyCall?.();
        return Promise.resolve(
          options.economy ?? {
            available: true,
            total: 1000,
            totalFormatted: '1000',
            currency: 'монет',
            playersCounted: 3,
            top: [],
          },
        );
      },
      getPlayers: () =>
        Promise.resolve([
          {
            name: 'Steve',
            uuid: '8667ba71-b85a-4004-af54-457a9734eed7',
            ping: null,
            health: null,
            maxHealth: null,
            world: null,
            position: null,
          },
        ]),
    } as unknown as CompanionService;

    const service = new MinecraftService(
      {} as PrismaService,
      {} as MinecraftConfigService,
      {} as VanillaRconService,
      companion,
      {
        log: (entry: { action: string; metadata?: unknown }) => {
          logged.push({ action: entry.action, metadata: entry.metadata as Record<string, unknown> });
          return Promise.resolve();
        },
      } as unknown as AuditService,
    );
    return { service, logged };
  }

  const STEVE = '8667ba71-b85a-4004-af54-457a9734eed7';

  it('в журнал попадают сумма, причина и баланс до и после', async () => {
    const { service, logged } = setup({});

    await service.changeBalance('s1', STEVE, 'deposit', 50, 'компенсация за откат', 'user-1');

    expect(logged).toHaveLength(1);
    const entry = logged[0]!;
    expect(entry.action).toBe('minecraft.economy.deposit');
    expect(entry.metadata).toMatchObject({
      playerUuid: STEVE,
      playerName: 'Steve',
      amount: 50,
      reason: 'компенсация за откат',
      ok: true,
      balanceBefore: 100,
      balanceAfter: 150,
    });
  });

  it('отказ провайдера тоже записывается — это состоявшаяся попытка', async () => {
    const { service, logged } = setup({
      change: {
        ok: true,
        change: {
          ok: false,
          error: 'Недостаточно средств',
          balanceBefore: 10,
          balanceAfter: 10,
        },
      },
    });

    const result = await service.changeBalance('s1', STEVE, 'withdraw', 100, null, 'user-1');

    expect(result.ok).toBe(false);
    const entry = logged[0]!;
    expect(entry.action).toBe('minecraft.economy.withdraw');
    expect(entry.metadata).toMatchObject({ ok: false, error: 'Недостаточно средств' });
  });

  it('когда валюты на сервере нет, операции не происходит и в журнал ничего не идёт', async () => {
    const { service, logged } = setup({
      change: {
        ok: false,
        failure: { available: false, code: 'requires-vault', reason: 'Нужен Vault' },
      },
    });

    await expect(service.changeBalance('s1', STEVE, 'deposit', 50, null, 'user-1')).rejects.toThrow(
      BadRequestException,
    );
    expect(logged).toHaveLength(0);
  });

  it('неположительная сумма отвергается до обращения к серверу', async () => {
    const { service, logged } = setup({});

    await expect(service.changeBalance('s1', STEVE, 'deposit', 0, null, 'u')).rejects.toThrow(
      BadRequestException,
    );
    await expect(service.changeBalance('s1', STEVE, 'deposit', -5, null, 'u')).rejects.toThrow(
      BadRequestException,
    );
    expect(logged).toHaveLength(0);
  });

  it('сводка экономики берётся из кэша, а refresh пересчитывает', async () => {
    let calls = 0;
    const { service } = setup({ onEconomyCall: () => (calls += 1) });
    const serverId = `cache-${Math.random()}`;

    const first = await service.getEconomy(serverId);
    expect(first.cached).toBe(false);
    expect(first.calculatedAt).toBeTruthy();
    expect(calls).toBe(1);

    // Второе открытие страницы не должно снова гонять сервер по всем игрокам.
    const second = await service.getEconomy(serverId);
    expect(second.cached).toBe(true);
    expect(calls).toBe(1);

    // Кнопка «обновить» — единственный способ получить свежую цифру раньше срока.
    const third = await service.getEconomy(serverId, { refresh: true });
    expect(third.cached).toBe(false);
    expect(calls).toBe(2);
  });

  it('недоступность не кэшируется: поставили Vault — цифра появляется сразу', async () => {
    let calls = 0;
    const { service } = setup({
      economy: { available: false, code: 'requires-vault', reason: 'Нужен Vault' },
      onEconomyCall: () => (calls += 1),
    });
    const serverId = `nocache-${Math.random()}`;

    await service.getEconomy(serverId);
    await service.getEconomy(serverId);

    expect(calls).toBe(2);
  });
});
