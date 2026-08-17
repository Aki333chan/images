import { BadRequestException, ForbiddenException, Injectable, Logger } from '@nestjs/common';
import type {
  AiChatMessage,
  AiPendingActionDto,
  AiStreamEvent,
  AiUsageDto,
} from '@aurum/shared';
import { PrismaService } from '../prisma/prisma.service';
import { PermissionsService } from '../rbac/permissions.service';
import { AiSettingsService } from './ai-settings.service';
import { AiToolsService } from './ai-tools.service';
import { DeepseekClient, type DeepseekMessage } from './deepseek.client';

/**
 * Сколько раз подряд модель может сходить за инструментами внутри одного
 * обращения. Ограничение против зацикливания: без него модель, не получив
 * ожидаемого, может ходить за одним и тем же, пока не кончатся деньги.
 */
const MAX_TOOL_ROUNDS = 5;

/** Предложение старше этого времени исполнять нельзя — обстановка изменилась. */
const ACTION_TTL_MINUTES = 15;

/** Длина одного сообщения и всей истории — защита от «простыни» в контексте. */
const MAX_MESSAGE_LENGTH = 4000;
const MAX_HISTORY_MESSAGES = 20;

@Injectable()
export class AiService {
  private readonly logger = new Logger(AiService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly settings: AiSettingsService,
    private readonly tools: AiToolsService,
    private readonly deepseek: DeepseekClient,
    private readonly permissions: PermissionsService,
  ) {}

  /** Расход и остаток лимита — показывается в интерфейсе. */
  async usage(userId: string): Promise<AiUsageDto> {
    const settings = await this.settings.get();
    const { requests, tokens } = await this.spent(userId);
    return {
      requestsLastHour: requests,
      requestsPerHour: settings.requestsPerHour,
      tokensToday: tokens,
      tokensPerDay: settings.tokensPerDay,
    };
  }

  private async spent(userId: string): Promise<{ requests: number; tokens: number }> {
    const hourAgo = new Date(Date.now() - 3600_000);
    const dayStart = new Date();
    dayStart.setHours(0, 0, 0, 0);

    const [requests, tokens] = await Promise.all([
      this.prisma.aiUsageLog.count({ where: { userId, createdAt: { gte: hourAgo } } }),
      this.prisma.aiUsageLog.aggregate({
        where: { userId, createdAt: { gte: dayStart } },
        _sum: { promptTokens: true, completionTokens: true },
      }),
    ]);
    return {
      requests,
      tokens: (tokens._sum.promptTokens ?? 0) + (tokens._sum.completionTokens ?? 0),
    };
  }

  /**
   * Обращение к ассистенту. События отдаются по мере поступления —
   * контроллер превращает их в SSE.
   */
  async chat(
    userId: string,
    history: AiChatMessage[],
    emit: (event: AiStreamEvent) => void,
  ): Promise<void> {
    const config = await this.settings.getRuntime();
    if (!config) {
      emit({
        type: 'error',
        message: 'Ассистент выключен или не настроен — ГМ может включить его в настройках панели.',
      });
      return;
    }

    // Лимиты считаем ДО обращения: смысл лимита в том, чтобы не потратить.
    const spent = await this.spent(userId);
    if (spent.requests >= config.requestsPerHour) {
      emit({
        type: 'error',
        message: `Достигнут лимит обращений: ${config.requestsPerHour} в час. Попробуйте позже.`,
      });
      return;
    }
    if (spent.tokens >= config.tokensPerDay) {
      emit({
        type: 'error',
        message: `Достигнут дневной лимит расхода (${config.tokensPerDay} токенов). Лимит сбросится завтра.`,
      });
      return;
    }

    const permissions = await this.permissions.getEffectivePermissions(userId);
    const tools = this.tools.toolsFor(permissions);

    const messages: DeepseekMessage[] = [
      { role: 'system', content: config.systemPrompt },
      ...history
        .slice(-MAX_HISTORY_MESSAGES)
        .map((m) => ({ role: m.role, content: m.content.slice(0, MAX_MESSAGE_LENGTH) })),
    ];

    let promptTokens = 0;
    let completionTokens = 0;
    let toolCallCount = 0;
    /** Попадали ли в контекст данные, введённые игроками. */
    let sawUntrusted = false;

    try {
      for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
        const result = await this.deepseek.chat(config, messages, tools, {
          onDelta: (text) => emit({ type: 'delta', text }),
        });
        promptTokens += result.promptTokens;
        completionTokens += result.completionTokens;

        if (result.toolCalls.length === 0) break;
        toolCallCount += result.toolCalls.length;

        messages.push({
          role: 'assistant',
          content: result.content || null,
          tool_calls: result.toolCalls,
        });

        for (const call of result.toolCalls) {
          const args = parseArguments(call.function.arguments);
          const tool = this.tools.find(call.function.name);

          if (!tool) {
            messages.push({
              role: 'tool',
              tool_call_id: call.id,
              content: `Инструмента ${call.function.name} не существует`,
            });
            continue;
          }

          // РАЗРУШИТЕЛЬНОЕ: модель не выполняет, а предлагает. Это работает
          // независимо от того, что ей написали в контексте, — в том числе
          // если её пытались переубедить текстом из игры.
          if (tool.kind === 'destructive') {
            const action = await this.propose(userId, call.function.name, args, sawUntrusted);
            emit({ type: 'action', action });
            messages.push({
              role: 'tool',
              tool_call_id: call.id,
              content:
                'Действие НЕ выполнено. Оно показано человеку карточкой на подтверждение. ' +
                'Скажи собеседнику, что ждёшь его решения, и не пытайся выполнить это иначе.',
            });
            continue;
          }

          // БЕЗОПАСНОЕ: выполняем сразу.
          try {
            const output = await this.tools.execute(userId, call.function.name, args);
            if (output.untrusted) sawUntrusted = true;
            emit({
              type: 'tool',
              name: call.function.name,
              summary: this.tools.summarize(call.function.name, args),
            });
            messages.push({ role: 'tool', tool_call_id: call.id, content: output.content });
          } catch (e) {
            messages.push({
              role: 'tool',
              tool_call_id: call.id,
              content: `Не удалось: ${(e as Error).message}`,
            });
          }
        }
      }

      emit({ type: 'usage', promptTokens, completionTokens });
      emit({ type: 'done' });
      await this.recordUsage(userId, config.model, promptTokens, completionTokens, toolCallCount, null);
    } catch (e) {
      const message = (e as Error).message;
      this.logger.warn(`Обращение к ассистенту не удалось: ${message}`);
      emit({ type: 'error', message });
      // Неудачное обращение тоже расходует лимит запросов: иначе поломанный
      // ключ можно было бы долбить бесконечно.
      await this.recordUsage(userId, config.model, promptTokens, completionTokens, toolCallCount, message);
    }
  }

  private async recordUsage(
    userId: string,
    model: string,
    promptTokens: number,
    completionTokens: number,
    toolCalls: number,
    error: string | null,
  ): Promise<void> {
    await this.prisma.aiUsageLog
      .create({
        data: {
          userId,
          model,
          promptTokens,
          completionTokens,
          toolCalls,
          error: error?.slice(0, 500) ?? null,
        },
      })
      .catch(() => undefined);
  }

  /** Сохранить предложение. Аргументы хранятся на сервере — см. модель. */
  private async propose(
    userId: string,
    tool: string,
    args: Record<string, unknown>,
    fromUntrustedInput: boolean,
  ): Promise<AiPendingActionDto> {
    const created = await this.prisma.aiPendingAction.create({
      data: { userId, tool, args: args as object, fromUntrustedInput },
    });
    return {
      id: created.id,
      tool,
      summary: this.tools.summarize(tool, args),
      args,
      fromUntrustedInput,
      status: 'pending',
    };
  }

  /**
   * Решение человека по предложенному действию.
   *
   * Исполняется ровно то, что записано на сервере: аргументы с клиента не
   * принимаются, иначе подтверждение ничего бы не гарантировало.
   */
  async resolve(
    userId: string,
    actionId: string,
    approve: boolean,
  ): Promise<AiPendingActionDto> {
    const action = await this.prisma.aiPendingAction.findUnique({ where: { id: actionId } });
    if (!action) throw new BadRequestException('Предложение не найдено');
    // Подтвердить может только тот, кто вёл диалог: карточка адресована ему.
    if (action.userId !== userId) {
      throw new ForbiddenException('Это предложение адресовано другому сотруднику');
    }
    if (action.status !== 'pending') {
      throw new BadRequestException('По этому предложению решение уже принято');
    }

    const args = (action.args ?? {}) as Record<string, unknown>;
    const base = {
      id: action.id,
      tool: action.tool,
      summary: this.tools.summarize(action.tool, args),
      args,
      fromUntrustedInput: action.fromUntrustedInput,
    };

    const ageMinutes = (Date.now() - action.createdAt.getTime()) / 60_000;
    if (ageMinutes > ACTION_TTL_MINUTES) {
      await this.prisma.aiPendingAction.update({
        where: { id: action.id },
        data: { status: 'expired', resolvedAt: new Date() },
      });
      return { ...base, status: 'expired', result: 'Предложение устарело — попросите ассистента заново' };
    }

    if (!approve) {
      await this.prisma.aiPendingAction.update({
        where: { id: action.id },
        data: { status: 'rejected', resolvedAt: new Date() },
      });
      return { ...base, status: 'rejected' };
    }

    try {
      const output = await this.tools.execute(userId, action.tool, args);
      await this.prisma.aiPendingAction.update({
        where: { id: action.id },
        data: { status: 'approved', result: output.content.slice(0, 1000), resolvedAt: new Date() },
      });
      return { ...base, status: 'approved', result: output.content };
    } catch (e) {
      const message = (e as Error).message;
      await this.prisma.aiPendingAction.update({
        where: { id: action.id },
        data: { status: 'failed', result: message.slice(0, 1000), resolvedAt: new Date() },
      });
      return { ...base, status: 'failed', result: message };
    }
  }
}

/** Аргументы приходят строкой JSON и могут быть битыми — это не повод падать. */
function parseArguments(raw: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(raw || '{}');
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
