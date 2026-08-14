import { Injectable, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { createHash, randomBytes } from 'crypto';
import { env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

export interface IssuedTokens {
  accessToken: string;
  refreshToken: string;
  sessionId: string;
}

/**
 * Access — короткоживущий JWT (только sub + sid).
 * Refresh — непрозрачная случайная строка `<sessionId>.<secret>`, в БД хранится
 * только SHA-256 от secret; ротация на каждом refresh, отзыв через revokedAt.
 */
@Injectable()
export class TokensService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly jwt: JwtService,
  ) {}

  private hash(value: string): string {
    return createHash('sha256').update(value).digest('hex');
  }

  private async signAccess(userId: string, sessionId: string): Promise<string> {
    return this.jwt.signAsync(
      { sub: userId, sid: sessionId, purpose: 'access' },
      { secret: env.JWT_ACCESS_SECRET, expiresIn: env.ACCESS_TOKEN_TTL_SEC },
    );
  }

  async createSession(userId: string, userAgent?: string, ip?: string): Promise<IssuedTokens> {
    const secret = randomBytes(32).toString('base64url');
    const session = await this.prisma.session.create({
      data: {
        userId,
        refreshTokenHash: this.hash(secret),
        userAgent: userAgent ?? null,
        ip: ip ?? null,
        expiresAt: new Date(Date.now() + env.REFRESH_TOKEN_TTL_SEC * 1000),
      },
    });
    return {
      accessToken: await this.signAccess(userId, session.id),
      refreshToken: `${session.id}.${secret}`,
      sessionId: session.id,
    };
  }

  async rotate(refreshToken: string): Promise<IssuedTokens & { userId: string }> {
    const dot = refreshToken.indexOf('.');
    if (dot <= 0) throw new UnauthorizedException('Некорректный refresh-токен');
    const sessionId = refreshToken.slice(0, dot);
    const secret = refreshToken.slice(dot + 1);

    const session = await this.prisma.session.findUnique({ where: { id: sessionId } });
    if (
      !session ||
      session.revokedAt ||
      session.expiresAt < new Date() ||
      session.refreshTokenHash !== this.hash(secret)
    ) {
      // Повторное использование старого refresh после ротации — признак кражи:
      // отзываем сессию целиком.
      if (session && !session.revokedAt) {
        await this.prisma.session.update({
          where: { id: sessionId },
          data: { revokedAt: new Date() },
        });
      }
      throw new UnauthorizedException('Refresh-токен недействителен');
    }

    const user = await this.prisma.user.findUnique({ where: { id: session.userId } });
    if (!user || !user.isActive) throw new UnauthorizedException('Пользователь деактивирован');

    const newSecret = randomBytes(32).toString('base64url');
    await this.prisma.session.update({
      where: { id: sessionId },
      data: {
        refreshTokenHash: this.hash(newSecret),
        lastUsedAt: new Date(),
        expiresAt: new Date(Date.now() + env.REFRESH_TOKEN_TTL_SEC * 1000),
      },
    });
    return {
      accessToken: await this.signAccess(session.userId, sessionId),
      refreshToken: `${sessionId}.${newSecret}`,
      sessionId,
      userId: session.userId,
    };
  }

  async revoke(sessionId: string): Promise<void> {
    await this.prisma.session.updateMany({
      where: { id: sessionId, revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }
}
