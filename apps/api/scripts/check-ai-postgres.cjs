// Explicit disposable PostgreSQL check. No provider HTTP calls or game/panel mutations.
/* eslint-disable @typescript-eslint/no-require-imports -- Standalone Node entry point for compiled CommonJS API checkouts. */
const assert = require('node:assert/strict');
const path = require('node:path');
const { createRequire } = require('node:module');
const { randomUUID } = require('node:crypto');
/* eslint-enable @typescript-eslint/no-require-imports */

const url = new URL(process.env.DATABASE_URL || 'postgresql://localhost/missing');
const database = decodeURIComponent(url.pathname.slice(1));
assert.match(database, /^aurum_ai_stage_[a-z0-9_]+$/, 'Use a dedicated aurum_ai_stage_* database.');
assert.ok(!url.searchParams.has('schema') || url.searchParams.get('schema') === 'public');
process.env.NODE_ENV = 'test';
const repo = path.resolve(process.env.AI_CHECK_REPO || path.join(__dirname, '../../..'));
const load = createRequire(path.join(repo, 'package.json'));
const { PrismaClient } = load('@prisma/client');
const db = new PrismaClient();
const compiled = (name) => load(path.join(repo, 'apps/api/dist', name));
const legacyUser = 'ai-qa-legacy';
const legacyAction = '66da2285-0983-4ce6-bc6a-eed51b0e21ea';
const config = {
  provider: 'deepseek',
  model: 'deepseek-flash',
  apiKey: 'qa-only',
  systemPrompt: 'QA only.',
  requestsPerHour: 30,
  tokensPerDay: 200000,
  maxInputTokens: 32768,
  maxOutputTokens: 4096,
  dailyBudgetUsd: 1,
  monthlyBudgetUsd: 10,
};

async function seedLegacy() {
  assert.equal(await db.aiUsageLog.count(), 0, 'Legacy seed requires an empty database.');
  const { CryptoService } = compiled('common/crypto.service.js');
  const crypto = new CryptoService();
  for (const [key, value] of Object.entries({
    'ai.enabled': 'true',
    'ai.model': 'deepseek-v4-flash',
    'ai.systemPrompt': 'QA only.',
  }))
    await db.appSetting.create({ data: { key, value } });
  for (const provider of ['deepseek', 'gemini'])
    await db.integrationSecret.create({
      data: { key: `ai.${provider}.apiKey`, valueEnc: crypto.encrypt(`${provider}-qa-only`) },
    });
  await db.aiUsageLog.create({
    data: {
      userId: legacyUser,
      model: 'deepseek-v4-flash',
      promptTokens: 321,
      completionTokens: 123,
      toolCalls: 1,
      createdAt: new Date('2020-01-01T00:00:00Z'),
    },
  });
  await db.aiPendingAction.create({
    data: {
      id: legacyAction,
      userId: legacyUser,
      tool: 'change_player_balance',
      args: { player: 'Steve' },
    },
  });
  console.log('PostgreSQL QA: legacy fixture seeded (synthetic keys only).');
}

async function check() {
  const { reserveAiRequest, aiBudgetUsage } = compiled('ai/ai-budget.js');
  const { AiSettingsService } = compiled('ai/ai-settings.service.js');
  const { CryptoService } = compiled('common/crypto.service.js');
  const { AiService } = compiled('ai/ai.service.js');
  const { AiProviderError } = compiled('ai/ai-provider.client.js');
  const { I18nService } = compiled('i18n/i18n.service.js');
  const settings = new AiSettingsService(db, new CryptoService());
  const cleanUsage = () =>
    db.aiUsageLog.deleteMany({ where: { userId: { startsWith: 'ai-qa-' } } });

  const legacy = await db.aiUsageLog.findFirstOrThrow({ where: { userId: legacyUser } });
  assert.equal(legacy.promptTokens, 321);
  assert.equal(legacy.completionTokens, 123);
  assert.equal(legacy.provider, 'deepseek');
  assert.equal(legacy.costUsdMicros, 0n);
  assert.equal(legacy.reservedCostUsdMicros, 0n);
  assert.equal(legacy.reservedTokens, 0);
  assert.equal(legacy.status, 'complete');
  assert.equal(
    (await db.aiPendingAction.findUniqueOrThrow({ where: { id: legacyAction } })).status,
    'pending',
  );
  console.log('PostgreSQL QA: additive migration preserves old usage and pending actions.');

  const secrets = await db.integrationSecret.findMany({ orderBy: { key: 'asc' } });
  assert.equal((await settings.get()).model, 'deepseek-v4-flash');
  assert.equal((await settings.getRuntime()).apiKey, 'deepseek-qa-only');
  await settings.update({ provider: 'gemini', model: 'gemini-3.5-flash-lite', apiKey: '  ' });
  assert.equal((await settings.getRuntime()).apiKey, 'gemini-qa-only');
  await settings.update({ provider: 'deepseek' });
  assert.equal((await settings.get()).model, 'deepseek-v4-flash');
  assert.deepEqual(await db.integrationSecret.findMany({ orderBy: { key: 'asc' } }), secrets);
  console.log(
    'PostgreSQL QA: legacy settings, separate encrypted keys and blank-key preservation.',
  );

  const parallel = (operation) =>
    Promise.allSettled(Array.from({ length: 8 }, (_, i) => operation(i)));
  const accepted = (results) => results.filter((r) => r.status === 'fulfilled');
  const rejected = (results, code) => {
    for (const result of results.filter((r) => r.status === 'rejected'))
      assert.equal(result.reason.message, code);
  };
  await cleanUsage();
  let results = await parallel(() => reserveAiRequest(db, 'ai-qa-tokens', config));
  assert.equal(accepted(results).length, 1);
  rejected(results, 'ai.err.tokenReserve');
  assert.equal(await db.aiUsageLog.count(), 1);
  console.log('PostgreSQL QA: 8 concurrent requests, one token reservation.');

  await cleanUsage();
  results = await parallel((i) =>
    reserveAiRequest(db, `ai-qa-daily-${i}`, { ...config, dailyBudgetUsd: 0.1 }),
  );
  assert.equal(accepted(results).length, 1);
  rejected(results, 'ai.err.budgetLimit');
  assert.ok((await aiBudgetUsage(db)).dailyCostUsd <= 0.1);

  await cleanUsage();
  await reserveAiRequest(db, 'ai-qa-gemini', {
    ...config,
    provider: 'gemini',
    model: 'gemini-3.8-flash',
    dailyBudgetUsd: 0.4,
  });
  results = await parallel((i) =>
    reserveAiRequest(db, `ai-qa-mixed-${i}`, { ...config, dailyBudgetUsd: 0.4 }),
  );
  assert.equal(accepted(results).length, 2);
  rejected(results, 'ai.err.budgetLimit');
  assert.ok((await aiBudgetUsage(db)).dailyCostUsd <= 0.4);

  await cleanUsage();
  results = await parallel((i) =>
    reserveAiRequest(db, `ai-qa-month-${i}`, { ...config, monthlyBudgetUsd: 0.1 }),
  );
  assert.equal(accepted(results).length, 1);
  rejected(results, 'ai.err.budgetLimit');
  console.log('PostgreSQL QA: shared daily/monthly ceilings, including both providers.');

  await cleanUsage();
  results = await parallel(() =>
    reserveAiRequest(db, 'ai-qa-rate', { ...config, requestsPerHour: 2, tokensPerDay: 1000000 }),
  );
  assert.equal(accepted(results).length, 2);
  rejected(results, 'ai.err.rateLimit');
  console.log('PostgreSQL QA: per-user rate includes in-flight requests.');

  const actionId = randomUUID();
  let executions = 0;
  const tools = {
    summarize: () => 'QA transfer',
    pageContext: async () => undefined,
    toolsFor: () => [],
    contractPrompt: () => 'QA only.',
    execute: async (user, tool, args, id) => {
      assert.equal(user, legacyUser);
      assert.equal(id, actionId);
      assert.equal(tool, 'change_player_balance');
      assert.deepEqual(args, { player: 'Steve' });
      executions++;
      await new Promise((resolve) => setTimeout(resolve, 50));
      return { content: 'Synthetic operation only', untrusted: false };
    },
  };
  const permissions = { getEffectivePermissions: async () => ({ permissions: new Set() }) };
  const service = new AiService(db, settings, tools, {}, permissions, new I18nService());
  await db.aiPendingAction.create({
    data: {
      id: actionId,
      userId: legacyUser,
      tool: 'change_player_balance',
      args: { player: 'Steve' },
    },
  });
  await assert.rejects(service.resolve('ai-qa-other', actionId, true), /ai.err.actionNotYours/);
  results = await parallel(() => service.resolve(legacyUser, actionId, true));
  assert.equal(accepted(results).length, 1);
  rejected(results, 'ai.err.actionDecided');
  assert.equal(executions, 1);
  assert.equal((await service.action(legacyUser, actionId)).status, 'approved');
  console.log('PostgreSQL QA: 8 simultaneous approvals, one execution and stable action UUID.');

  await cleanUsage();
  const provider = {
    chat: async () => ({
      content: 'QA',
      toolCalls: [],
      promptTokens: 16,
      completionTokens: 8,
      usageKnown: true,
      finishReason: 'stop',
    }),
  };
  const chat = new AiService(db, settings, tools, provider, permissions, new I18nService());
  const events = [];
  await chat.chat('ai-qa-settlement', [{ role: 'user', content: 'QA' }], (event) =>
    events.push(event),
  );
  assert.equal(events.at(-1).type, 'done');
  let usage = await db.aiUsageLog.findFirstOrThrow({ where: { userId: 'ai-qa-settlement' } });
  assert.equal(usage.status, 'complete');
  assert.equal(usage.reservedTokens, 0);
  assert.equal(usage.reservedCostUsdMicros, 0n);
  assert.equal(usage.costUsdMicros, 15n);

  const abort = new AbortController();
  const cancelledProvider = {
    chat: async (_config, _messages, _tools, _handlers, signal) =>
      new Promise((_resolve, reject) => {
        signal.addEventListener(
          'abort',
          () => reject(new AiProviderError('ai.err.cancelled', true)),
          { once: true },
        );
        abort.abort();
      }),
  };
  const cancelled = new AiService(
    db,
    settings,
    tools,
    cancelledProvider,
    permissions,
    new I18nService(),
  );
  await cancelled.chat(
    'ai-qa-cancelled',
    [{ role: 'user', content: 'QA' }],
    () => {},
    'en',
    undefined,
    abort.signal,
  );
  usage = await db.aiUsageLog.findFirstOrThrow({ where: { userId: 'ai-qa-cancelled' } });
  assert.equal(usage.status, 'complete');
  assert.equal(usage.error, 'ai.err.cancelled');
  assert.equal(usage.reservedTokens, 0);
  assert.equal(usage.reservedCostUsdMicros, 0n);
  assert.ok(usage.costUsdMicros > 0n);
  console.log(
    'PostgreSQL QA: settlement releases reserves; interrupted unknown usage stays charged.',
  );
}

(async () => {
  const actual = await db.$queryRaw`SELECT current_database() AS name`;
  assert.equal(actual[0].name, database);
  if (process.argv[2] === 'seed-legacy') await seedLegacy();
  else if (process.argv[2] === 'check') await check();
  else throw new Error('Use seed-legacy before the AI migration, or check afterwards.');
})()
  .catch((error) => {
    console.error(`PostgreSQL QA failed: ${error.message}`);
    process.exitCode = 1;
  })
  .finally(() => db.$disconnect());
