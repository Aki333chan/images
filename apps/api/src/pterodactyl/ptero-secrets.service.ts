import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { CryptoService } from '../common/crypto.service';
import { env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

export const SECRET_KEYS = {
  APP_KEY: 'ptero_app_api_key',
  CLIENT_KEY: 'ptero_client_api_key',
} as const;

/**
 * Ключи Pterodactyl хранятся в БД в зашифрованном виде (AES-256-GCM).
 * При старте: если ключ задан в env — он шифруется и записывается в БД
 * (env-значение имеет приоритет и обновляет запись), после чего переменную
 * можно удалить из .env.
 */
@Injectable()
export class PteroSecretsService implements OnModuleInit {
  private readonly logger = new Logger(PteroSecretsService.name);
  private cache = new Map<string, string>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
  ) {}

  async onModuleInit() {
    await this.importFromEnv(SECRET_KEYS.APP_KEY, env.PTERO_APP_API_KEY);
    await this.importFromEnv(SECRET_KEYS.CLIENT_KEY, env.PTERO_CLIENT_API_KEY);
  }

  private async importFromEnv(key: string, value: string) {
    if (!value) return;
    await this.prisma.integrationSecret.upsert({
      where: { key },
      create: { key, valueEnc: this.crypto.encrypt(value) },
      update: { valueEnc: this.crypto.encrypt(value) },
    });
    this.logger.log(`Секрет ${key} импортирован из окружения в БД (зашифрован)`);
  }

  async get(key: string): Promise<string | null> {
    const cached = this.cache.get(key);
    if (cached) return cached;
    const row = await this.prisma.integrationSecret.findUnique({ where: { key } });
    if (!row) return null;
    const value = this.crypto.decrypt(row.valueEnc);
    this.cache.set(key, value);
    return value;
  }
}
