import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { CryptoService } from '../../common/crypto.service';
import { PrismaService } from '../../prisma/prisma.service';

/**
 * Секреты модуля лежат в servers.credentials_enc (поле ядра) как
 * зашифрованный AES-256-GCM JSON — там же, где креды других модулей.
 * Ключ `palworld` свой, поэтому смена модуля у сервера чужие настройки
 * не портит.
 *
 * Наружу не отдаётся ничего: ни адрес, ни пароль администратора. Контроллер
 * возвращает только флаги «настроено / не настроено».
 */
export interface PalworldServerCredentials {
  palworld?: {
    /** Базовый адрес REST API, приватный: http://10.0.0.2:8212 */
    baseUrl: string;
    /**
     * Пароль администратора сервера (AdminPassword из PalWorldSettings.ini).
     * Логин у Basic-аутентификации всегда `admin` — он задан самим сервером
     * и не настраивается.
     */
    adminPassword: string;
  };
  lastSeenAt?: string;
}

/** Логин Basic-аутентификации у Palworld фиксированный. */
export const PALWORLD_API_USER = 'admin';

@Injectable()
export class PalworldConfigService {
  private readonly logger = new Logger(PalworldConfigService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
  ) {}

  async read(serverId: string): Promise<PalworldServerCredentials> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { credentialsEnc: true },
    });
    if (!server?.credentialsEnc) return {};
    try {
      return JSON.parse(this.crypto.decrypt(server.credentialsEnc)) as PalworldServerCredentials;
    } catch {
      // Содержимое не логируем — оно секретное.
      this.logger.error(`Не удалось расшифровать креды сервера ${serverId}`);
      return {};
    }
  }

  private async write(serverId: string, creds: PalworldServerCredentials): Promise<void> {
    await this.prisma.server.update({
      where: { id: serverId },
      data: { credentialsEnc: this.crypto.encrypt(JSON.stringify(creds)) },
    });
  }

  async setApi(serverId: string, baseUrl: string | null, adminPassword: string | null) {
    const current = await this.read(serverId);
    if (!baseUrl) {
      delete current.palworld;
      await this.write(serverId, current);
      return;
    }
    if (!adminPassword) {
      throw new BadRequestException('Нужен пароль администратора сервера (AdminPassword)');
    }
    await this.write(serverId, {
      ...current,
      palworld: { baseUrl: baseUrl.replace(/\/+$/, ''), adminPassword },
    });
  }

  /** Требует настроенного API — иначе понятная 400 вместо падения. */
  async require(serverId: string): Promise<NonNullable<PalworldServerCredentials['palworld']>> {
    const creds = await this.read(serverId);
    if (!creds.palworld) {
      throw new BadRequestException(
        'REST API Palworld для этого сервера не настроен — задайте адрес и пароль администратора ' +
          'в настройках модуля. На игровом сервере нужен RESTAPIEnabled=True.',
      );
    }
    return creds.palworld;
  }

  async markSeen(serverId: string): Promise<void> {
    const current = await this.read(serverId);
    if (!current.palworld) return;
    await this.write(serverId, { ...current, lastSeenAt: new Date().toISOString() });
  }
}
