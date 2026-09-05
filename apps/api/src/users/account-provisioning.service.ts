import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import * as argon2 from 'argon2';
import { randomBytes } from 'crypto';
import type { CreateUserResultDto, PendingUserDto, Role, UserAdminDto } from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';
import { MailService } from '../mail/mail.service';
import { env } from '../config/env';

/** Сколько живёт одноразовый пароль. */
export const ONE_TIME_PASSWORD_HOURS = 72;

/**
 * Алфавит одноразового пароля.
 *
 * Без 0/O и 1/l/I: пароль читают с экрана и набирают руками, а перепутанные
 * символы дают «неверный пароль» там, где всё введено верно.
 */
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

/**
 * Одноразовый пароль.
 *
 * 16 символов из алфавита в 56 знаков — около 93 бит энтропии. Это заведомо
 * больше, чем нужно паролю со сроком жизни в 72 часа, и не требует
 * дополнительных ограничений на попытки входа сверх уже имеющихся.
 *
 * randomBytes, а не Math.random: последний не криптостойкий.
 */
export function generateOneTimePassword(length = 16): string {
  const bytes = randomBytes(length);
  let out = '';
  for (let i = 0; i < length; i++) {
    // Смещение по модулю здесь допустимо: 256 % 56 даёт перекос меньше
    // четверти бита, что на фоне 93 бит несущественно.
    out += ALPHABET[bytes[i]! % ALPHABET.length];
  }
  return out;
}

/**
 * Создание учётных записей, подтверждение заявок и выдача одноразовых паролей.
 *
 * Вынесено из UsersService намеренно: тот занимается ролями и доступами, а
 * здесь — жизненный цикл аккаунта, который завязан на почту и настройки.
 */
@Injectable()
export class AccountProvisioningService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly settings: SettingsService,
    private readonly mail: MailService,
  ) {}

  /**
   * Создание аккаунта.
   *
   * @param actor кто создаёт — попадёт в createdById заявки
   * @param canManageUsers true — полное право users.manage (ГМ). Именно оно,
   *   а не роль, решает, нужна ли апробация: право можно переназначить,
   *   и логика не должна разъезжаться с настройками доступа.
   */
  async create(
    actor: { id: string },
    canManageUsers: boolean,
    input: { email: string; role: Role },
  ): Promise<CreateUserResultDto> {
    const email = input.email.toLowerCase().trim();
    if (await this.prisma.user.findUnique({ where: { email } })) {
      throw new ConflictException('users.err.emailTaken');
    }

    // Кто не имеет users.manage, заводит только модераторов. Проверка здесь,
    // а не только в контроллере: сервис — последняя линия перед записью в БД.
    if (!canManageUsers && input.role !== 'MODERATOR') {
      throw new BadRequestException('users.err.onlyModerators');
    }

    const appSettings = await this.settings.getAppSettings();
    const needsApproval =
      !canManageUsers && appSettings.requireGmApprovalForAdminCreatedAccounts;

    if (needsApproval) {
      // Заявка: аккаунт неактивен, пароля нет, письмо не уходит.
      // В passwordHash кладём случайную строку — поле обязательное, а
      // осмысленного пароля на этом этапе не существует.
      const user = await this.prisma.user.create({
        data: {
          email,
          role: input.role,
          passwordHash: await argon2.hash(generateOneTimePassword(32)),
          isActive: false,
          status: 'pending_approval',
          createdById: actor.id,
        },
        include: { serverAccess: { select: { serverId: true } } },
      });
      return { user: toDto(user), activated: false, emailSent: false };
    }

    const user = await this.prisma.user.create({
      data: {
        email,
        role: input.role,
        passwordHash: 'заглушка, перезаписывается ниже',
        isActive: true,
        status: 'active',
        createdById: actor.id,
      },
      include: { serverAccess: { select: { serverId: true } } },
    });

    const delivery = await this.issueOneTimePassword(user.id, user.email);
    const fresh = await this.prisma.user.findUniqueOrThrow({
      where: { id: user.id },
      include: { serverAccess: { select: { serverId: true } } },
    });
    return {
      user: toDto(fresh),
      activated: true,
      emailSent: delivery.sent,
      ...(delivery.error ? { emailError: delivery.error } : {}),
    };
  }

  /** Заявки, ждущие решения ГМ. */
  async listPending(): Promise<PendingUserDto[]> {
    const rows = await this.prisma.user.findMany({
      where: { status: 'pending_approval' },
      orderBy: { createdAt: 'asc' },
    });
    const authorIds = [...new Set(rows.map((r) => r.createdById).filter((v): v is string => !!v))];
    const authors = await this.prisma.user.findMany({
      where: { id: { in: authorIds } },
      select: { id: true, nickname: true },
    });
    const byId = new Map(authors.map((a) => [a.id, a]));

    return rows.map((row) => ({
      id: row.id,
      email: row.email,
      role: row.role,
      createdAt: row.createdAt.toISOString(),
      createdBy: row.createdById ? (byId.get(row.createdById) ?? null) : null,
    }));
  }

  /** Подтверждение заявки: аккаунт активируется, уходит письмо с паролем. */
  async approve(userId: string): Promise<CreateUserResultDto> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new NotFoundException('users.err.requestNotFound');
    if (user.status !== 'pending_approval') {
      throw new BadRequestException('users.err.requestDecided');
    }

    await this.prisma.user.update({
      where: { id: userId },
      data: { status: 'active', isActive: true },
    });
    const delivery = await this.issueOneTimePassword(user.id, user.email);

    const fresh = await this.prisma.user.findUniqueOrThrow({
      where: { id: userId },
      include: { serverAccess: { select: { serverId: true } } },
    });
    return {
      user: toDto(fresh),
      activated: true,
      emailSent: delivery.sent,
      ...(delivery.error ? { emailError: delivery.error } : {}),
    };
  }

  /**
   * Отклонение заявки.
   *
   * Запись не удаляем, а помечаем: email остаётся занятым, и повторная
   * заявка на тот же адрес не пройдёт незамеченной.
   */
  async reject(userId: string): Promise<void> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new NotFoundException('users.err.requestNotFound');
    if (user.status !== 'pending_approval') {
      throw new BadRequestException('users.err.requestDecided');
    }
    await this.prisma.user.update({
      where: { id: userId },
      data: { status: 'rejected', isActive: false },
    });
  }

  /**
   * Выдать новый одноразовый пароль и отправить письмо.
   *
   * Используется и при активации, и когда прежний пароль протух.
   * В БД попадает только хэш — открытый текст живёт ровно до отправки письма.
   */
  async issueOneTimePassword(
    userId: string,
    email: string,
    options?: { reset?: boolean },
  ): Promise<{ sent: boolean; error?: string }> {
    const password = generateOneTimePassword();
    const expiresAt = new Date(Date.now() + ONE_TIME_PASSWORD_HOURS * 3600 * 1000);

    await this.prisma.user.update({
      where: { id: userId },
      data: {
        passwordHash: await argon2.hash(password),
        mustChangePassword: true,
        passwordExpiresAt: expiresAt,
      },
    });

    // Все прежние сессии недействительны: пароль сменился.
    await this.prisma.session.updateMany({
      where: { userId, revokedAt: null },
      data: { revokedAt: new Date() },
    });

    const result = await this.mail.sendWelcome(email, {
      login: email,
      oneTimePassword: password,
      panelUrl: env.PANEL_URL,
      expiresInHours: ONE_TIME_PASSWORD_HOURS,
      // Письмо про сброс и письмо про новый аккаунт — разные по смыслу:
      // человеку с уже выбранным ником незачем читать «выберите никнейм».
      reset: options?.reset ?? false,
    });
    return { sent: result.sent, ...(result.error ? { error: result.error } : {}) };
  }
}

function toDto(user: {
  id: string;
  email: string;
  nickname: string | null;
  nicknameChangeAllowed: boolean;
  role: Role;
  isActive: boolean;
  totpEnabled: boolean;
  createdAt: Date;
  serverAccess: { serverId: string }[];
}): UserAdminDto {
  return {
    id: user.id,
    email: user.email,
    nickname: user.nickname,
    nicknameChangeAllowed: user.nicknameChangeAllowed,
    role: user.role,
    isActive: user.isActive,
    totpEnabled: user.totpEnabled,
    serverIds: user.serverAccess.map((a) => a.serverId),
    createdAt: user.createdAt.toISOString(),
  };
}
