import { AddonsService } from './addons.service';

/**
 * Условие показа поп-апа.
 *
 * Оно из шести частей, и цена ошибки в каждой разная: не показать — человек
 * не узнает про наши плагины, показать лишний раз после «не предлагать» —
 * панель выглядит навязчивой ровно так, как её и просили не делать.
 * Поэтому условие живёт в одном месте на бэке и проверяется здесь.
 */
function makeService(over: {
  moduleId?: string | null;
  dismissed?: boolean;
  featureEnabled?: boolean;
  files?: string[] | null;
}) {
  const prisma = {
    server: {
      findUnique: jest.fn().mockResolvedValue({
        moduleId: over.moduleId === undefined ? 'minecraft' : over.moduleId,
        addonsDismissed: over.dismissed ?? false,
      }),
    },
  };
  const settings = {
    addonsOfferEnabled: jest.fn().mockResolvedValue(over.featureEnabled ?? true),
  };
  const plugins = {
    pluginFileNames: jest.fn().mockResolvedValue(over.files === undefined ? [] : over.files),
    pteroIdentifier: jest.fn().mockResolvedValue('abc123'),
    fetchAndPlace: jest.fn(),
  };
  const audit = { log: jest.fn().mockResolvedValue(undefined) };

  return new AddonsService(
    prisma as never,
    settings as never,
    plugins as never,
    audit as never,
  );
}

describe('когда панель сама предлагает наши плагины', () => {
  it('предлагает: модуль знает аддоны, ничего не стоит, право есть', async () => {
    const state = await makeService({}).state('srv-1', true);
    expect(state.canOffer).toBe(true);
    expect(state.optional.map((a) => a.id)).toEqual([
      'aurum-auth',
      'aurum-guilds',
      'addons-npc',
      'aurum-arena',
      'aurum-slots',
    ]);
    expect(state.required?.id).toBe('aurum-companion');
  });

  it('не предлагает без права на установку', async () => {
    // Модератор откроет сервер и увидит его обычным: ставить он всё равно не
    // может, и окно с кнопкой, которой у него нет, было бы издевательством.
    const state = await makeService({}).state('srv-1', false);
    expect(state.canOffer).toBe(false);
  });

  it('не предлагает, когда фича выключена в настройках', async () => {
    const state = await makeService({ featureEnabled: false }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
  });

  it('не предлагает после «не предлагать» для этого сервера', async () => {
    const state = await makeService({ dismissed: true }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
    // Список при этом отдаётся: кнопка «Рекомендуемые плагины» остаётся
    // единственным способом вернуться к выбору.
    expect(state.optional.length).toBeGreaterThan(0);
  });

  it('не предлагает, когда всё опциональное уже стоит', async () => {
    const state = await makeService({
      files: [
        'AurumAuth-1.0.jar',
        'AurumGuilds-1.0.jar',
        'AddonsNPC-1.5.3.jar',
        'AurumArena-1.1.5.jar',
        'AurumSlots-1.1.2.jar',
      ],
    }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
    expect(state.optional.every((a) => a.installed)).toBe(true);
  });

  it('предлагает, если не хватает хотя бы одного', async () => {
    const state = await makeService({ files: ['AurumAuth-1.0.jar'] }).state('srv-1', true);
    expect(state.canOffer).toBe(true);
  });

  it('не предлагает, когда файлы сервера недоступны', async () => {
    // Без листинга plugins/ мы не знаем, что там стоит, и предложение
    // поставить уже стоящее кончилось бы второй копией плагина в папке.
    const state = await makeService({ files: null }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
    expect(state.filesAvailable).toBe(false);
  });

  it('не предлагает для модуля без своих плагинов', async () => {
    // Forge, Palworld, 7 Days to Die: плагины Bukkit там не загрузятся, и
    // предлагать их значило бы обещать то, что не заработает.
    const state = await makeService({ moduleId: 'minecraft-forge' }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
    expect(state.required).toBeNull();
    expect(state.optional).toEqual([]);
  });

  it('не предлагает, когда модуль вообще не назначен', async () => {
    const state = await makeService({ moduleId: null }).state('srv-1', true);
    expect(state.canOffer).toBe(false);
  });
});

describe('обязательный аддон', () => {
  it('ставится сам и без права на установку', async () => {
    const service = makeService({});
    const install = jest
      .spyOn(service as never, 'installOne' as never)
      .mockResolvedValue({ restartRequired: false } as never);

    const state = await service.bootstrap('srv-1', 'user-1', false);

    expect(install).toHaveBeenCalled();
    expect(state.requiredInstall).toBe('installed');
    expect(state.required?.installed).toBe(true);
  });

  it('не ставится повторно, если уже лежит в plugins/', async () => {
    const service = makeService({ files: ['AurumCompanion-0.3.1.jar'] });
    const install = jest.spyOn(service as never, 'installOne' as never);

    const state = await service.bootstrap('srv-1', 'user-1', true);

    expect(install).not.toHaveBeenCalled();
    expect(state.requiredInstall).toBeUndefined();
  });

  it('не ставится при выключенной фиче', async () => {
    const service = makeService({ featureEnabled: false });
    const install = jest.spyOn(service as never, 'installOne' as never);

    await service.bootstrap('srv-1', 'user-1', true);

    expect(install).not.toHaveBeenCalled();
  });

  it('флаг «не предлагать» его не касается', async () => {
    // Companion — не предложение, а условие работы панели с этим сервером.
    const service = makeService({ dismissed: true });
    const install = jest
      .spyOn(service as never, 'installOne' as never)
      .mockResolvedValue({ restartRequired: true } as never);

    const state = await service.bootstrap('srv-1', 'user-1', true);

    expect(install).toHaveBeenCalled();
    expect(state.requiredInstall).toBe('restart-required');
  });

  it('неудача не роняет страницу и повторяется не сразу', async () => {
    const service = makeService({});
    const install = jest
      .spyOn(service as never, 'installOne' as never)
      .mockRejectedValue(new Error('GitHub недоступен') as never);

    const first = await service.bootstrap('srv-1', 'user-1', true);
    expect(first.requiredInstall).toBe('failed');

    // Второй заход на страницу в ту же минуту в сеть уже не пойдёт: страницу
    // обновляют часто, и стучаться в GitHub на каждое обновление незачем.
    const second = await service.bootstrap('srv-1', 'user-1', true);
    expect(install).toHaveBeenCalledTimes(1);
    expect(second.requiredInstall).toBeUndefined();
  });
});

describe('установка выбранного', () => {
  it('ставит только то, что положено этому модулю', async () => {
    const service = makeService({});
    const install = jest
      .spyOn(service as never, 'installOne' as never)
      .mockResolvedValue({ restartRequired: false } as never);

    // «minecraft-mod» никакому модулю не принадлежит: id приходит из
    // браузера, и ставить по нему что попало из репозитория нельзя.
    const results = await service.install('srv-1', ['aurum-auth', 'minecraft-mod'], 'user-1');

    expect(results.map((r) => r.id)).toEqual(['aurum-auth']);
    expect(install).toHaveBeenCalledTimes(1);
  });

  it('неудача одного не отменяет остальных', async () => {
    const service = makeService({});
    jest
      .spyOn(service as never, 'installOne' as never)
      .mockRejectedValueOnce(new Error('нет релиза') as never)
      .mockResolvedValueOnce({ restartRequired: false } as never);

    const results = await service.install('srv-1', ['aurum-auth', 'aurum-guilds'], 'user-1');

    expect(results.map((r) => r.ok)).toEqual([false, true]);
  });

  it('пустой список — не ошибка, просто ничего не ставится', async () => {
    const service = makeService({});
    const install = jest.spyOn(service as never, 'installOne' as never);

    await expect(service.install('srv-1', [], 'user-1')).resolves.toEqual([]);
    expect(install).not.toHaveBeenCalled();
  });

  it('при выключенной фиче установка отклоняется', async () => {
    const service = makeService({ featureEnabled: false });
    await expect(service.install('srv-1', ['aurum-auth'], 'user-1')).rejects.toThrow();
  });
});
