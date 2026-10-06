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
import { IsIn, IsInt, IsOptional, IsString, IsUUID, Matches, Max, MaxLength, Min } from 'class-validator';
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

class SiteProfileDto {
  @IsUUID()
  serverId!: string;

  @IsUUID()
  playerUuid!: string;
}

class SiteGuildsDto {
  @IsUUID()
  serverId!: string;

  @IsString()
  @MaxLength(80)
  query!: string;
}

class SiteGuildDto {
  @IsUUID() serverId!: string;
  @IsInt() @Min(1) @Max(Number.MAX_SAFE_INTEGER) guildId!: number;
  @IsOptional() @IsUUID() playerUuid?: string;
}
class SiteGuildActionDto extends SiteProfileDto {
  @IsInt() @Min(1) @Max(Number.MAX_SAFE_INTEGER) guildId!: number;
  @IsIn(['invite', 'join', 'kick', 'promote', 'demote']) action!: string;
  @IsOptional() @IsUUID() targetUuid?: string;
}

/** Fixed player-owned operations only. No RCON, IPs, inventory or financial mutations. */
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

  @Post('profile')
  async profile(@Body() dto: SiteProfileDto) {
    await this.publishedServer(dto.serverId);
    return this.companion.getSiteProfile(dto.serverId, dto.playerUuid);
  }

  @Post('guilds')
  async guilds(@Body() dto: SiteGuildsDto) {
    await this.publishedServer(dto.serverId);
    const guilds = await this.companion.getGuilds(dto.serverId, dto.query.trim() || null);
    return {
      available: guilds !== null,
      // No guild treasury, roster, private UUIDs or administration data.
      guilds: (guilds ?? [])
        .slice(0, 50)
        .map(({ id, name, tag, leaderName, memberCount }) => ({ id, name, tag, leaderName, memberCount })),
      limit: 50,
    };
  }

  @Post('guild')
  async guild(@Body() dto: SiteGuildDto) {
    await this.publishedServer(dto.serverId);
    return this.companion.getSiteGuild(dto.serverId, dto.guildId, dto.playerUuid);
  }

  @Post('guild-action')
  async guildAction(@Body() dto: SiteGuildActionDto) {
    await this.publishedServer(dto.serverId);
    const { serverId, ...action } = dto;
    return this.companion.executeSiteGuildAction(serverId, action);
  }

  private async publishedServer(id: string) {
    const { servers } = await this.servers();
    if (!servers.some((server) => server.id === id))
      throw new ForbiddenException('Server is not published on the website');
  }
}
