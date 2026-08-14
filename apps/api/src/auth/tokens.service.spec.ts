process.env.NODE_ENV = 'test';

import { UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { createHash } from 'crypto';
import { TokensService } from './tokens.service';
import { PrismaService } from '../prisma/prisma.service';

describe('TokensService (ротация refresh)', () => {
  let prisma: {
    session: { create: jest.Mock; findUnique: jest.Mock; update: jest.Mock; updateMany: jest.Mock };
    user: { findUnique: jest.Mock };
  };
  let tokens: TokensService;

  beforeEach(() => {
    prisma = {
      session: {
        create: jest.fn(),
        findUnique: jest.fn(),
        update: jest.fn().mockResolvedValue({}),
        updateMany: jest.fn().mockResolvedValue({}),
      },
      user: { findUnique: jest.fn() },
    };
    tokens = new TokensService(prisma as unknown as PrismaService, new JwtService({}));
  });

  function sha256(v: string) {
    return createHash('sha256').update(v).digest('hex');
  }

  it('rotate меняет hash refresh-токена и выдаёт новый access', async () => {
    const secret = 'old-secret';
    prisma.session.findUnique.mockResolvedValue({
      id: 's1',
      userId: 'u1',
      refreshTokenHash: sha256(secret),
      revokedAt: null,
      expiresAt: new Date(Date.now() + 100000),
    });
    prisma.user.findUnique.mockResolvedValue({ id: 'u1', isActive: true });

    const result = await tokens.rotate(`s1.${secret}`);
    expect(result.accessToken).toBeTruthy();
    expect(result.refreshToken.startsWith('s1.')).toBe(true);
    expect(result.refreshToken).not.toBe(`s1.${secret}`);
    const newHash = prisma.session.update.mock.calls[0][0].data.refreshTokenHash;
    expect(newHash).not.toBe(sha256(secret));
  });

  it('повторное использование старого refresh отзывает сессию', async () => {
    prisma.session.findUnique.mockResolvedValue({
      id: 's1',
      userId: 'u1',
      refreshTokenHash: sha256('new-secret'),
      revokedAt: null,
      expiresAt: new Date(Date.now() + 100000),
    });
    await expect(tokens.rotate('s1.stolen-old-secret')).rejects.toThrow(UnauthorizedException);
    expect(prisma.session.update).toHaveBeenCalledWith(
      expect.objectContaining({ data: { revokedAt: expect.any(Date) } }),
    );
  });

  it('отклоняет просроченную сессию', async () => {
    const secret = 'sec';
    prisma.session.findUnique.mockResolvedValue({
      id: 's1',
      userId: 'u1',
      refreshTokenHash: sha256(secret),
      revokedAt: null,
      expiresAt: new Date(Date.now() - 1000),
    });
    await expect(tokens.rotate(`s1.${secret}`)).rejects.toThrow(UnauthorizedException);
  });
});
