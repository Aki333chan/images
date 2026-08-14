import { Injectable, UnauthorizedException, BadRequestException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import * as argon2 from 'argon2';
import { env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';
import { TokensService, IssuedTokens } from './tokens.service';
import { TotpService } from './totp.service';

interface TwoFactorTokenPayload {
  sub: string;
  purpose: '2fa';
}

export type LoginResult =
  | { twoFactorRequired: true; twoFactorToken: string }
  | ({ twoFactorRequired: false } & IssuedTokens & { userId: string });

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokensService,
    private readonly totp: TotpService,
    private readonly jwt: JwtService,
  ) {}

  async login(email: string, password: string, userAgent?: string, ip?: string): Promise<LoginResult> {
    const user = await this.prisma.user.findUnique({ where: { email: email.toLowerCase() } });
    // Хешируем даже при отсутствии пользователя — не раскрываем существование email по таймингу.
    if (!user) {
      await argon2.hash(password);
      throw new UnauthorizedException('Неверный email или пароль');
    }
    if (!user.isActive) throw new UnauthorizedException('Учётная запись деактивирована');
    const ok = await argon2.verify(user.passwordHash, password);
    if (!ok) throw new UnauthorizedException('Неверный email или пароль');

    if (user.totpEnabled) {
      const twoFactorToken = await this.jwt.signAsync(
        { sub: user.id, purpose: '2fa' },
        { secret: env.JWT_ACCESS_SECRET, expiresIn: 300 },
      );
      return { twoFactorRequired: true, twoFactorToken };
    }

    const issued = await this.tokens.createSession(user.id, userAgent, ip);
    return { twoFactorRequired: false, ...issued, userId: user.id };
  }

  async loginTwoFactor(
    twoFactorToken: string,
    code: string,
    userAgent?: string,
    ip?: string,
  ): Promise<IssuedTokens & { userId: string }> {
    let payload: TwoFactorTokenPayload;
    try {
      payload = await this.jwt.verifyAsync<TwoFactorTokenPayload>(twoFactorToken, {
        secret: env.JWT_ACCESS_SECRET,
      });
    } catch {
      throw new UnauthorizedException('Сессия 2FA истекла, войдите заново');
    }
    if (payload.purpose !== '2fa') throw new UnauthorizedException('Неверный тип токена');

    const user = await this.prisma.user.findUnique({ where: { id: payload.sub } });
    if (!user || !user.isActive || !user.totpEnabled || !user.totpSecretEnc) {
      throw new UnauthorizedException('2FA недоступна');
    }
    if (!this.totp.verify(code, this.totp.decryptSecret(user.totpSecretEnc))) {
      throw new UnauthorizedException('Неверный код 2FA');
    }
    const issued = await this.tokens.createSession(user.id, userAgent, ip);
    return { ...issued, userId: user.id };
  }

  /** Шаг 1 включения 2FA: генерируем секрет, сохраняем зашифрованным, но не включаем. */
  async totpSetup(userId: string): Promise<{ secret: string; otpauthUrl: string }> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (user.totpEnabled) throw new BadRequestException('2FA уже включена');
    const secret = this.totp.generateSecret();
    await this.prisma.user.update({
      where: { id: userId },
      data: { totpSecretEnc: this.totp.encryptSecret(secret) },
    });
    return { secret, otpauthUrl: this.totp.buildOtpAuthUrl(user.email, secret) };
  }

  /** Шаг 2: подтверждение кодом из приложения. */
  async totpEnable(userId: string, code: string): Promise<void> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (user.totpEnabled) throw new BadRequestException('2FA уже включена');
    if (!user.totpSecretEnc) throw new BadRequestException('Сначала вызовите /auth/totp/setup');
    if (!this.totp.verify(code, this.totp.decryptSecret(user.totpSecretEnc))) {
      throw new BadRequestException('Неверный код');
    }
    await this.prisma.user.update({ where: { id: userId }, data: { totpEnabled: true } });
  }

  async totpDisable(userId: string, password: string): Promise<void> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (!(await argon2.verify(user.passwordHash, password))) {
      throw new UnauthorizedException('Неверный пароль');
    }
    await this.prisma.user.update({
      where: { id: userId },
      data: { totpEnabled: false, totpSecretEnc: null },
    });
  }
}
