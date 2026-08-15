import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { CryptoService } from '../../common/crypto.service';
import { PrismaService } from '../../prisma/prisma.service';
import { RconService } from './rcon/rcon.service';

/**
 * Секреты модуля лежат в servers.credentials_enc (поле ядра) как зашифрованный
 * AES-256-GCM JSON. Наружу они не отдаются НИКОГДА: контроллер возвращает
 * только флаги «настроено / не настроено».
 */
export interface MinecraftServerCredentials {
  rcon?: {
    host: string;
    port: number;
    password: string;
  };
  companion?: {
    /** Базовый URL companion-плагина (приватный адрес через туннель). */
    baseUrl: string;
    token: string;
  };
  lastSeenAt?: string;
}

@Injectable()
export class MinecraftConfigService {
  private readonly logger = new Logger(MinecraftConfigService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
    private readonly rcon: RconService,
  ) {}

  async read(serverId: string): Promise<MinecraftServerCredentials> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { credentialsEnc: true },
    });
    if (!server?.credentialsEnc) return {};
    try {
      return JSON.parse(this.crypto.decrypt(server.credentialsEnc)) as MinecraftServerCredentials;
    } catch {
      // Не логируем содержимое — оно секретное.
      this.logger.error(`Не удалось расшифровать креды сервера ${serverId}`);
      return {};
    }
  }

  private async write(serverId: string, creds: MinecraftServerCredentials): Promise<void> {
    await this.prisma.server.update({
      where: { id: serverId },
      data: { credentialsEnc: this.crypto.encrypt(JSON.stringify(creds)) },
    });
    // Настройки могли измениться — старое соединение больше не актуально.
    this.rcon.drop(serverId);
  }

  async setRcon(serverId: string, host: string, port: number, password: string): Promise<void> {
    const current = await this.read(serverId);
    await this.write(serverId, { ...current, rcon: { host, port, password } });
  }

  async setCompanion(serverId: string, baseUrl: string | null, token: string | null): Promise<void> {
    const current = await this.read(serverId);
    if (!baseUrl) {
      delete current.companion;
      await this.write(serverId, current);
      return;
    }
    if (!token) throw new BadRequestException('Для companion-плагина нужен токен');
    await this.write(serverId, {
      ...current,
      companion: { baseUrl: baseUrl.replace(/\/$/, ''), token },
    });
  }

  /** Требует настроенного RCON — иначе понятная 400-ошибка вместо падения. */
  async requireRcon(serverId: string): Promise<NonNullable<MinecraftServerCredentials['rcon']>> {
    const creds = await this.read(serverId);
    if (!creds.rcon) {
      throw new BadRequestException(
        'RCON для этого сервера не настроен — задайте хост, порт и пароль в настройках модуля',
      );
    }
    return creds.rcon;
  }

  async markSeen(serverId: string): Promise<void> {
    const current = await this.read(serverId);
    if (!current.rcon) return;
    await this.prisma.server.update({
      where: { id: serverId },
      data: {
        credentialsEnc: this.crypto.encrypt(
          JSON.stringify({ ...current, lastSeenAt: new Date().toISOString() }),
        ),
      },
    });
  }
}
