import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  Injectable,
} from '@nestjs/common';
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

  /** Проверка и нормализация ника с понятными отказами. */
  private async validateNickname(raw: string, userId: string): Promise<string> {
    const nickname = raw.trim().replace(/\s+/g, ' ');
    if (!isValidStaffNickname(nickname)) {
      throw new BadRequestException(
        'Ник: от 2 до 31 символа, буквы и цифры, можно пробел, дефис и подчёркивание',
      );
    }
    if (!(await this.isNicknameAvailable(nickname, userId))) {
      throw new ConflictException(`Ник «${nickname}» уже занят — выберите другой`);
    }
    return nickname;
  }

  /** Гонка за один ник: уникальный индекс в БД — последняя защита. */
  private static rethrowNicknameConflict(e: unknown, nickname: string): never {
    if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
      throw new ConflictException(`Ник «${nickname}» уже занят — выберите другой`);
    }
    throw e;
  }

  /**
   * Первый вход: постоянный пароль и, если ника ещё нет, — ник.
   *
   * Ник спрашивается ровно один раз в жизни аккаунта. После сброса пароля ГМ
   * человек проходит этот же экран, но ник у него уже есть, и требовать
   * придумать новый было бы бессмысленно: коллеги знают его по старому.
   */
  async complete(
    userId: string,
    currentSessionId: string,
    input: { currentPassword: string; newPassword: string; nickname?: string },
  ): Promise<{ nickname: string | null }> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });

    // Текущий пароль подтверждаем даже здесь: сессия могла остаться открытой
    // на чужом устройстве, а здесь задаётся постоянный пароль.
    if (!(await argon2.verify(user.passwordHash, input.currentPassword))) {
      throw new BadRequestException('Текущий пароль указан неверно');
    }
    if (input.newPassword.length < 10) {
      throw new BadRequestException('Новый пароль должен быть не короче 10 символов');
    }
    if (input.newPassword === input.currentPassword) {
      throw new BadRequestException('Новый пароль должен отличаться от временного');
    }

    let nickname = user.nickname;
    if (!nickname) {
      if (!input.nickname?.trim()) {
        throw new BadRequestException('Придумайте ник — под ним вас увидят коллеги');
      }
      nickname = await this.validateNickname(input.nickname, userId);
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
      OnboardingService.rethrowNicknameConflict(e, nickname);
    }

    // Прочие сессии обрываем: пароль сменился, старые входы недействительны.
    // Текущую оставляем — иначе человека выкинуло бы сразу после сохранения,
    // и он попал бы на форму входа, не поняв, прошло ли оно.
    await this.prisma.session.updateMany({
      where: { userId, revokedAt: null, id: { not: currentSessionId } },
      data: { revokedAt: new Date() },
    });

    return { nickname };
  }

  /**
   * Смена своего пароля из настроек.
   *
   * Отдельно от complete: там речь про одноразовый пароль и первый вход, а
   * здесь — про обычную смену, доступную всем и в любой момент.
   */
  async changePassword(
    userId: string,
    currentSessionId: string,
    input: { currentPassword: string; newPassword: string },
  ): Promise<void> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (!(await argon2.verify(user.passwordHash, input.currentPassword))) {
      throw new BadRequestException('Текущий пароль указан неверно');
    }
    if (input.newPassword.length < 10) {
      throw new BadRequestException('Новый пароль должен быть не короче 10 символов');
    }
    if (input.newPassword === input.currentPassword) {
      throw new BadRequestException('Новый пароль должен отличаться от прежнего');
    }

    await this.prisma.user.update({
      where: { id: userId },
      data: {
        passwordHash: await argon2.hash(input.newPassword),
        mustChangePassword: false,
        passwordExpiresAt: null,
      },
    });

    // Смена пароля — это в том числе способ выгнать того, кто увёл сессию.
    // Свою оставляем, чужие обрываем.
    await this.prisma.session.updateMany({
      where: { userId, revokedAt: null, id: { not: currentSessionId } },
      data: { revokedAt: new Date() },
    });
  }

  /**
   * Смена своего ника.
   *
   * Разрешение разовое и выдаётся ГМ: ник — это то, по чему человека знают
   * коллеги и чем он подписан в журнале аудита, и менять его по своему
   * усмотрению значит рвать эту связь. Исключение — те, кто и так управляет
   * учётными записями: у них разрешение постоянное, просить его не у кого.
   */
  async changeNickname(
    userId: string,
    input: { nickname: string; standingPermission: boolean },
  ): Promise<{ nickname: string }> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (!input.standingPermission && !user.nicknameChangeAllowed) {
      throw new ForbiddenException(
        'Смену ника разрешает ГМ. Попросите его открыть смену — разрешение действует на один раз.',
      );
    }

    const nickname = await this.validateNickname(input.nickname, userId);
    try {
      await this.prisma.user.update({
        where: { id: userId },
        data: {
          nickname,
          // Разрешение гасим: оно на один раз. У тех, кто меняет ник по
          // должности, флаг и так не стоял — гасить нечего.
          nicknameChangeAllowed: false,
        },
      });
    } catch (e) {
      OnboardingService.rethrowNicknameConflict(e, nickname);
    }
    return { nickname };
  }
}
