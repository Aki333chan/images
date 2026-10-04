import { BadRequestException } from '@nestjs/common';
import type { AiProvider } from '@aurum/shared';
import type { PrismaService } from '../prisma/prisma.service';
import {
  AI_MAX_TOOL_ROUNDS,
  AI_REQUEST_TIMEOUT_MS,
  type AiRuntimeConfig,
} from './ai-settings.service';

/** USD/million. DeepSeek peak, no cache discount: the budget is a conservative ceiling. */
export function aiTokenPrices(
  provider: AiProvider,
  model: string,
  now = new Date(),
): [number, number] {
  if (provider === 'deepseek') {
    if (model === 'deepseek-flash' || model === 'deepseek-v4-flash') return [0.3, 1.2];
    if (model === 'deepseek-v4-pro') return [1.32, 3.96];
  } else {
    if (model === 'gemini-3.8-flash')
      return now < new Date('2027-01-01T00:00:00Z') ? [0.75, 3.75] : [1.5, 7.5];
    if (model === 'gemini-3.5-flash-lite') return [0.3, 2.5];
    if (model === 'gemini-3.1-flash-lite') return [0.25, 1.5];
  }
  throw new BadRequestException('ai.err.unsupportedModel');
}

export function aiCostMicros(
  provider: AiProvider,
  model: string,
  input: number,
  output: number,
  now = new Date(),
): bigint {
  const [inputPrice, outputPrice] = aiTokenPrices(provider, model, now);
  return BigInt(Math.ceil(input * inputPrice + output * outputPrice));
}

export function aiBudgetDates() {
  const day = new Date();
  day.setUTCHours(0, 0, 0, 0);
  const month = new Date(day);
  month.setUTCDate(1);
  return { day, month, hour: new Date(Date.now() - 3_600_000) };
}

export async function aiBudgetUsage(prisma: PrismaService) {
  const { day, month } = aiBudgetDates();
  const total = async (since: Date) => {
    const sum = await prisma.aiUsageLog.aggregate({
      where: { createdAt: { gte: since } },
      _sum: { costUsdMicros: true, reservedCostUsdMicros: true },
    });
    return (
      Number((sum._sum.costUsdMicros ?? 0n) + (sum._sum.reservedCostUsdMicros ?? 0n)) / 1_000_000
    );
  };
  const [dailyCostUsd, monthlyCostUsd] = await Promise.all([total(day), total(month)]);
  return { dailyCostUsd, monthlyCostUsd };
}

export async function reserveAiRequest(
  prisma: PrismaService,
  userId: string,
  config: AiRuntimeConfig,
) {
  const tokens = AI_MAX_TOOL_ROUNDS * (config.maxInputTokens + config.maxOutputTokens);
  const cost = aiCostMicros(
    config.provider,
    config.model,
    AI_MAX_TOOL_ROUNDS * config.maxInputTokens,
    AI_MAX_TOOL_ROUNDS * config.maxOutputTokens,
    // Also cover a scheduled price increase while this request is still running.
    new Date(Date.now() + AI_REQUEST_TIMEOUT_MS),
  );
  const { day, month, hour } = aiBudgetDates();
  // ponytail: one short global DB lock; split by billing account only if real AI throughput warrants it.
  return prisma.$transaction(async (tx) => {
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(414914170::bigint)`;
    const [requests, userTokens, daily, monthly] = await Promise.all([
      tx.aiUsageLog.count({ where: { userId, createdAt: { gte: hour } } }),
      tx.aiUsageLog.aggregate({
        where: { userId, createdAt: { gte: day } },
        _sum: { promptTokens: true, completionTokens: true, reservedTokens: true },
      }),
      tx.aiUsageLog.aggregate({
        where: { createdAt: { gte: day } },
        _sum: { costUsdMicros: true, reservedCostUsdMicros: true },
      }),
      tx.aiUsageLog.aggregate({
        where: { createdAt: { gte: month } },
        _sum: { costUsdMicros: true, reservedCostUsdMicros: true },
      }),
    ]);
    if (requests >= config.requestsPerHour) throw new BadRequestException('ai.err.rateLimit');
    const usedTokens =
      (userTokens._sum.promptTokens ?? 0) +
      (userTokens._sum.completionTokens ?? 0) +
      (userTokens._sum.reservedTokens ?? 0);
    if (usedTokens + tokens > config.tokensPerDay)
      throw new BadRequestException('ai.err.tokenReserve');
    const usedCost = (sum: typeof daily) =>
      (sum._sum.costUsdMicros ?? 0n) + (sum._sum.reservedCostUsdMicros ?? 0n);
    if (
      usedCost(daily) + cost > BigInt(Math.floor(config.dailyBudgetUsd * 1_000_000)) ||
      usedCost(monthly) + cost > BigInt(Math.floor(config.monthlyBudgetUsd * 1_000_000))
    ) {
      throw new BadRequestException('ai.err.budgetLimit');
    }
    return tx.aiUsageLog.create({
      data: {
        userId,
        provider: config.provider,
        model: config.model,
        status: 'reserved',
        reservedTokens: tokens,
        reservedCostUsdMicros: cost,
      },
    });
  });
}
