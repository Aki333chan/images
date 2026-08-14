import { BullModule } from '@nestjs/bullmq';
import { Module } from '@nestjs/common';
import { APP_GUARD, APP_INTERCEPTOR } from '@nestjs/core';
import { env } from './config/env';
import { PrismaModule } from './prisma/prisma.module';
import { CryptoModule } from './common/crypto.module';
import { AuthModule } from './auth/auth.module';
import { JwtAuthGuard } from './auth/jwt-auth.guard';
import { RbacModule } from './rbac/rbac.module';
import { PermissionsGuard } from './rbac/permissions.guard';
import { AuditModule } from './audit/audit.module';
import { AuditInterceptor } from './audit/audit.interceptor';
import { WsModule } from './ws/ws.module';
import { PterodactylModule } from './pterodactyl/pterodactyl.module';
import { ServersModule } from './servers/servers.module';
import { TicketsModule } from './tickets/tickets.module';
import { UsersModule } from './users/users.module';
import { GameModulesModule } from './modules/game-modules.module';

@Module({
  imports: [
    PrismaModule,
    CryptoModule,
    BullModule.forRoot({ connection: { url: env.REDIS_URL } }),
    RbacModule,
    AuthModule,
    WsModule,
    AuditModule,
    PterodactylModule,
    ServersModule,
    TicketsModule,
    UsersModule,
    GameModulesModule.forRoot(),
  ],
  providers: [
    // Порядок важен: сначала аутентификация, затем RBAC по состоянию БД.
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: PermissionsGuard },
    { provide: APP_INTERCEPTOR, useClass: AuditInterceptor },
  ],
})
export class AppModule {}
