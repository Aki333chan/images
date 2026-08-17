process.env.NODE_ENV = 'test';

import type { AiStreamEvent } from '@aurum/shared';
import { AiService } from './ai.service';
import type { AiSettingsService } from './ai-settings.service';
import type { AiToolsService } from './ai-tools.service';
import type { DeepseekClient, DeepseekResult } from './deepseek.client';
import type { PermissionsService } from '../rbac/permissions.service';
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
function setup(options: {
  rounds?: Partial<DeepseekResult>[];
  usageRows?: { requests: number; tokens: number };
  settings?: Partial<{ requestsPerHour: number; tokensPerDay: number }>;
  enabled?: boolean;
  onExecute?: (name: string) => void;
} = {}) {
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
  } as unknown as DeepseekClient;

  const prisma = {
    aiUsageLog: {
      count: () => Promise.resolve(options.usageRows?.requests ?? 0),
      aggregate: () =>
        Promise.resolve({
          _sum: { promptTokens: options.usageRows?.tokens ?? 0, completionTokens: 0 },
        }),
      create: () => Promise.resolve({}),
    },
    aiPendingAction: {
      create: ({ data }: { data: { tool: string; args: unknown; fromUntrustedInput: boolean } }) => {
        proposed.push(data);
        return Promise.resolve({ id: `act-${proposed.length}`, ...data });
      },
    },
  } as unknown as PrismaService;

  const tools = {
    toolsFor: () => [],
    find: (name: string) => ({
      name,
      kind: name.startsWith('list_') ? 'safe' : 'destructive',
    }),
    summarize: (name: string) => `сводка ${name}`,
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
  );

  return { service, executed, proposed };
}

const toolCall = (name: string, args: object) => ({
  id: `call-${name}`,
  type: 'function' as const,
  function: { name, arguments: JSON.stringify(args) },
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
        { content: '', toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Griefer99' })] },
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
        { content: '', toolCalls: [toolCall('ban_player', { serverId: 's1', player: 'Griefer99' })] },
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
    expect(events[0]).toMatchObject({ type: 'error', message: expect.stringContaining('выключен') });
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
