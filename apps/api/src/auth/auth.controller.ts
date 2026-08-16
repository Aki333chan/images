import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  Param,
  Post,
  Query,
  Req,
  Res,
  UnauthorizedException,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import type { LoginResponse, MeResponse, SessionDto } from '@aurum/shared';
import { env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';
import { PermissionsService } from '../rbac/permissions.service';
import { AuthService } from './auth.service';
import { TokensService } from './tokens.service';
import { CurrentUser, Public, AuthUser } from './decorators';
import { LoginDto, OnboardingDto, TotpDisableDto, TotpEnableDto, TwoFactorDto } from './dto';
import { OnboardingService } from '../users/onboarding.service';
import { AuditRedactBody } from '../audit/audit.decorators';

const REFRESH_COOKIE = 'aurum_refresh';

@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly tokens: TokensService,
    private readonly prisma: PrismaService,
    private readonly permissions: PermissionsService,
    private readonly onboarding: OnboardingService,
  ) {}

  private setRefreshCookie(res: Response, refreshToken: string) {
    res.cookie(REFRESH_COOKIE, refreshToken, {
      httpOnly: true,
      secure: env.isProd,
      sameSite: 'lax',
      path: '/api/auth',
      maxAge: env.REFRESH_TOKEN_TTL_SEC * 1000,
    });
  }

  private clientMeta(req: Request) {
    return { userAgent: req.headers['user-agent'], ip: req.ip };
  }

  @Public()
  @Post('login')
  @HttpCode(200)
  async login(
    @Body() dto: LoginDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<LoginResponse> {
    const meta = this.clientMeta(req);
    const result = await this.auth.login(dto.email, dto.password, meta.userAgent, meta.ip);
    if (result.twoFactorRequired) {
      return { twoFactorRequired: true, twoFactorToken: result.twoFactorToken };
    }
    this.setRefreshCookie(res, result.refreshToken);
    return {
      accessToken: result.accessToken,
      me: await this.permissions.buildMeResponse(result.userId),
    };
  }

  @Public()
  @Post('2fa')
  @HttpCode(200)
  async twoFactor(
    @Body() dto: TwoFactorDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<LoginResponse> {
    const meta = this.clientMeta(req);
    const result = await this.auth.loginTwoFactor(
      dto.twoFactorToken,
      dto.code,
      meta.userAgent,
      meta.ip,
    );
    this.setRefreshCookie(res, result.refreshToken);
    return {
      accessToken: result.accessToken,
      me: await this.permissions.buildMeResponse(result.userId),
    };
  }

  @Public()
  @Post('refresh')
  @HttpCode(200)
  async refresh(
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<{ accessToken: string }> {
    const token: string | undefined = req.cookies?.[REFRESH_COOKIE];
    if (!token) throw new UnauthorizedException('Нет refresh-токена');
    const rotated = await this.tokens.rotate(token);
    this.setRefreshCookie(res, rotated.refreshToken);
    return { accessToken: rotated.accessToken };
  }

  @Post('logout')
  @HttpCode(200)
  async logout(@CurrentUser() user: AuthUser, @Res({ passthrough: true }) res: Response) {
    await this.tokens.revoke(user.sessionId);
    res.clearCookie(REFRESH_COOKIE, { path: '/api/auth' });
    return { ok: true };
  }

  @Get('me')
  async me(@CurrentUser() user: AuthUser): Promise<MeResponse> {
    return this.permissions.buildMeResponse(user.id);
  }

  /**
   * Завершение онбординга: постоянный пароль и ник сотрудника.
   *
   * Живёт в auth, а не в users: это действие пользователя над собой, и
   * никаких прав, кроме аутентификации, для него не нужно.
   */
  @Post('onboarding')
  @AuditRedactBody() // в теле два пароля
  async completeOnboarding(
    @CurrentUser() user: AuthUser,
    @Body() dto: OnboardingDto,
  ): Promise<MeResponse> {
    await this.onboarding.complete(user.id, user.sessionId, dto);
    return this.permissions.buildMeResponse(user.id);
  }

  /** Проверка ника на занятость — для подсказки прямо в форме. */
  @Get('onboarding/nickname-available')
  async nicknameAvailable(
    @CurrentUser() user: AuthUser,
    @Query('nickname') nickname?: string,
  ): Promise<{ available: boolean }> {
    if (!nickname?.trim()) return { available: false };
    return { available: await this.onboarding.isNicknameAvailable(nickname, user.id) };
  }

  @Get('sessions')
  async sessions(@CurrentUser() user: AuthUser): Promise<SessionDto[]> {
    const rows = await this.prisma.session.findMany({
      where: { userId: user.id, revokedAt: null, expiresAt: { gt: new Date() } },
      orderBy: { lastUsedAt: 'desc' },
    });
    return rows.map((s) => ({
      id: s.id,
      userAgent: s.userAgent,
      ip: s.ip,
      createdAt: s.createdAt.toISOString(),
      lastUsedAt: s.lastUsedAt.toISOString(),
      current: s.id === user.sessionId,
    }));
  }

  @Delete('sessions/:id')
  async revokeSession(@CurrentUser() user: AuthUser, @Param('id') id: string) {
    // Отзывать можно только собственные сессии.
    const session = await this.prisma.session.findUnique({ where: { id } });
    if (!session || session.userId !== user.id) {
      throw new UnauthorizedException('Сессия не найдена');
    }
    await this.tokens.revoke(id);
    return { ok: true };
  }

  @Post('totp/setup')
  async totpSetup(@CurrentUser() user: AuthUser) {
    return this.auth.totpSetup(user.id);
  }

  @Post('totp/enable')
  async totpEnable(@CurrentUser() user: AuthUser, @Body() dto: TotpEnableDto) {
    await this.auth.totpEnable(user.id, dto.code);
    return { ok: true };
  }

  @Post('totp/disable')
  async totpDisable(@CurrentUser() user: AuthUser, @Body() dto: TotpDisableDto) {
    await this.auth.totpDisable(user.id, dto.password);
    return { ok: true };
  }
}
