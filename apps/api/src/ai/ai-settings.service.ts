import { Injectable } from '@nestjs/common';
import { DEFAULT_AI_SYSTEM_PROMPT, type AiSettingsDto } from '@aurum/shared';
import { CryptoService } from '../common/crypto.service';
import { PrismaService } from '../prisma/prisma.service';

/**
 * Настройки AI-ассистента.
 *
 * Ключ API лежит зашифрованным в integration_secrets — там же, где ключи
 * Pterodactyl, пароли RCON и пароль почтового ящика. Остальное (модель,
 * системный промпт, лимиты) секретом не является и хранится открыто в
 * app_settings, чтобы его можно было прочитать и поправить.
 */
const KEY_API = 'ai.deepseek.apiKey';
const KEY_ENABLED = 'ai.enabled';
const KEY_MODEL = 'ai.model';
const KEY_PROMPT = 'ai.systemPrompt';
const KEY_RPH = 'ai.requestsPerHour';
const KEY_TPD = 'ai.tokensPerDay';

/** Модель по умолчанию: дешёвая из актуальных на момент написания. */
const DEFAULT_MODEL = 'deepseek-v4-flash';
const DEFAULT_REQUESTS_PER_HOUR = 30;
const DEFAULT_TOKENS_PER_DAY = 200_000;

export interface AiRuntimeConfig {
  apiKey: string;
  model: string;
  systemPrompt: string;
  requestsPerHour: number;
  tokensPerDay: number;
}

@Injectable()
export class AiSettingsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
  ) {}

  async get(): Promise<AiSettingsDto> {
    const [enabled, model, systemPrompt, rph, tpd, key] = await Promise.all([
      this.readString(KEY_ENABLED),
      this.readString(KEY_MODEL),
      this.readString(KEY_PROMPT),
      this.readString(KEY_RPH),
      this.readString(KEY_TPD),
      this.readApiKey(),
    ]);
    return {
      enabled: enabled === 'true',
      hasApiKey: !!key,
      model: model ?? DEFAULT_MODEL,
      systemPrompt: systemPrompt ?? DEFAULT_AI_SYSTEM_PROMPT,
      requestsPerHour: toPositiveInt(rph, DEFAULT_REQUESTS_PER_HOUR),
      tokensPerDay: toPositiveInt(tpd, DEFAULT_TOKENS_PER_DAY),
    };
  }

  /** Полная конфигурация с ключом — только для внутреннего использования. */
  async getRuntime(): Promise<AiRuntimeConfig | null> {
    const settings = await this.get();
    if (!settings.enabled) return null;
    const apiKey = await this.readApiKey();
    if (!apiKey) return null;
    return {
      apiKey,
      model: settings.model,
      systemPrompt: settings.systemPrompt,
      requestsPerHour: settings.requestsPerHour,
      tokensPerDay: settings.tokensPerDay,
    };
  }

  async update(patch: {
    enabled?: boolean;
    model?: string;
    systemPrompt?: string;
    requestsPerHour?: number;
    tokensPerDay?: number;
    /** Пустая строка — не трогать сохранённый ключ; null — удалить. */
    apiKey?: string | null;
  }): Promise<AiSettingsDto> {
    if (patch.enabled !== undefined) await this.writeString(KEY_ENABLED, String(patch.enabled));
    if (patch.model !== undefined) await this.writeString(KEY_MODEL, patch.model.trim());
    if (patch.systemPrompt !== undefined) await this.writeString(KEY_PROMPT, patch.systemPrompt);
    if (patch.requestsPerHour !== undefined)
      await this.writeString(KEY_RPH, String(patch.requestsPerHour));
    if (patch.tokensPerDay !== undefined)
      await this.writeString(KEY_TPD, String(patch.tokensPerDay));

    if (patch.apiKey === null) {
      await this.prisma.integrationSecret.deleteMany({ where: { key: KEY_API } });
    } else if (patch.apiKey) {
      const valueEnc = this.crypto.encrypt(patch.apiKey.trim());
      await this.prisma.integrationSecret.upsert({
        where: { key: KEY_API },
        create: { key: KEY_API, valueEnc },
        update: { valueEnc },
      });
    }
    return this.get();
  }

  private async readApiKey(): Promise<string | null> {
    const row = await this.prisma.integrationSecret.findUnique({ where: { key: KEY_API } });
    if (!row) return null;
    try {
      return this.crypto.decrypt(row.valueEnc);
    } catch {
      // Значение не логируем — оно секретное.
      return null;
    }
  }

  private async readString(key: string): Promise<string | null> {
    const row = await this.prisma.appSetting.findUnique({ where: { key } });
    return row?.value ?? null;
  }

  private async writeString(key: string, value: string): Promise<void> {
    await this.prisma.appSetting.upsert({
      where: { key },
      create: { key, value, updatedAt: new Date() },
      update: { value, updatedAt: new Date() },
    });
  }
}

function toPositiveInt(raw: string | null, fallback: number): number {
  const value = Number(raw);
  return Number.isInteger(value) && value > 0 ? value : fallback;
}
