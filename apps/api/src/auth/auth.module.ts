import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { RbacModule } from '../rbac/rbac.module';
import { UsersModule } from '../users/users.module';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import { TokensService } from './tokens.service';
import { TotpService } from './totp.service';

@Module({
  // UsersModule — ради OnboardingService: онбординг это действие
  // пользователя над собой, поэтому его маршруты живут в auth.
  imports: [JwtModule.register({}), RbacModule, UsersModule],
  controllers: [AuthController],
  providers: [AuthService, TokensService, TotpService, JwtAuthGuard],
  exports: [JwtAuthGuard, TokensService, JwtModule],
})
export class AuthModule {}
