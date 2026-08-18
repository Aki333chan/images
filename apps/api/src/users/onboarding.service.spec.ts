process.env.NODE_ENV = 'test';

import * as argon2 from 'argon2';
import { OnboardingService, isValidStaffNickname, normalizeNickname } from './onboarding.service';
import type { PrismaService } from '../prisma/prisma.service';

/**
 * Ник СОТРУДНИКА панели — не ник игрока Minecraft. Правила разные, и
 * проверять их надо отдельно, иначе легко скопировать чужие ограничения.
 */
describe('ник сотрудника', () => {
  it.each(['Ann', 'Большой Босс', 'admin_2', 'ГМ-1', 'x9'])('принимает «%s»', (value) => {
    expect(isValidStaffNickname(value)).toBe(true);
  });

  it.each([
    ['a', 'короче двух символов'],
    ['', 'пустой'],
    [' Ann', 'начинается с пробела'],
    ['_ann', 'начинается с подчёркивания'],
    ['ann@mail', 'спецсимвол'],
    ['a'.repeat(32), 'длиннее 31 символа'],
  ])('отклоняет «%s» (%s)', (value) => {
    expect(isValidStaffNickname(value)).toBe(false);
  });

  it('кириллица допустима — это ник для людей, а не для игрового сервера', () => {
    expect(isValidStaffNickname('Модератор Вася')).toBe(true);
  });

  it('ровно 31 символ проходит, 32 — нет', () => {
    expect(isValidStaffNickname('a'.repeat(31))).toBe(true);
    expect(isValidStaffNickname('a'.repeat(32))).toBe(false);
  });
});

describe('normalizeNickname', () => {
  it('уравнивает регистр и лишние пробелы', () => {
    expect(normalizeNickname('Big  Boss ')).toBe(normalizeNickname('big boss'));
  });

  it('различает действительно разные ники', () => {
    expect(normalizeNickname('Ann')).not.toBe(normalizeNickname('Anna'));
  });

  it('работает с кириллицей', () => {
    expect(normalizeNickname('ГМ')).toBe(normalizeNickname('гм'));
  });
});

/**
 * Первый вход, смена пароля и смена ника.
 *
 * Ник спрашивается ровно один раз в жизни аккаунта — на этом держится сценарий
 * сброса пароля: человек проходит тот же экран, но ник у него уже есть.
 */
describe('OnboardingService', () => {
  const TEMP = 'temporary-password';

  // argon2 намеренно медленный, а тестов здесь много: при параллельном прогоне
  // всего набора пятисекундного умолчания не хватает. Это не признак того, что
  // что-то тормозит в коде, — так и задумано хэшированием паролей.
  jest.setTimeout(60_000);

  /** Хэш временного пароля считаем один раз на весь набор, а не в каждом тесте. */
  let tempHash: string;
  beforeAll(async () => {
    tempHash = await argon2.hash(TEMP);
  });

  function setup(user: Partial<{ nickname: string | null; nicknameChangeAllowed: boolean }> = {}) {
    const updates: Record<string, unknown>[] = [];
    const revoked: unknown[] = [];
    const stored = {
      id: 'u1',
      passwordHash: '',
      nickname: user.nickname === undefined ? null : user.nickname,
      nicknameChangeAllowed: user.nicknameChangeAllowed ?? false,
    };

    const prisma = {
      user: {
        findUniqueOrThrow: () => Promise.resolve(stored),
        // Занят ровно один ник — «Занятый».
        findMany: () => Promise.resolve([{ id: 'other', nickname: 'Занятый' }]),
        update: ({ data }: { data: Record<string, unknown> }) => {
          updates.push(data);
          return Promise.resolve({ ...stored, ...data });
        },
      },
      session: {
        updateMany: (args: unknown) => {
          revoked.push(args);
          return Promise.resolve({ count: 1 });
        },
      },
    };
    const service = new OnboardingService(prisma as unknown as PrismaService);
    return { service, stored, updates, revoked };
  }

  async function withTempPassword<T>(
    ctx: { stored: { passwordHash: string } },
    run: () => Promise<T>,
  ): Promise<T> {
    ctx.stored.passwordHash = tempHash;
    return run();
  }

  describe('первый вход', () => {
    it('без ника аккаунт не активируется — под чем-то же надо ходить', async () => {
      const ctx = setup({ nickname: null });

      await expect(
        withTempPassword(ctx, () =>
          ctx.service.complete('u1', 's1', { currentPassword: TEMP, newPassword: 'new-password-1' }),
        ),
      ).rejects.toThrow(/Придумайте ник/);
    });

    it('с ником сохраняет пароль и ник разом', async () => {
      const ctx = setup({ nickname: null });

      const result = await withTempPassword(ctx, () =>
        ctx.service.complete('u1', 's1', {
          currentPassword: TEMP,
          newPassword: 'new-password-1',
          nickname: '  Новый  Ник  ',
        }),
      );

      expect(result.nickname).toBe('Новый Ник');
      expect(ctx.updates[0]).toMatchObject({ nickname: 'Новый Ник', mustChangePassword: false });
    });

    it('занятый ник отвергается', async () => {
      const ctx = setup({ nickname: null });

      await expect(
        withTempPassword(ctx, () =>
          ctx.service.complete('u1', 's1', {
            currentPassword: TEMP,
            newPassword: 'new-password-1',
            // Тот же ник в другом регистре — это тот же ник.
            nickname: 'занятый',
          }),
        ),
      ).rejects.toThrow(/уже занят/);
    });
  });

  describe('после сброса пароля ГМ', () => {
    it('ник заново не спрашивается — он уже есть', async () => {
      const ctx = setup({ nickname: 'Вася' });

      const result = await withTempPassword(ctx, () =>
        ctx.service.complete('u1', 's1', { currentPassword: TEMP, newPassword: 'new-password-1' }),
      );

      expect(result.nickname).toBe('Вася');
      expect(ctx.updates[0]).toMatchObject({ nickname: 'Вася' });
    });

    it('присланный ник игнорируется: сменить его так нельзя', async () => {
      const ctx = setup({ nickname: 'Вася' });

      await withTempPassword(ctx, () =>
        ctx.service.complete('u1', 's1', {
          currentPassword: TEMP,
          newPassword: 'new-password-1',
          nickname: 'Петя',
        }),
      );

      expect(ctx.updates[0]).toMatchObject({ nickname: 'Вася' });
    });
  });

  describe('смена своего пароля', () => {
    it('проверяет текущий пароль', async () => {
      const ctx = setup();

      await expect(
        withTempPassword(ctx, () =>
          ctx.service.changePassword('u1', 's1', {
            currentPassword: 'не тот',
            newPassword: 'new-password-1',
          }),
        ),
      ).rejects.toThrow(/Текущий пароль указан неверно/);
      expect(ctx.updates).toEqual([]);
    });

    it('не даёт задать прежний пароль и слишком короткий', async () => {
      const ctx = setup();

      await expect(
        withTempPassword(ctx, () =>
          ctx.service.changePassword('u1', 's1', { currentPassword: TEMP, newPassword: TEMP }),
        ),
      ).rejects.toThrow(/должен отличаться/);
      await expect(
        withTempPassword(ctx, () =>
          ctx.service.changePassword('u1', 's1', { currentPassword: TEMP, newPassword: 'корот' }),
        ),
      ).rejects.toThrow(/10 символов/);
    });

    it('обрывает остальные сессии, но не текущую', async () => {
      const ctx = setup();

      await withTempPassword(ctx, () =>
        ctx.service.changePassword('u1', 's1', {
          currentPassword: TEMP,
          newPassword: 'new-password-1',
        }),
      );

      expect(ctx.revoked[0]).toMatchObject({ where: { id: { not: 's1' } } });
    });
  });

  describe('смена своего ника', () => {
    it('без разрешения ГМ не даётся', async () => {
      const ctx = setup({ nickname: 'Вася', nicknameChangeAllowed: false });

      await expect(
        ctx.service.changeNickname('u1', { nickname: 'Петя', standingPermission: false }),
      ).rejects.toThrow(/разрешает ГМ/);
      expect(ctx.updates).toEqual([]);
    });

    it('с разрешением меняет и гасит разрешение — оно на один раз', async () => {
      const ctx = setup({ nickname: 'Вася', nicknameChangeAllowed: true });

      const result = await ctx.service.changeNickname('u1', {
        nickname: 'Петя',
        standingPermission: false,
      });

      expect(result.nickname).toBe('Петя');
      expect(ctx.updates[0]).toEqual({ nickname: 'Петя', nicknameChangeAllowed: false });
    });

    it('у того, кто сам раздаёт разрешения, оно постоянное', async () => {
      const ctx = setup({ nickname: 'ГМ', nicknameChangeAllowed: false });

      await expect(
        ctx.service.changeNickname('u1', { nickname: 'Главный', standingPermission: true }),
      ).resolves.toEqual({ nickname: 'Главный' });
    });

    it('занятый ник не отдаётся даже с разрешением', async () => {
      const ctx = setup({ nickname: 'Вася', nicknameChangeAllowed: true });

      await expect(
        ctx.service.changeNickname('u1', { nickname: 'Занятый', standingPermission: true }),
      ).rejects.toThrow(/уже занят/);
    });
  });
});
