using System;
using System.Collections.Generic;
using System.Net;
using System.Text;
using System.Threading;

namespace Aurum.Companion.Core.Http
{
    /// <summary>Private-network HTTP adapter with bounded admission, bodies and I/O deadlines.</summary>
    public sealed class CompanionHttpServer : IDisposable
    {
        private readonly HttpListener _listener = new HttpListener();
        private readonly CompanionRouter _router;
        private readonly IGameBridge _game;
        private readonly string _prefix;
        private readonly object _gate = new object();
        private readonly HashSet<HttpListenerContext> _active = new HashSet<HttpListenerContext>();
        private readonly int _maxRequests;
        private readonly int _maxBodyBytes;
        private readonly int _ioTimeoutMs;
        private Thread? _thread;
        private volatile bool _running;
        private const int MaxResponseBytes = 1024 * 1024;

        public CompanionHttpServer(CompanionRouter router, IGameBridge game, string host, int port,
            int maxRequests = 4, int maxBodyBytes = 65536, int ioTimeoutMs = 3000)
        {
            if (maxRequests < 1 || maxRequests > 16) throw new ArgumentOutOfRangeException(nameof(maxRequests));
            if (maxBodyBytes < 1 || maxBodyBytes > 1024 * 1024) throw new ArgumentOutOfRangeException(nameof(maxBodyBytes));
            if (ioTimeoutMs < 1 || ioTimeoutMs > 30000) throw new ArgumentOutOfRangeException(nameof(ioTimeoutMs));
            _router = router;
            _game = game;
            _maxRequests = maxRequests;
            _maxBodyBytes = maxBodyBytes;
            _ioTimeoutMs = ioTimeoutMs;
            _prefix = "http://" + host + ":" + port + "/";
            _listener.Prefixes.Add(_prefix);
        }

        public void Start()
        {
            _listener.Start();
            _running = true;
            _thread = new Thread(Loop) { IsBackground = true, Name = "aurum-companion-http" };
            _thread.Start();
            _game.Log("Companion слушает " + _prefix);
        }

        public void Stop()
        {
            _running = false;
            lock (_gate)
                foreach (var context in _active) Abort(context);
            try { _listener.Stop(); } catch (Exception) { }
            _thread?.Join(TimeSpan.FromSeconds(3));
            _thread = null;
        }

        private void Loop()
        {
            while (_running)
            {
                HttpListenerContext context;
                try { context = _listener.GetContext(); }
                catch (Exception)
                {
                    if (_running) _game.LogError("HTTP listener stopped unexpectedly", null);
                    return;
                }
                // Reject before reading even one byte of an unauthenticated body.
                if (!_router.IsAuthorized(context.Request.Headers["Authorization"]))
                {
                    Reject(context, 401);
                    continue;
                }
                bool admitted;
                lock (_gate)
                {
                    admitted = _running && _active.Count < _maxRequests;
                    if (admitted) _active.Add(context);
                }
                if (!admitted) { Reject(context, 503); continue; }
                if (!ThreadPool.QueueUserWorkItem(_ => Respond(context)))
                {
                    lock (_gate) _active.Remove(context);
                    Reject(context, 503);
                }
            }
        }

        private void Respond(HttpListenerContext context)
        {
            // 0=reading, 1=admitted for dispatch, 2=body timed out.
            int state = 0;
            using (var deadline = new Timer(_ =>
            {
                if (Interlocked.CompareExchange(ref state, 2, 0) == 0) Abort(context);
            }, null, _ioTimeoutMs, Timeout.Infinite))
            {
                try
                {
                    if (context.Request.ContentLength64 > _maxBodyBytes) { Reject(context, 413); return; }
                    string body = context.Request.HasEntityBody
                        ? BoundedBody.Read(context.Request.InputStream, _maxBodyBytes) : "";
                    // Timed-out bodies must NEVER dispatch late game actions.
                    if (Interlocked.CompareExchange(ref state, 1, 0) != 0 || !_running) return;
                    deadline.Change(Timeout.Infinite, Timeout.Infinite);
                    var response = _router.Handle(new HttpRequestData(
                        context.Request.HttpMethod, context.Request.Url?.AbsolutePath ?? "/", body),
                        context.Request.Headers["Authorization"]);
                    WriteResponse(context, response);
                }
                catch (BodyTooLargeException) { Reject(context, 413); }
                catch (DecoderFallbackException) { Reject(context, 400); }
                catch (Exception) { Abort(context); } // Routine disconnects must not flood the game log.
                finally
                {
                    Interlocked.Exchange(ref state, 1);
                    Abort(context);
                    lock (_gate) _active.Remove(context);
                }
            }
        }

        private void WriteResponse(HttpListenerContext context, HttpResponseData response)
        {
            var utf8 = new UTF8Encoding(false);
            if (utf8.GetByteCount(response.Json) > MaxResponseBytes) { Reject(context, 503); return; }
            byte[] bytes = utf8.GetBytes(response.Json);
            using (var deadline = new Timer(_ => Abort(context), null, _ioTimeoutMs, Timeout.Infinite))
            {
                context.Response.StatusCode = response.Status;
                context.Response.ContentType = "application/json; charset=utf-8";
                context.Response.KeepAlive = false;
                context.Response.ContentLength64 = bytes.Length;
                context.Response.OutputStream.Write(bytes, 0, bytes.Length);
                context.Response.Close();
            }
        }

        private static void Reject(HttpListenerContext context, int status)
        {
            try
            {
                context.Response.StatusCode = status;
                context.Response.KeepAlive = false;
                context.Response.ContentLength64 = 0;
                context.Response.Close();
            }
            catch (Exception) { Abort(context); }
        }

        private static void Abort(HttpListenerContext context)
        {
            try { context.Response.Abort(); } catch (Exception) { }
            try { context.Request.InputStream.Close(); } catch (Exception) { }
        }

        public void Dispose()
        {
            Stop();
            // Mono can throw again during cleanup of a failed bind. Keep the original error.
            try { ((IDisposable)_listener).Dispose(); } catch (Exception) { }
        }
    }
}
