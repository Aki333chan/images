process.env.NODE_ENV = 'test';

import type { AiStreamEvent } from '@aurum/shared';
import { AiService } from './ai.service';
import type { AiSettingsService } from './ai-settings.service';
import type { AiToolsService } from './ai-tools.service';
import { AiProviderError, type AiProviderClient, type AiResult } from './ai-provider.client';
import type { PermissionsService } from '../rbac/permissions.service';
import { I18nService } from '../i18n/i18n.service';
import type { PrismaService } from '../prisma/prisma.service';

/**
 * Поведение ассистента вокруг разрушительных действий и лимитов.
 *
 * Главное свойство, которое здесь закрепляется: вызов разрушительного
 * инструмента, пришедший от модели, НЕ выполняется — он превращается в
 * предложение человеку. Это структурная защита: она работает независимо от
 * того, что модели написали в контексте, в том числе если её пытались
 * переубедить текстом, введённым игроком.
 */
function setup(
  options: {
    rounds?: Partial<AiResult>[];
    usageRows?: { requests: number; tokens: number };
    settings?: Partial<{ requestsPerHour: number; tokensPerDay: number }>;
    enabled?: boolean;
    onExecute?: (name: string) => void;
  } = {},
) {
  const rounds = options.rounds ?? [{ content: 'Готово', toolCalls: [] }];
  const executed: string[] = [];
  const proposed: { tool: string; args: unknown; fromUntrustedInput: boolean }[] = [];

  let round = 0;
  const deepseek = {
    chat: (_c: unknown, _m: unknown, _t: unknown, handlers: { onDelta: (t: string) => void }) => {
      const current = rounds[Math.min(round, rounds.length - 1)]!;
      round++;
      if (current.content) handlers.onDelta(current.content);
      return Promise.resolve({
        content: current.content ?? '',
        toolCalls: current.toolCalls ?? [],
        promptTokens: current.promptTokens ?? 10,
        completionTokens: current.completionTokens ?? 5,
        finishReason: null,
      });
    },
  } as unknown as AiProviderClient;

  const prisma = {
    aiUsageLog: {
      count: () => Promise.resolve(options.usageRows?.requests ?? 0),
      aggregate: () =>
        Promise.resolve({
          _sum: {
            promptTokens: options.usageRows?.tokens ?? 0,
            completionTokens: 0,
            reservedTokens: 0,
            costUsdMicros: 0n,
            reservedCostUsdMicros: 0n,
          },
        }),
      create: jest.fn().mockResolvedValue({ id: 1n }),
      update: jest.fn().mockResolvedValue({}),
    },
    aiPendingAction: {
      create: ({
        data,
      }: {
        data: { tool: string; args: unknown; fromUntrustedInput: boolean };
      }) => {
        proposed.push(data);
        return Promise.resolve({ id: `act-${proposed.length}`, ...data });
      },
    },
  } as unknown as PrismaService;
  prisma.$transaction = ((run: (tx: unknown) => unknown) =>
    run({ ...prisma, $executeRaw: () => Promise.resolve(0) })) as never;

  const tools = {
    toolsFor: () =>
      [...new Set(rounds.flatMap((r) => r.toolCalls?.map((c) => c.function.name) ?? []))].map(
        (name) => ({ type: 'function', function: { name, description: '', parameters: {} } }),
      ),
    pageContext: () => Promise.resolve(undefined),
    contractPrompt: () => 'технические правила',
    find: (name: string) => ({
      name,
      kind: name.startsWith('list_') ? 'safe' : 'destructive',
    }),
    summarize: (name: string) => `сводка ${name}`,
    // Приведение ника и сервера к точным значениям: в чате оно идёт перед
    // карточкой, чтобы человек подтверждал настоящее имя, а не набранный
    // кусок. Здесь достаточно тождества.
    normalizeArgs: (_u: string, _n: string, args: Record<string, unknown>) => Promise.resolve(args),
    prepareAction: (_u: string, _n: string, args: Record<string, unknown>) => Promise.resolve(args),
    execute: (_u: string, name: string) => {
      executed.push(name);
      options.onExecute?.(name);
      return Promise.resolve({ content: 'результат', untrusted: name === 'list_players' });
    },
  } as unknown as AiToolsService;

  const service = new AiService(
    prisma,
    {
      get: () =>
        Promise.resolve({
          enabled: true,
          hasApiKey: true,
          model: 'deepseek-v4-flash',
          provider: 'deepseek',
          providerKeys: { deepseek: true, gemini: false },
          maxInputTokens: 32768,
          maxOutputTokens: 4096,
          dailyBudgetUsd: 1,
          monthlyBudgetUsd: 10,
          systemPrompt: 'ты ассистент',
          requestsPerHour: options.settings?.requestsPerHour ?? 30,
          tokensPerDay: options.settings?.tokensPerDay ?? 200_000,
        }),
      getRuntime: () =>
        Promise.resolve(
          options.enabled === false
            ? null
            : {
                apiKey: 'sk-test',
                model: 'deepseek-v4-flash',
                provider: 'deepseek',
                maxInputTokens: 32768,
                maxOutputTokens: 4096,
                dailyBudgetUsd: 1,
                monthlyBudgetUsd: 10,
                systemPrompt: 'ты ассистент',
                requestsPerHour: options.settings?.requestsPerHour ?? 30,
                tokensPerDay: options.settings?.tokensPerDay ?? 200_000,
              },
        ),
    } as unknown as AiSettingsService,
    tools,
    deepseek,
    {
      getEffectivePermissions: () =>
        Promise.resolve({ permissions: new Set<string>(), allowedServerIds: null }),
    } as unknown as PermissionsService,
    // Настоящий: тексты лимитов и отказов ассистента собираются им, и
    // подменять его заглушкой значило бы проверять не то, что показывается.
    new I18nService(),
  );

  return { service, executed, proposed, prisma, deepseek, tools };
}

const toolCall = (name: string, args: object) => ({
  id: `call-${name}`,
  type: 'function' as const,
  function: { name, arguments: JSON.stringify(args) },
});

describe('atomic confirmation and recovery', () => {
  const actionId = '66da2285-0983-4ce6-bc6a-eed51b0e21ea';
  function actions() {
    const fixture = setup();
    const row = {
      id: actionId,
      userId: 'user-1',
      tool: 'change_player_balance',
      args: { player: 'Steve' },
      createdAt: new Date(),
      status: 'pending',
      fromUntrustedInput: false,
      result: null as string | null,
    };
    fixture.prisma.aiPendingAction.findUnique = jest
      .fn()
      .mockImplementation(() => Promise.resolve({ ...row })) as never;
    fixture.prisma.aiPendingAction.updateMany = jest.fn().mockImplementation(({ where, data }) => {
      if (row.status !== where.status) return Promise.resolve({ count: 0 });
      Object.assign(row, data);
      return Promise.resolve({ count: 1 });
    }) as never;
    fixture.prisma.aiPendingAction.update = jest.fn().mockImplementation(({ data }) => {
      Object.assign(row, data);
      return Promise.resolve(row);
    }) as never;
    const execute = jest.spyOn(fixture.tools, 'execute').mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
      return { content: 'paid', untrusted: false };
    });
    return { ...fixture, row, execute };
  }
  it('two approvals execute exactly once and keep the confirmation UUID', async () => {
    const { service, execute, row } = actions();
    const results = await Promise.allSettled([
      service.resolve('user-1', actionId, true),
      service.resolve('user-1', actionId, true),
    ]);
    expect(results.map((r) => r.status).sort()).toEqual(['fulfilled', 'rejected']);
    expect(execute).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledWith(
      'user-1',
      'change_player_balance',
      { player: 'Steve' },
      actionId,
    );
    expect(row.status).toBe('approved');
  });
  it('cannot approve another user’s action', async () => {
    const { service, execute } = actions();
    await expect(service.resolve('user-2', actionId, true)).rejects.toThrow(
      'ai.err.actionNotYours',
    );
    await expect(service.action('user-2', actionId)).rejects.toThrow('ai.err.actionNotYours');
    expect(execute).not.toHaveBeenCalled();
  });
  it('rejection and expiration never execute a tool', async () => {
    const rejected = actions();
    expect((await rejected.service.resolve('user-1', actionId, false)).status).toBe('rejected');
    expect(rejected.execute).not.toHaveBeenCalled();
    const expired = actions();
    expired.row.createdAt = new Date(Date.now() - 20 * 60000);
    expect((await expired.service.resolve('user-1', actionId, true)).status).toBe('expired');
    expect(expired.execute).not.toHaveBeenCalled();
  });
  it('read recovery never re-executes an action or exposes Core confirmation tokens', async () => {
    const { service, row, execute } = actions();
    row.args = {
      ...row.args,
      _previewToken: 'private-core-token',
      _preview: { before: 1, after: 2 },
    } as never;
    const result = await service.action('user-1', actionId);
    expect(JSON.stringify(result)).not.toContain('private-core-token');
    expect(result.args.preview).toEqual({ before: 1, after: 2 });
    expect(execute).not.toHaveBeenCalled();
  });
});

describe('billing and cancellation', () => {
  it('settles the reserve before emitting done', async () => {
    const { service, prisma } = setup();
    await service.chat('user-1', [{ role: 'user', content: 'hello' }], (event) => {
      if (event.type === 'done') expect(prisma.aiUsageLog.update).toHaveBeenCalled();
    });
    expect(prisma.aiUsageLog.update).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          promptTokens: 10,
          completionTokens: 5,
          reservedTokens: 0,
          reservedCostUsdMicros: 0n,
        }),
      }),
    );
  });
  it('provider refusal is not charged as a full round', async () => {
    const { service, prisma, deepseek } = setup();
    jest
      .spyOn(deepseek, 'chat')
      .mockRejectedValue(new AiProviderError('ai.err.providerQuota', false));
    await run(service);
    expect(prisma.aiUsageLog.update).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          promptTokens: 0,
          completionTokens: 0,
          costUsdMicros: 0n,
        }),
      }),
    );
  });
  it('an uncertain interrupted call keeps one round’s conservative ceiling', async () => {
    const { service, prisma, deepseek } = setup();
    jest
      .spyOn(deepseek, 'chat')
      .mockRejectedValue(new AiProviderError('ai.err.providerUnavailable', true));
    await run(service);
    expect(prisma.aiUsageLog.update).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          promptTokens: 32768,
          completionTokens: 4096,
        }),
      }),
    );
  });
  it('an already cancelled request never reserves budget or contacts a provider', async () => {
    const { service, prisma, deepseek } = setup();
    const provider = jest.spyOn(deepseek, 'chat');
    const abort = new AbortController();
    abort.abort();
    await service.chat(
      'user-1',
      [{ role: 'user', content: 'hello' }],
      () => {},
      'en',
      undefined,
      abort.signal,
    );
    expect(prisma.aiUsageLog.create).not.toHaveBeenCalled();
    expect(provider).not.toHaveBeenCalled();
  });
});

async function run(service: AiService, text = 'привет'): Promise<AiStreamEvent[]> {
  const events: AiStreamEvent[] = [];
  await service.chat('user-1', [{ role: 'user', content: text }], (e) => events.push(e));
  return events;
}

describe('разрушительные действия', () => {
  it('вызов разрушительного инструмента НЕ выполняется, а становится предложением', async () => {
    const { service, executed, proposed } = setup({
      rounds: [
        {
          content: '',
          toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Griefer99' })],
        },
        { content: 'Жду вашего решения', toolCalls: [] },
      ],
    });

    const events = await run(service);

    // Ничего не выполнено — только предложено.
    expect(executed).toEqual([]);
    expect(proposed).toHaveLength(1);
    expect(proposed[0]!.tool).toBe('ban_player');

    const action = events.find((e) => e.type === 'action');
    expect(action).toBeDefined();
  });

  it('в карточке видно, что именно предлагается', async () => {
    const { service } = setup({
      rounds: [
        {
          content: '',
          toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Griefer99' })],
        },
        { content: 'ок', toolCalls: [] },
      ],
    });
    const events = await run(service);
    const action = events.find((e) => e.type === 'action');
    expect(action).toMatchObject({
      type: 'action',
      action: { tool: 'ban_player', status: 'pending', args: { player: 'Griefer99' } },
    });
  });

  it('безопасный инструмент выполняется сразу', async () => {
    const { service, executed, proposed } = setup({
      rounds: [
        { content: '', toolCalls: [toolCall('list_players', { serverId: 's1' })] },
        { content: 'Онлайн двое', toolCalls: [] },
      ],
    });
    await run(service);
    expect(executed).toEqual(['list_players']);
    expect(proposed).toEqual([]);
  });

  // Сценарий prompt injection: сперва модель читает тикеты (недоверенный
  // ввод), потом «решает» кого-то забанить. Забанить она всё равно не может,
  // но предложение помечается — интерфейс предупредит человека отдельно.
  it('предложение после чтения игровых данных помечается как основанное на них', async () => {
    const { service, proposed } = setup({
      rounds: [
        { content: '', toolCalls: [toolCall('list_players', { serverId: 's1' })] },
        { content: '', toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Alex' })] },
        { content: 'Предложил бан', toolCalls: [] },
      ],
    });

    await run(service);

    expect(proposed).toHaveLength(1);
    expect(proposed[0]!.fromUntrustedInput).toBe(true);
  });

  it('предложение без чтения игровых данных такой пометки не получает', async () => {
    const { service, proposed } = setup({
      rounds: [
        { content: '', toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Alex' })] },
        { content: 'ок', toolCalls: [] },
      ],
    });
    await run(service);
    expect(proposed[0]!.fromUntrustedInput).toBe(false);
  });

  it('модель не зацикливается на инструментах бесконечно', async () => {
    // Модель, которая всегда просит инструмент, должна упереться в потолок,
    // а не тратить деньги до бесконечности.
    const { service, executed } = setup({
      rounds: [{ content: '', toolCalls: [toolCall('list_players', { serverId: 's1' })] }],
    });
    await run(service);
    expect(executed.length).toBeLessThanOrEqual(5);
    expect(executed.length).toBeGreaterThan(0);
  });
});

describe('лимиты', () => {
  it('при исчерпании лимита обращений к модели не ходим вовсе', async () => {
    const { service, executed } = setup({
      settings: { requestsPerHour: 5 },
      usageRows: { requests: 5, tokens: 0 },
    });
    const events = await run(service);

    expect(events).toEqual([
      { type: 'error', message: expect.stringContaining('лимит обращений') },
    ]);
    expect(executed).toEqual([]);
  });

  it('дневной лимит токенов тоже проверяется до обращения', async () => {
    const { service } = setup({
      settings: { tokensPerDay: 1000 },
      usageRows: { requests: 0, tokens: 1000 },
    });
    const events = await run(service);
    expect(events[0]).toMatchObject({ type: 'error', message: expect.stringContaining('лимит') });
  });

  it('выключенный ассистент честно об этом говорит', async () => {
    const { service } = setup({ enabled: false });
    const events = await run(service);
    expect(events[0]).toMatchObject({
      type: 'error',
      message: expect.stringContaining('выключен'),
    });
  });

  it('расход токенов отдаётся в поток — по нему считается стоимость', async () => {
    const { service } = setup();
    const events = await run(service);
    expect(events.find((e) => e.type === 'usage')).toMatchObject({
      type: 'usage',
      promptTokens: 10,
      completionTokens: 5,
    });
  });

  it('поток завершается событием done', async () => {
    const { service } = setup();
    const events = await run(service);
    expect(events[events.length - 1]).toEqual({ type: 'done' });
  });
});
