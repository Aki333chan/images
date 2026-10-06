import {
  Body,
  CanActivate,
  Controller,
  ExecutionContext,
  ForbiddenException,
  Get,
  Injectable,
  Post,
  UseGuards,
} from '@nestjs/common';
import { IsString, IsUUID, Matches } from 'class-validator';
import { Public } from '../../auth/decorators';
import { constantTimeEquals, isPrivateAddress } from '../../common/private-network';
import { env } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { CompanionService } from './companion.service';

@Injectable()
export class SiteBridgeGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const req = context.switchToHttp().getRequest();
    const expected = env.SITE_BRIDGE_TOKEN;
    const authorization: unknown = req.headers?.authorization;
    if (
      expected.length < 32 ||
      !isPrivateAddress(req.ip) ||
      typeof authorization !== 'string' ||
      !authorization.startsWith('Bearer ') ||
      !constantTimeEquals(authorization.slice(7), expected)
    ) {
      throw new ForbiddenException('Website bridge access denied');
    }
    return true;
  }
}

class SiteLinkDto {
  @IsUUID()
  serverId!: string;

  @IsString()
  @Matches(/^[A-Z2-9]{8}$/)
  code!: string;
}

/** Two fixed operations only. Site cannot access RCON, player IPs, money or staff tools. */
@Public()
@UseGuards(SiteBridgeGuard)
@Controller('internal/site/minecraft')
export class SiteBridgeController {
  constructor(
    private readonly prisma: PrismaService,
    private readonly companion: CompanionService,
  ) {}

  @Get('servers')
  async servers() {
    const allowed = env.SITE_BRIDGE_SERVERS.split(',')
      .map((id) => id.trim())
      .filter(Boolean)
      .slice(0, 32);
    const servers = await this.prisma.server.findMany({
      where: { id: { in: allowed }, moduleId: 'minecraft' },
      select: { id: true, name: true },
      orderBy: { name: 'asc' },
    });
    return { servers };
  }

  @Post('consume')
  async consume(@Body() dto: SiteLinkDto) {
    const { servers } = await this.servers();
    const server = servers.find((item) => item.id === dto.serverId);
    if (!server) throw new ForbiddenException('Server is not published on the website');
    const player = await this.companion.consumeSiteLink(server.id, dto.code);
    return {
      serverId: server.id,
      serverName: server.name,
      playerUuid: player.uuid,
      playerName: player.name,
    };
  }
}
