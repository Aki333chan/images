process.env.NODE_ENV = 'test';
import { request } from 'undici';
import { AiProviderClient } from './ai-provider.client';
jest.mock('undici', () => ({ request: jest.fn() }));

const transport = request as jest.Mock;
const config = {
  apiKey: 'secret-test-key',
  provider: 'gemini' as const,
  model: 'gemini-3.8-flash',
  maxOutputTokens: 4096,
};
const frame = (data: unknown) => Buffer.from(`data: ${JSON.stringify(data)}\r\n\r\n`);
function stream(chunks: Uint8Array[], statusCode = 200) {
  const body = {
    dump: jest.fn(),
    async *[Symbol.asyncIterator]() {
      for (const chunk of chunks) yield chunk;
    },
  };
  transport.mockResolvedValue({ statusCode, body });
  return body;
}
beforeEach(() => transport.mockReset());

it('handles UTF-8 split between bytes and CRLF events', async () => {
  const event = frame({ choices: [{ delta: { content: 'Привет 👋' }, finish_reason: 'stop' }] });
  stream([
    ...Array.from(event, (b) => Uint8Array.of(b)),
    frame({ usage: { prompt_tokens: 10, completion_tokens: 5 } }),
  ]);
  const deltas: string[] = [];
  const result = await new AiProviderClient().chat(config, [], [], {
    onDelta: (text) => deltas.push(text),
  });
  expect(result).toMatchObject({
    content: 'Привет 👋',
    promptTokens: 10,
    completionTokens: 5,
    usageKnown: true,
  });
  expect(deltas.join('')).toBe(result.content);
});

it('preserves Gemini thought signatures on streamed tool calls', async () => {
  stream([
    frame({
      choices: [
        {
          delta: {
            tool_calls: [
              {
                index: 0,
                id: 'c1',
                function: { name: 'list_servers', arguments: '{' },
                extra_content: { google: { thought_signature: 'opaque-signature' } },
              },
            ],
          },
        },
      ],
    }),
    frame({
      choices: [
        {
          delta: { tool_calls: [{ index: 0, function: { arguments: '}' } }] },
          finish_reason: 'tool_calls',
        },
      ],
    }),
  ]);
  const client = new AiProviderClient();
  const result = await client.chat(config, [], [], { onDelta: () => {} });
  expect(result.toolCalls[0]).toMatchObject({
    id: 'c1',
    function: { name: 'list_servers', arguments: '{}' },
    extra_content: { google: { thought_signature: 'opaque-signature' } },
  });
  stream([frame({ choices: [{ delta: { content: 'OK' }, finish_reason: 'stop' }] })]);
  await client.chat(
    config,
    [{ role: 'assistant', content: null, tool_calls: result.toolCalls }],
    [],
    { onDelta: () => {} },
  );
  expect(
    JSON.parse(transport.mock.calls[1][1].body).messages[0].tool_calls[0].extra_content.google
      .thought_signature,
  ).toBe('opaque-signature');
});

it('caps output and disables DeepSeek thinking with stateless browser history', async () => {
  stream([frame({ choices: [{ delta: { content: 'OK' }, finish_reason: 'stop' }] })]);
  await new AiProviderClient().chat(
    { ...config, provider: 'deepseek', model: 'deepseek-flash', maxOutputTokens: 1024 },
    [],
    [],
    { onDelta: () => {} },
  );
  const body = JSON.parse(transport.mock.calls[0][1].body);
  expect(body).toMatchObject({
    max_tokens: 1024,
    thinking: { type: 'disabled' },
    reasoning_effort: 'none',
  });
  expect(transport.mock.calls[0][0]).toBe('https://api.deepseek.com/chat/completions');
});

it.each([401, 402, 429, 500])('never leaks the provider error body (%s)', async (status) => {
  const body = stream([Buffer.from('secret-test-key full prompt private data')], status);
  await expect(
    new AiProviderClient().chat(config, [], [], { onDelta: () => {} }),
  ).rejects.toMatchObject({
    message: expect.stringMatching(/^ai\.err\.provider/),
    uncertain: status >= 500,
  });
  expect(body.dump).toHaveBeenCalled();
});

it('treats an interrupted stream as potentially billed, retaining known usage', async () => {
  stream([
    frame({
      choices: [{ delta: { content: 'Partial' } }],
      usage: { prompt_tokens: 20, completion_tokens: 6 },
    }),
  ]);
  await expect(
    new AiProviderClient().chat(config, [], [], { onDelta: () => {} }),
  ).rejects.toMatchObject({
    uncertain: true,
    usage: { usageKnown: true, promptTokens: 20, completionTokens: 6 },
  });
});

it('forwards cancellation to the HTTP transport', async () => {
  transport.mockImplementation(
    (_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new Error('aborted')), {
          once: true,
        });
      }),
  );
  const abort = new AbortController();
  const pending = new AiProviderClient().chat(config, [], [], { onDelta: () => {} }, abort.signal);
  abort.abort();
  await expect(pending).rejects.toMatchObject({ message: 'ai.err.cancelled', uncertain: true });
});
