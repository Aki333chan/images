process.env.NODE_ENV = 'test';

import { BadRequestException, ServiceUnavailableException } from '@nestjs/common';
import { JailsService } from './jails.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';
import type { CompanionJails, CompanionService } from './companion.service';

/**
 * Тюрьмы: последовательность команд и защита кнопки выпуска.
 *
 * ПОЧЕМУ ЭТО ВООБЩЕ ТЕСТИРУЕТСЯ. Правильная команда здесь не выводится из
 * запроса — она зависит от того, что происходит на сервере прямо сейчас, и
 * поведение togglejail в каждом из состояний разное (проверено по исходникам
 * EssentialsX 2.x). Ошибка стоит дорого в обе стороны: лишний togglejail с
 * одним аргументом сажает того, кого просили выпустить, а пропущенный выпуск
 * оставляет перевод в другую тюрьму невыполненным с сообщением, которого
 * никто не увидит.
 */
describe('JailsService: команды по текущему состоянию', () => {
  const HOUR = 3600_000;

  function setup(options: {
    jails?: CompanionJails | null;
    online?: string[];
    records?: { id: string; playerName: string; jail: string; jailedBy: string; jailedAt: Date }[];
  }) {
    const commands: string[] = [];
    const deleted: unknown[] = [];
    const upserted: unknown[] = [];
    const records = options.records ?? [];

    const prisma = {
      minecraftJailRecord: {
        findMany: () => Promise.resolve(records),
        deleteMany: (args: unknown) => {
          deleted.push(args);
          return Promise.resolve({ count: 1 });
        },
        upsert: (args: unknown) => {
          upserted.push(args);
          return Promise.resolve({});
        },
      },
      user: { findUnique: () => Promise.resolve({ nickname: 'GM' }) },
    } as unknown as PrismaService;

    const companion = {
      getJails: () => Promise.resolve(options.jails === undefined ? JAILS : options.jails),
      getPlayers: () =>
        Promise.resolve((options.online ?? []).map((name) => ({ name }) as never)),
    } as unknown as CompanionService;

    const rcon = {
      assertNickname: (name: string) => name,
      runCommand: (_serverId: string, command: string) => {
        commands.push(command);
        return Promise.resolve('ok');
      },
    } as unknown as VanillaRconService;

    return { service: new JailsService(prisma, companion, rcon), commands, deleted, upserted };
  }

  /** Стив сидит в main, Алекс — в mine; обоих посадили не через панель. */
  const JAILS: CompanionJails = {
    available: true,
    jails: ['main', 'mine', 'arena'],
    jailed: [
      { uuid: 'u-1', name: 'Steve', jail: 'main', releaseAt: Date.now() + 2 * HOUR },
      { uuid: 'u-2', name: 'Alex', jail: 'mine', releaseAt: 0 },
    ],
  };

  it('не сидящего сажает одной командой', async () => {
    const { service, commands } = setup({});
    await service.jail('s1', { player: 'Notch', jail: 'main', duration: '2h' }, 'u');
    expect(commands).toEqual(['togglejail Notch main 2h']);
  });

  it('без срока сажает до отмены — команда без третьего аргумента', async () => {
    const { service, commands } = setup({});
    await service.jail('s1', { player: 'Notch', jail: 'arena' }, 'u');
    expect(commands).toEqual(['togglejail Notch arena']);
  });

  it('в той же тюрьме новый срок заменяет старый одной командой', async () => {
    const { service, commands } = setup({});
    await service.jail('s1', { player: 'Steve', jail: 'main', duration: '7d' }, 'u');
    expect(commands).toEqual(['togglejail Steve main 7d']);
  });

  it('в ту же тюрьму без срока — выпуск и посадка заново', async () => {
    // Иначе EssentialsX разбирает пустую строку как срок и падает: выставить
    // «до отмены» продлением нельзя, ноль означал бы «выпустить немедленно».
    const { service, commands } = setup({});
    await service.jail('s1', { player: 'Steve', jail: 'main' }, 'u');
    expect(commands).toEqual(['togglejail Steve', 'togglejail Steve main']);
  });

  it('перевод в другую тюрьму — выпуск и посадка заново', async () => {
    // Одной командой EssentialsX переводить отказывается: отвечает
    // jailAlreadyIncarcerated и не делает ничего.
    const { service, commands } = setup({});
    await service.jail('s1', { player: 'Steve', jail: 'arena', duration: '30m' }, 'u');
    expect(commands).toEqual(['togglejail Steve', 'togglejail Steve arena 30m']);
  });

  it('имя тюрьмы берётся из списка сервера, а не из запроса', async () => {
    const { service, commands, upserted } = setup({});
    await service.jail('s1', { player: 'Notch', jail: 'ARENA' }, 'u');
    expect(commands).toEqual(['togglejail Notch arena']);
    expect(upserted).toHaveLength(1);
  });

  it('несуществующая тюрьма отклоняется до похода на сервер', async () => {
    const { service, commands } = setup({});
    await expect(service.jail('s1', { player: 'Notch', jail: 'nope' }, 'u')).rejects.toBeInstanceOf(
      BadRequestException,
    );
    expect(commands).toEqual([]);
  });

  it('негодный срок отклоняется до похода на сервер', async () => {
    const { service, commands } = setup({});
    await expect(
      service.jail('s1', { player: 'Notch', jail: 'main', duration: '2 часа; op Notch' }, 'u'),
    ).rejects.toBeInstanceOf(BadRequestException);
    expect(commands).toEqual([]);
  });

  it('без ответа EssentialsX не сажает вслепую', async () => {
    const { service, commands } = setup({ jails: null });
    await expect(
      service.jail('s1', { player: 'Notch', jail: 'main' }, 'u'),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
    expect(commands).toEqual([]);
  });

  it('выпуск сидящего — togglejail ровно с одним аргументом', async () => {
    const { service, commands, deleted } = setup({});
    await service.release('s1', 'Steve');
    expect(commands).toEqual(['togglejail Steve']);
    expect(deleted).toHaveLength(1);
  });

  it('выпуск не сидящего отклоняется, команда не уходит', async () => {
    // Главная защита кнопки: togglejail с одним аргументом по не сидящему
    // игроку на сервере с единственной тюрьмой САЖАЕТ его туда.
    const { service, commands } = setup({
      jails: { available: true, jails: ['main'], jailed: [] },
    });
    await expect(service.release('s1', 'Notch')).rejects.toBeInstanceOf(BadRequestException);
    expect(commands).toEqual([]);
  });
});

describe('JailsService: список сидящих', () => {
  const HOUR = 3600_000;
  const jailedAt = new Date(Date.now() - 5 * HOUR);

  function setup(options: {
    jails: CompanionJails | null;
    online: string[];
    records: { id: string; playerName: string; jail: string; jailedBy: string; jailedAt: Date }[];
  }) {
    const deleted: { where: { id: { in: string[] } } }[] = [];
    const prisma = {
      minecraftJailRecord: {
        findMany: () => Promise.resolve(options.records),
        deleteMany: (args: { where: { id: { in: string[] } } }) => {
          deleted.push(args);
          return Promise.resolve({ count: args.where.id.in.length });
        },
      },
    } as unknown as PrismaService;
    const companion = {
      getJails: () => Promise.resolve(options.jails),
      getPlayers: () => Promise.resolve(options.online.map((name) => ({ name }) as never)),
    } as unknown as CompanionService;
    return {
      service: new JailsService(prisma, companion, {} as VanillaRconService),
      deleted,
    };
  }

  it('к ответу плагина подставляет момент посадки из своей записи', async () => {
    const { service } = setup({
      jails: {
        available: true,
        jails: ['main'],
        jailed: [{ uuid: 'u-1', name: 'Steve', jail: 'main', releaseAt: 0 }],
      },
      online: ['Steve'],
      records: [{ id: 'r1', playerName: 'steve', jail: 'main', jailedBy: 'GM', jailedAt }],
    });

    const state = await service.list('s1');
    expect(state.jailed).toEqual([
      {
        uuid: 'u-1',
        name: 'Steve',
        jail: 'main',
        releaseAt: 0,
        jailedAt: jailedAt.getTime(),
        jailedBy: 'GM',
        online: true,
      },
    ]);
  });

  it('запись о другой тюрьме не приписывается: в игре перевели', async () => {
    const { service } = setup({
      jails: {
        available: true,
        jails: ['main', 'mine'],
        jailed: [{ uuid: 'u-1', name: 'Steve', jail: 'mine', releaseAt: 0 }],
      },
      online: ['Steve'],
      records: [{ id: 'r1', playerName: 'steve', jail: 'main', jailedBy: 'GM', jailedAt }],
    });

    const [entry] = (await service.list('s1')).jailed;
    expect(entry?.jail).toBe('mine');
    expect(entry?.jailedAt).toBeNull();
    expect(entry?.jailedBy).toBeNull();
  });

  it('офлайн-сиделец берётся из записи панели, срок у него неизвестен', async () => {
    const { service, deleted } = setup({
      jails: { available: true, jails: ['main'], jailed: [] },
      online: ['Alex'],
      records: [{ id: 'r1', playerName: 'steve', jail: 'main', jailedBy: 'GM', jailedAt }],
    });

    const [entry] = (await service.list('s1')).jailed;
    expect(entry).toMatchObject({ name: 'steve', online: false, releaseAt: null, jailedBy: 'GM' });
    // Записи офлайновых не трогаем: плагин их просто не видит, и удалять их
    // значило бы забыть о человеке, который продолжает сидеть.
    expect(deleted).toEqual([]);
  });

  it('запись о вышедшем в сеть и уже не сидящем снимается', async () => {
    const { service, deleted } = setup({
      jails: { available: true, jails: ['main'], jailed: [] },
      online: ['Steve'],
      records: [{ id: 'r1', playerName: 'steve', jail: 'main', jailedBy: 'GM', jailedAt }],
    });

    expect((await service.list('s1')).jailed).toEqual([]);
    expect(deleted).toEqual([{ where: { id: { in: ['r1'] } } }]);
  });

  it('без companion честно отвечает «недоступно», а не «никто не сидит»', async () => {
    const { service } = setup({ jails: null, online: [], records: [] });
    expect(await service.list('s1')).toEqual({ available: false, jails: [], jailed: [] });
  });
});
