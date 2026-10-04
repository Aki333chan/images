import { Injectable } from '@nestjs/common';
import { request } from 'undici';
import type { AiProvider } from '@aurum/shared';
import { env } from '../config/env';

export interface AiMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  tool_calls?: AiToolCall[];
  tool_call_id?: string;
  reasoning_content?: string;
  extra_content?: Record<string, unknown>;
}

export interface AiToolCall {
  id: string;
  type: 'function';
  function: { name: string; arguments: string };
  /** Gemini's thought signature must survive a tool round unchanged. */
  extra_content?: Record<string, unknown>;
}

export interface AiTool {
  type: 'function';
  function: { name: string; description: string; parameters: Record<string, unknown> };
}

export interface AiResult {
  content: string;
  reasoningContent?: string;
  extraContent?: Record<string, unknown>;
  toolCalls: AiToolCall[];
  promptTokens: number;
  completionTokens: number;
  usageKnown?: boolean;
  finishReason: string | null;
}

export class AiProviderError extends Error {
  constructor(
    message: string,
    readonly uncertain: boolean,
    readonly usage?: AiResult,
  ) {
    super(message);
  }
}

/** Same small REST transport for both providers; keys and endpoints never reach the browser. */
@Injectable()
export class AiProviderClient {
  async chat(
    config: { apiKey: string; model: string; provider: AiProvider; maxOutputTokens: number },
    messages: AiMessage[],
    tools: AiTool[],
    handlers: { onDelta: (text: string) => void },
    signal?: AbortSignal,
  ): Promise<AiResult> {
    const state: AiResult = {
      content: '',
      reasoningContent: '',
      toolCalls: [],
      promptTokens: 0,
      completionTokens: 0,
      usageKnown: false,
      finishReason: null,
    };
    const endpoint = config.provider === 'gemini' ? env.GEMINI_BASE_URL : env.DEEPSEEK_BASE_URL;
    signal?.throwIfAborted();
    try {
      const response = await request(`${endpoint.replace(/\/$/, '')}/chat/completions`, {
        method: 'POST',
        signal: AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(90_000)]),
        headers: {
          authorization: `Bearer ${config.apiKey}`,
          'content-type': 'application/json',
          accept: 'text/event-stream',
        },
        body: JSON.stringify({
          model: config.model,
          messages,
          ...(tools.length ? { tools, tool_choice: 'auto' } : {}),
          max_tokens: config.maxOutputTokens,
          reasoning_effort: config.provider === 'gemini' ? 'low' : 'none',
          // Browser history is plain text: disable DeepSeek thinking rather than invent past reasoning.
          ...(config.provider === 'deepseek' ? { thinking: { type: 'disabled' } } : {}),
          stream: true,
          stream_options: { include_usage: true },
        }),
        headersTimeout: 30_000,
        bodyTimeout: 30_000,
      });
      if (response.statusCode >= 400) {
        await response.body.dump();
        // Never display/log provider error bodies: they can echo secrets or complete prompts.
        const key =
          response.statusCode === 401 || response.statusCode === 403
            ? 'ai.err.providerAuth'
            : response.statusCode === 402
              ? 'ai.err.providerFunds'
              : response.statusCode === 429
                ? 'ai.err.providerQuota'
                : response.statusCode >= 500
                  ? 'ai.err.providerUnavailable'
                  : 'ai.err.providerRequest';
        throw new AiProviderError(key, response.statusCode >= 500, state);
      }
      const decoder = new TextDecoder();
      let buffer = '';
      let bytes = 0;
      for await (const chunk of response.body) {
        bytes += chunk.byteLength;
        if (bytes > 2_000_000) throw new AiProviderError('ai.err.providerStream', true, state);
        buffer += decoder.decode(chunk, { stream: true });
        if (buffer.length > 1_000_000)
          throw new AiProviderError('ai.err.providerStream', true, state);
        let match = /\r?\n\r?\n/.exec(buffer);
        while (match) {
          this.consumeEvent(buffer.slice(0, match.index), state, handlers);
          buffer = buffer.slice(match.index + match[0].length);
          match = /\r?\n\r?\n/.exec(buffer);
        }
      }
      buffer += decoder.decode();
      if (buffer.trim()) this.consumeEvent(buffer, state, handlers);
      if (!state.finishReason) throw new AiProviderError('ai.err.providerStream', true, state);
      state.toolCalls = state.toolCalls.filter(Boolean);
      return state;
    } catch (e) {
      if (e instanceof AiProviderError) throw e;
      throw new AiProviderError(
        signal?.aborted ? 'ai.err.cancelled' : 'ai.err.providerUnavailable',
        true,
        state,
      );
    }
  }

  private consumeEvent(
    raw: string,
    state: AiResult,
    handlers: { onDelta: (text: string) => void },
  ) {
    const payload = raw
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (!payload || payload === '[DONE]') return;
    let parsed: StreamChunk;
    try {
      parsed = JSON.parse(payload);
    } catch {
      throw new AiProviderError('ai.err.providerStream', true, state);
    }
    if (parsed.usage) {
      const { prompt_tokens: input, completion_tokens: output } = parsed.usage;
      if (
        Number.isSafeInteger(input) &&
        input! >= 0 &&
        Number.isSafeInteger(output) &&
        output! >= 0
      ) {
        state.promptTokens = input!;
        state.completionTokens = output!;
        state.usageKnown = true;
      }
    }
    const choice = parsed.choices?.[0];
    if (choice?.finish_reason) state.finishReason = choice.finish_reason;
    const delta = choice?.delta;
    if (!delta) return;
    if (delta.reasoning_content)
      state.reasoningContent = (state.reasoningContent ?? '') + delta.reasoning_content;
    if (delta.extra_content) state.extraContent = { ...state.extraContent, ...delta.extra_content };
    if (delta.content) {
      state.content += delta.content;
      handlers.onDelta(delta.content);
    }
    if (state.content.length + (state.reasoningContent?.length ?? 0) > 262_144) {
      throw new AiProviderError('ai.err.providerStream', true, state);
    }
    for (const part of delta.tool_calls ?? []) {
      const index = part.index ?? 0;
      if (!Number.isInteger(index) || index < 0 || index >= 16)
        throw new AiProviderError('ai.err.toolLimit', true, state);
      const existing = state.toolCalls[index] ?? {
        id: '',
        type: 'function',
        function: { name: '', arguments: '' },
      };
      state.toolCalls[index] = {
        id: part.id ?? existing.id,
        type: 'function',
        function: {
          name: part.function?.name ?? existing.function.name,
          arguments: existing.function.arguments + (part.function?.arguments ?? ''),
        },
        ...(existing.extra_content || part.extra_content
          ? { extra_content: { ...existing.extra_content, ...part.extra_content } }
          : {}),
      };
    }
  }
}

interface StreamChunk {
  choices?: {
    delta?: {
      content?: string | null;
      reasoning_content?: string;
      extra_content?: Record<string, unknown>;
      tool_calls?: (Omit<Partial<AiToolCall>, 'function'> & {
        index?: number;
        function?: Partial<AiToolCall['function']>;
      })[];
    };
    finish_reason?: string | null;
  }[];
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}
