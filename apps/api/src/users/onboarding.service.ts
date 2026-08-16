import { BadRequestException, ConflictException, Injectable } from '@nestjs/common';
import * as argon2 from 'argon2';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';

/**
 * Ник СОТРУДНИКА панели.
 *
 * Это не ник игрока в Minecraft — сущности разные и правила у них разные.
 * Здесь допускаются буквы (в том числе кириллица), цифры, пробел, дефис и
 * подчёркивание: сотрудник представляется людям, а не серверу, и загонять
 * его в 16 символов латиницы незачем.
 */
const NICKNAME_RE = /^[\p{L}\p{N}][\p{L}\p{N} _-]{1,30}$/u;

export function isValidStaffNickname(value: string): boolean {
  return NICKNAME_RE.test(value);
}

/**
 * Нормализация для сравнения на занятость.
 *
 * Уникальность должна быть нечувствительна к регистру и к схлопыванию
 * пробелов: «Big Boss», «big boss» и «Big  Boss» — один и тот же человек с
 * точки зрения того, кто ищет его в списке.
 */
export function normalizeNickname(value: string): string {
  return value.trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru');
}

@Injectable()
export class OnboardingService {
  constructor(private readonly prisma: PrismaService) {}

  /** Свободен ли ник. Используется и формой, и при сохранении. */
  async isNicknameAvailable(nickname: string, exceptUserId?: string): Promise<boolean> {
    const normalized = normalizeNickname(nickname);
    const taken = await this.prisma.user.findMany({
      where: { nickname: { not: null } },
      select: { id: true, nickname: true },
    });
    return !taken.some(
      (u) => u.id !== exceptUserId && normalizeNickname(u.nickname!) === normalized,
    );
  }

  /**
   * Завершение онбординга: новый пароль и ник за одну операцию.
   *
   * Вместе, а не по отдельности: наполовину пройденный онбординг — это
   * пользователь без ника либо с одноразовым паролём, и оба состояния
   * пришлось бы отдельно обрабатывать во всей панели.
   */
  async complete(
    userId: string,
    currentSessionId: string,
    input: { currentPassword: string; newPassword: string; nickname: string },
  ): Promise<{ nickname: string }> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });

    // Текущий пароль подтверждаем даже здесь: сессия могла остаться открытой
    // на чужом устройстве, а онбординг задаёт постоянный пароль.
    if (!(await argon2.verify(user.passwordHash, input.currentPassword))) {
      throw new BadRequestException('Текущий пароль указан неверно');
    }
    if (input.newPassword.length < 10) {
      throw new BadRequestException('Новый пароль должен быть не короче 10 символов');
    }
    if (input.newPassword === input.currentPassword) {
      throw new BadRequestException('Новый пароль должен отличаться от временного');
    }

    const nickname = input.nickname.trim().replace(/\s+/g, ' ');
    if (!isValidStaffNickname(nickname)) {
      throw new BadRequestException(
        'Ник: от 2 до 31 символа, буквы и цифры, можно пробел, дефис и подчёркивание',
      );
    }
    if (!(await this.isNicknameAvailable(nickname, userId))) {
      throw new ConflictException(`Ник «${nickname}» уже занят — выберите другой`);
    }

    try {
      await this.prisma.user.update({
        where: { id: userId },
        data: {
          nickname,
          passwordHash: await argon2.hash(input.newPassword),
          mustChangePassword: false,
          passwordExpiresAt: null,
        },
      });
    } catch (e) {
      // Гонка: двое одновременно взяли один ник. Уникальный индекс в БД —
      // последняя защита, и проигравшему нужно сказать понятное.
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
        throw new ConflictException(`Ник «${nickname}» уже занят — выберите другой`);
      }
      throw e;
    }

    // Прочие сессии обрываем: пароль сменился, старые входы недействительны.
    // Текущую оставляем — иначе человека выкинуло бы сразу после онбординга,
    // и он попал бы на форму входа, не поняв, прошло ли сохранение.
    await this.prisma.session.updateMany({
      where: { userId, revokedAt: null, id: { not: currentSessionId } },
      data: { revokedAt: new Date() },
    });

    return { nickname };
  }
}
