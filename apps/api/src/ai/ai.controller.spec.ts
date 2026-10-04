process.env.NODE_ENV = 'test';
import { EventEmitter } from 'node:events';
import type { Request, Response } from 'express';
import { AiController } from './ai.controller';
import type { AiService } from './ai.service';
import type { AiSettingsService } from './ai-settings.service';
import type { AiToolsService } from './ai-tools.service';
import type { AuthUser } from '../auth/decorators';
import { I18nService } from '../i18n/i18n.service';

it.each(['normal', 'response-close', 'request-aborted'] as const)(
  'SSE cancellation and listener cleanup: %s',
  async (mode) => {
    const request = Object.assign(new EventEmitter(), { headers: { 'accept-language': 'pl' } });
    const response = Object.assign(new EventEmitter(), {
      setHeader: jest.fn(),
      flushHeaders: jest.fn(),
      write: jest.fn(),
      end: jest.fn(),
    });
    const chat = jest
      .fn<ReturnType<AiService['chat']>, Parameters<AiService['chat']>>()
      .mockImplementation(async (_id, _messages, emit, locale, context, signal) => {
        expect(locale).toBe('pl');
        expect(context).toEqual({ serverId: 's1', tab: 'economy' });
        request.emit('close'); // A completed POST body must NOT cancel the response stream.
        expect(signal?.aborted).toBe(false);
        emit({ type: 'delta', text: 'Hello' });
        if (mode === 'response-close') response.emit('close');
        if (mode === 'request-aborted') request.emit('aborted');
        expect(signal?.aborted).toBe(mode !== 'normal');
        emit({ type: 'done' });
      });
    const controller = new AiController(
      { chat } as unknown as AiService,
      {} as AiSettingsService,
      {} as AiToolsService,
      new I18nService(),
    );
    await controller.chat(
      { id: 'u1' } as AuthUser,
      {
        messages: [{ role: 'user', content: 'hello' }],
        context: { serverId: 's1', tab: 'economy' },
      },
      request as unknown as Request,
      response as unknown as Response,
    );
    expect(response.write).toHaveBeenCalledTimes(mode === 'normal' ? 2 : 1);
    expect(response.end).toHaveBeenCalledTimes(mode === 'normal' ? 1 : 0);
    expect(request.listenerCount('aborted')).toBe(0);
    expect(response.listenerCount('close')).toBe(0);
  },
);

it('does not expose an unexpected setup/database error in the SSE response', async () => {
  const request = Object.assign(new EventEmitter(), { headers: { 'accept-language': 'en' } });
  const response = Object.assign(new EventEmitter(), {
    setHeader: jest.fn(),
    flushHeaders: jest.fn(),
    write: jest.fn(),
    end: jest.fn(),
  });
  const controller = new AiController(
    {
      chat: jest.fn().mockRejectedValue(new Error('database: private-query-secret')),
    } as unknown as AiService,
    {} as AiSettingsService,
    {} as AiToolsService,
    new I18nService(),
  );
  await controller.chat(
    { id: 'u1' } as AuthUser,
    { messages: [{ role: 'user', content: 'hello' }] },
    request as unknown as Request,
    response as unknown as Response,
  );
  expect(JSON.stringify(response.write.mock.calls)).not.toContain('private-query-secret');
  expect(response.end).toHaveBeenCalledTimes(1);
  expect(request.listenerCount('aborted')).toBe(0);
  expect(response.listenerCount('close')).toBe(0);
});
