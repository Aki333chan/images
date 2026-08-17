import { Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import { env } from '../config/env';

/**
 * Клиент DeepSeek.
 *
 * API совместим с OpenAI Chat Completions: тот же формат сообщений, тот же
 * блок tools с type:"function", те же tool_calls в ответе и та же
 * потоковая отдача через SSE. Поэтому отдельного SDK не нужно — достаточно
 * одного HTTP-вызова, и это одна зависимость вместо целого пакета.
 *
 * Имена моделей проверены на август 2026: deepseek-v4-flash и
 * deepseek-v4-pro. Прежние deepseek-chat и deepseek-reasoner отключены
 * 24.07.2026. Имя модели не захардкожено — оно приходит из настроек, чтобы
 * смена модели не требовала правки кода.
 */

export interface DeepseekMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  /** Для role='assistant' с вызовами инструментов. */
  tool_calls?: DeepseekToolCall[];
  /** Для role='tool' — id вызова, на который отвечаем. */
  tool_call_id?: string;
}

export interface DeepseekToolCall {
  id: string;
  type: 'function';
  function: { name: string; arguments: string };
}

export interface DeepseekTool {
  type: 'function';
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

export interface DeepseekStreamHandlers {
  /** Очередной кусок текста ответа. */
  onDelta: (text: string) => void;
}

export interface DeepseekResult {
  content: string;
  toolCalls: DeepseekToolCall[];
  promptTokens: number;
  completionTokens: number;
  /** Почему модель остановилась: tool_calls означает «жду результатов». */
  finishReason: string | null;
}

@Injectable()
export class DeepseekClient {
  private readonly logger = new Logger(DeepseekClient.name);

  /**
   * Один обмен с моделью в потоковом режиме.
   *
   * Текст отдаётся кусками через onDelta, а вызовы инструментов собираются
   * целиком и возвращаются в конце: выполнять инструмент по половине
   * аргументов нельзя, а приходят они по буквам.
   */
  async chat(
    config: { apiKey: string; model: string },
    messages: DeepseekMessage[],
    tools: DeepseekTool[],
    handlers: DeepseekStreamHandlers,
  ): Promise<DeepseekResult> {
    const response = await request(`${env.DEEPSEEK_BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${config.apiKey}`,
        'content-type': 'application/json',
        accept: 'text/event-stream',
      },
      body: JSON.stringify({
        model: config.model,
        messages,
        ...(tools.length > 0 ? { tools, tool_choice: 'auto' } : {}),
        stream: true,
        // Просим сервер прислать расход в последнем чанке: иначе токены
        // при стриминге посчитать нечем, а без них не работают лимиты.
        stream_options: { include_usage: true },
      }),
      headersTimeout: 30_000,
      bodyTimeout: 300_000,
    });

    if (response.statusCode >= 400) {
      const text = await response.body.text();
      // Ключ в сообщении не повторяем — только код и краткая причина.
      this.logger.warn(`DeepSeek ответил ${response.statusCode}`);
      throw new Error(describeApiError(response.statusCode, text));
    }

    const state: DeepseekResult = {
      content: '',
      toolCalls: [],
      promptTokens: 0,
      completionTokens: 0,
      finishReason: null,
    };

    let buffer = '';
    for await (const chunk of response.body) {
      buffer += chunk.toString('utf8');
      // События SSE разделены пустой строкой; последний кусок может быть
      // неполным — оставляем его в буфере до следующей порции.
      let separator = buffer.indexOf('\n\n');
      while (separator !== -1) {
        const rawEvent = buffer.slice(0, separator);
        buffer = buffer.slice(separator + 2);
        this.consumeEvent(rawEvent, state, handlers);
        separator = buffer.indexOf('\n\n');
      }
    }
    if (buffer.trim()) this.consumeEvent(buffer, state, handlers);

    return state;
  }

  private consumeEvent(
    rawEvent: string,
    state: DeepseekResult,
    handlers: DeepseekStreamHandlers,
  ): void {
    for (const line of rawEvent.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === '[DONE]') continue;

      let parsed: StreamChunk;
      try {
        parsed = JSON.parse(payload) as StreamChunk;
      } catch {
        // Битый чанк не повод ронять весь ответ.
        continue;
      }

      if (parsed.usage) {
        state.promptTokens = parsed.usage.prompt_tokens ?? state.promptTokens;
        state.completionTokens = parsed.usage.completion_tokens ?? state.completionTokens;
      }

      const choice = parsed.choices?.[0];
      if (!choice) continue;
      if (choice.finish_reason) state.finishReason = choice.finish_reason;

      const delta = choice.delta;
      if (!delta) continue;

      if (typeof delta.content === 'string' && delta.content.length > 0) {
        state.content += delta.content;
        handlers.onDelta(delta.content);
      }

      // Вызовы инструментов приходят по частям: имя в первом чанке, аргументы
      // дописываются по кускам. Собираем по индексу.
      for (const part of delta.tool_calls ?? []) {
        const index = part.index ?? 0;
        const existing = state.toolCalls[index] ?? {
          id: '',
          type: 'function' as const,
          function: { name: '', arguments: '' },
        };
        state.toolCalls[index] = {
          id: part.id ?? existing.id,
          type: 'function',
          function: {
            name: part.function?.name ?? existing.function.name,
            arguments: existing.function.arguments + (part.function?.arguments ?? ''),
          },
        };
      }
    }
  }
}

interface StreamChunk {
  choices?: {
    delta?: {
      content?: string | null;
      tool_calls?: {
        index?: number;
        id?: string;
        function?: { name?: string; arguments?: string };
      }[];
    };
    finish_reason?: string | null;
  }[];
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

/** Ошибку показываем человеку словами, а не кодом. */
function describeApiError(status: number, body: string): string {
  if (status === 401) return 'DeepSeek отклонил ключ API — проверьте его в настройках ассистента';
  if (status === 402)
    return 'На счету DeepSeek закончились средства — пополните баланс в личном кабинете';
  if (status === 429) return 'DeepSeek временно ограничил запросы — попробуйте через минуту';
  if (status >= 500) return 'DeepSeek недоступен, попробуйте позже';
  const detail = safeErrorMessage(body);
  return detail ? `DeepSeek отклонил запрос: ${detail}` : `DeepSeek ответил ошибкой ${status}`;
}

function safeErrorMessage(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string } };
    return parsed.error?.message?.slice(0, 200) ?? null;
  } catch {
    return null;
  }
}
