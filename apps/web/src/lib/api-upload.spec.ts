import { ApiError, apiRaw, setAccessToken, setSessionExpiredHandler } from './api';

describe('file uploads with native progress', () => {
  const originalFetch = global.fetch;
  const originalXhr = global.XMLHttpRequest;
  const originalStorage = global.localStorage;
  let responses: { status: number; body?: string; failure?: 'error' | 'abort' }[];
  let requests: MockXhr[];

  class MockXhr {
    status = 200;
    responseText = '';
    withCredentials = false;
    headers: Record<string, string> = {};
    body?: Blob;
    upload: { onprogress?: (event: { loaded: number }) => void } = {};
    onload?: () => void;
    onerror?: () => void;
    onabort?: () => void;
    open = jest.fn();

    constructor() { requests.push(this); }
    setRequestHeader(name: string, value: string) { this.headers[name] = value; }
    send(body: Blob) {
      this.body = body;
      const response = responses.shift()!;
      queueMicrotask(() => {
        this.upload.onprogress?.({ loaded: body.size });
        this.status = response.status;
        this.responseText = response.body ?? '';
        if (response.failure === 'error') this.onerror?.();
        else if (response.failure === 'abort') this.onabort?.();
        else this.onload?.();
      });
    }
  }

  beforeEach(() => {
    requests = [];
    responses = [{ status: 200, body: '{"path":"/plugins/test.jar"}' }];
    global.XMLHttpRequest = MockXhr as unknown as typeof XMLHttpRequest;
    global.localStorage = { getItem: () => 'en' } as unknown as Storage;
    setAccessToken('initial-token');
  });

  afterEach(() => {
    global.fetch = originalFetch;
    global.XMLHttpRequest = originalXhr;
    global.localStorage = originalStorage;
    setAccessToken(null);
    setSessionExpiredHandler(() => undefined);
  });

  it('sends the original body, auth and locale, and reports bytes sent', async () => {
    const body = new Blob(['file-content']);
    const progress = jest.fn();
    await expect(apiRaw('/api/servers/x/files/upload', body, progress)).resolves.toEqual({ path: '/plugins/test.jar' });
    expect(requests[0]!.open).toHaveBeenCalledWith('POST', '/api/servers/x/files/upload');
    expect(requests[0]!.body).toBe(body);
    expect(requests[0]!.withCredentials).toBe(true);
    expect(requests[0]!.headers).toEqual({
      'content-type': 'application/octet-stream',
      'accept-language': 'en',
      authorization: 'Bearer initial-token',
    });
    expect(progress).toHaveBeenCalledWith(body.size);
  });

  it('keeps ordinary saves on the existing fetch path', async () => {
    global.fetch = jest.fn(async () => new Response('{"path":"/config.yml"}')) as unknown as typeof fetch;
    const body = new Blob(['config']);
    await expect(apiRaw('/api/servers/x/files/content', body)).resolves.toEqual({ path: '/config.yml' });
    expect(requests).toHaveLength(0);
    expect(global.fetch).toHaveBeenCalledWith('/api/servers/x/files/content', expect.objectContaining({ body, method: 'POST' }));
  });

  it('refreshes an expired token and retries only once using the new token', async () => {
    responses.unshift({ status: 401 });
    global.fetch = jest.fn(async () => new Response('{"accessToken":"renewed-token"}')) as unknown as typeof fetch;
    await apiRaw('/api/servers/x/files/upload', new Blob(['test']), jest.fn());
    expect(requests).toHaveLength(2);
    expect(requests[1]!.headers.authorization).toBe('Bearer renewed-token');
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('expires the session without retrying when refresh fails', async () => {
    responses = [{ status: 401, body: '{"message":"Session expired"}' }];
    global.fetch = jest.fn(async () => new Response(null, { status: 401 })) as unknown as typeof fetch;
    const expire = jest.fn();
    setSessionExpiredHandler(expire);
    await expect(apiRaw('/api/servers/x/files/upload', new Blob(), jest.fn())).rejects.toMatchObject({ status: 401 });
    expect(requests).toHaveLength(1);
    expect(expire).toHaveBeenCalledTimes(1);
  });

  it('preserves the existing proxy error explanation', async () => {
    responses = [{ status: 413, body: '<html>413</html>' }];
    await expect(apiRaw('/api/x', new Blob(), jest.fn())).rejects.toThrow(/64 MiB/);
  });

  it.each(['error', 'abort'] as const)('rejects %s instead of leaving the upload stuck', async (failure) => {
    responses = [{ status: 0, failure }];
    await expect(apiRaw('/api/x', new Blob(), jest.fn())).rejects.toThrow(ApiError);
    responses = [{ status: 0, failure }];
    await expect(apiRaw('/api/x', new Blob(), jest.fn())).rejects.toThrow(/Check your connection/);
  });

  it('accepts an empty 204 response', async () => {
    responses = [{ status: 204 }];
    await expect(apiRaw('/api/x', new Blob(), jest.fn())).resolves.toBeUndefined();
  });
});
