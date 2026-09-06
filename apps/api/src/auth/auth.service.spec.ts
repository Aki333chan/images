process.env.NODE_ENV = 'test';

import { UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import * as argon2 from 'argon2';
import { AuthService } from './auth.service';
import { TokensService } from './tokens.service';
import { TotpService } from './totp.service';
import { CryptoService } from '../common/crypto.service';
import { PrismaService } from '../prisma/prisma.service';

describe('AuthService', () => {
  let auth: AuthService;
  let prisma: { user: { findUnique: jest.Mock; findUniqueOrThrow: jest.Mock; update: jest.Mock } };
  let tokens: { createSession: jest.Mock };
  const jwt = new JwtService({});
  const totp = new TotpService(new CryptoService());

  const baseUser = {
    id: 'u1',
    email: 'gm@example.com',
    displayName: 'ГМ',
    role: 'OWNER',
    isActive: true,
    totpEnabled: false,
    totpSecretEnc: null as string | null,
    passwordHash: '',
  };

  beforeAll(async () => {
    baseUser.passwordHash = await argon2.hash('correct-password');
  });

  beforeEach(() => {
    prisma = {
      user: { findUnique: jest.fn(), findUniqueOrThrow: jest.fn(), update: jest.fn() },
    };
    tokens = {
      createSession: jest
        .fn()
        .mockResolvedValue({ accessToken: 'at', refreshToken: 'rt', sessionId: 's1' }),
    };
    auth = new AuthService(
      prisma as unknown as PrismaService,
      tokens as unknown as TokensService,
      totp,
      jwt,
    );
  });

  it('выдаёт токены при верном пароле без 2FA', async () => {
    prisma.user.findUnique.mockResolvedValue({ ...baseUser });
    const result = await auth.login('GM@example.com', 'correct-password');
    expect(result.twoFactorRequired).toBe(false);
    expect(tokens.createSession).toHaveBeenCalledWith('u1', undefined, undefined);
    // email нормализуется в нижний регистр
    expect(prisma.user.findUnique).toHaveBeenCalledWith({
      where: { email: 'gm@example.com' },
    });
  });

  it('отклоняет неверный пароль', async () => {
    prisma.user.findUnique.mockResolvedValue({ ...baseUser });
    await expect(auth.login('gm@example.com', 'wrong')).rejects.toThrow(UnauthorizedException);
    expect(tokens.createSession).not.toHaveBeenCalled();
  });

  it('отклоняет несуществующего пользователя тем же сообщением', async () => {
    prisma.user.findUnique.mockResolvedValue(null);
    await expect(auth.login('nobody@example.com', 'x')).rejects.toThrow('auth.err.badCredentials');
  });

  it('отклоняет деактивированного пользователя', async () => {
    prisma.user.findUnique.mockResolvedValue({ ...baseUser, isActive: false });
    await expect(auth.login('gm@example.com', 'correct-password')).rejects.toThrow(
      UnauthorizedException,
    );
  });

  it('при включённой 2FA не выдаёт токены, а требует второй шаг', async () => {
    const secret = totp.generateSecret();
    prisma.user.findUnique.mockResolvedValue({
      ...baseUser,
      totpEnabled: true,
      totpSecretEnc: totp.encryptSecret(secret),
    });
    const result = await auth.login('gm@example.com', 'correct-password');
    expect(result.twoFactorRequired).toBe(true);
    expect(tokens.createSession).not.toHaveBeenCalled();
    if (!result.twoFactorRequired) throw new Error('unreachable');
    expect(result.twoFactorToken).toBeTruthy();
  });

  it('завершает 2FA-логин при верном коде и отклоняет неверный', async () => {
    const { authenticator } = await import('otplib');
    const secret = totp.generateSecret();
    const user = {
      ...baseUser,
      totpEnabled: true,
      totpSecretEnc: totp.encryptSecret(secret),
    };
    prisma.user.findUnique.mockResolvedValue(user);

    const login = await auth.login('gm@example.com', 'correct-password');
    if (!login.twoFactorRequired) throw new Error('ожидался шаг 2FA');

    await expect(auth.loginTwoFactor(login.twoFactorToken, '000000')).rejects.toThrow(
      'auth.err.badTwoFaCode',
    );

    const code = authenticator.generate(secret);
    const done = await auth.loginTwoFactor(login.twoFactorToken, code);
    expect(done.accessToken).toBe('at');
  });

  it('включение 2FA требует корректный код подтверждения', async () => {
    const secret = totp.generateSecret();
    prisma.user.findUniqueOrThrow.mockResolvedValue({
      ...baseUser,
      totpSecretEnc: totp.encryptSecret(secret),
    });
    await expect(auth.totpEnable('u1', '000000')).rejects.toThrow('auth.err.badCode');
    expect(prisma.user.update).not.toHaveBeenCalled();
  });
});
