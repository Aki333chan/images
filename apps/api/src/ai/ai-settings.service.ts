import { BadRequestException, Injectable } from '@nestjs/common';
import {
  AI_MODELS,
  DEFAULT_AI_SYSTEM_PROMPT,
  type AiProvider,
  type AiSettingsDto,
} from '@aurum/shared';
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
const KEY_PROVIDER = 'ai.provider';

/** Модель по умолчанию: дешёвая из актуальных на момент написания. */
const DEFAULT_REQUESTS_PER_HOUR = 30;
const DEFAULT_TOKENS_PER_DAY = 200_000;

export const AI_MAX_TOOL_ROUNDS = 5;
export const AI_REQUEST_TIMEOUT_MS = 180_000;
const LIMITS = {
  maxInputTokens: { fallback: 32_768, min: 4096, max: 131_072 },
  maxOutputTokens: { fallback: 4096, min: 256, max: 16_384 },
  dailyBudgetUsd: { fallback: 1, min: 0.05, max: 1000 },
  monthlyBudgetUsd: { fallback: 10, min: 0.05, max: 10_000 },
} as const;

export interface AiRuntimeConfig extends Omit<
  AiSettingsDto,
  'enabled' | 'hasApiKey' | 'providerKeys'
> {
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
    const provider = await this.provider();
    const [enabled, model, legacyModel, systemPrompt, rph, tpd, deepseekKey, geminiKey, limits] =
      await Promise.all([
        this.readString(KEY_ENABLED),
        this.readString(`ai.${provider}.model`),
        this.readString(KEY_MODEL),
        this.readString(KEY_PROMPT),
        this.readString(KEY_RPH),
        this.readString(KEY_TPD),
        this.readApiKey('deepseek'),
        this.readApiKey('gemini'),
        Promise.all(
          Object.entries(LIMITS).map(async ([key, range]) => {
            const value = Number(await this.readString(`ai.${key}`));
            return [
              key,
              Number.isFinite(value) && value >= range.min && value <= range.max
                ? value
                : range.fallback,
            ];
          }),
        ),
      ]);
    const providerKeys = { deepseek: !!deepseekKey, gemini: !!geminiKey };
    return {
      enabled: enabled === 'true',
      provider,
      providerKeys,
      hasApiKey: providerKeys[provider],
      model:
        model ?? (provider === 'deepseek' ? legacyModel : null) ?? AI_MODELS[provider][0].value,
      systemPrompt: systemPrompt ?? DEFAULT_AI_SYSTEM_PROMPT,
      requestsPerHour: toPositiveInt(rph, DEFAULT_REQUESTS_PER_HOUR),
      tokensPerDay: toPositiveInt(tpd, DEFAULT_TOKENS_PER_DAY),
      ...(Object.fromEntries(limits) as Pick<AiSettingsDto, keyof typeof LIMITS>),
    };
  }

  /** Полная конфигурация с ключом — только для внутреннего использования. */
  async getRuntime(): Promise<AiRuntimeConfig | null> {
    const settings = await this.get();
    if (!settings.enabled) return null;
    const apiKey = await this.readApiKey(settings.provider);
    if (!apiKey) return null;
    return {
      ...settings,
      apiKey,
    };
  }

  async update(
    patch: {
      provider?: AiProvider;
      enabled?: boolean;
      model?: string;
      systemPrompt?: string;
      requestsPerHour?: number;
      tokensPerDay?: number;
      /** Пустая строка — не трогать сохранённый ключ; null — удалить. */
      apiKey?: string | null;
    } & Partial<Pick<AiSettingsDto, keyof typeof LIMITS>>,
  ): Promise<AiSettingsDto> {
    const provider = patch.provider ?? (await this.provider());
    if (provider !== 'deepseek' && provider !== 'gemini')
      throw new BadRequestException('ai.err.provider');
    if (patch.model !== undefined) {
      const supported =
        AI_MODELS[provider].some((m) => m.value === patch.model?.trim()) ||
        (provider === 'deepseek' && patch.model.trim() === 'deepseek-v4-flash');
      if (!supported) throw new BadRequestException('ai.err.unsupportedModel');
    }
    for (const [key, range] of Object.entries(LIMITS)) {
      const value = patch[key as keyof typeof LIMITS];
      if (value === undefined) continue;
      if (
        !Number.isFinite(value) ||
        value < range.min ||
        value > range.max ||
        (key.endsWith('Tokens') && !Number.isInteger(value))
      ) {
        throw new BadRequestException('ai.err.limits');
      }
    }
    await this.prisma.$transaction(async (tx) => {
      if (patch.provider !== undefined) await this.writeString(KEY_PROVIDER, provider, tx);
      if (patch.enabled !== undefined)
        await this.writeString(KEY_ENABLED, String(patch.enabled), tx);
      if (patch.model !== undefined)
        await this.writeString(`ai.${provider}.model`, patch.model.trim(), tx);
      if (patch.systemPrompt !== undefined)
        await this.writeString(KEY_PROMPT, patch.systemPrompt, tx);
      if (patch.requestsPerHour !== undefined)
        await this.writeString(KEY_RPH, String(patch.requestsPerHour), tx);
      if (patch.tokensPerDay !== undefined)
        await this.writeString(KEY_TPD, String(patch.tokensPerDay), tx);
      for (const key of Object.keys(LIMITS) as (keyof typeof LIMITS)[]) {
        if (patch[key] !== undefined) await this.writeString(`ai.${key}`, String(patch[key]), tx);
      }

      const apiKeyName = provider === 'deepseek' ? KEY_API : 'ai.gemini.apiKey';
      if (patch.apiKey === null) {
        await tx.integrationSecret.deleteMany({ where: { key: apiKeyName } });
      } else if (patch.apiKey?.trim()) {
        const valueEnc = this.crypto.encrypt(patch.apiKey.trim());
        await tx.integrationSecret.upsert({
          where: { key: apiKeyName },
          create: { key: apiKeyName, valueEnc },
          update: { valueEnc },
        });
      }
    });
    return this.get();
  }

  private async provider(): Promise<AiProvider> {
    return (await this.readString(KEY_PROVIDER)) === 'gemini' ? 'gemini' : 'deepseek';
  }

  private async readApiKey(provider: AiProvider): Promise<string | null> {
    const key = provider === 'deepseek' ? KEY_API : 'ai.gemini.apiKey';
    const row = await this.prisma.integrationSecret.findUnique({ where: { key } });
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

  private async writeString(
    key: string,
    value: string,
    db: Pick<PrismaService, 'appSetting'>,
  ): Promise<void> {
    await db.appSetting.upsert({
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
