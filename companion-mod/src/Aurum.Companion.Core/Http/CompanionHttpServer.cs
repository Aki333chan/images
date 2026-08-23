using System;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;

namespace Aurum.Companion.Core.Http
{
    /// <summary>
    /// Приём запросов панели.
    /// </summary>
    /// <remarks>
    /// Тонкая обёртка над HttpListener: принять байты, отдать байты. Вся
    /// логика — в <see cref="CompanionRouter"/>, который проверяется тестами
    /// без сокета.
    ///
    /// ПРО АДРЕС. Слушаем по умолчанию только 127.0.0.1. Панель живёт на
    /// другой машине и приходит по туннелю, поэтому адрес прослушивания задаёт
    /// человек осознанно — но умолчание должно быть таким, при котором забытая
    /// настройка не открывает управление сервером всему интернету.
    /// </remarks>
    public sealed class CompanionHttpServer : IDisposable
    {
        private readonly HttpListener _listener = new HttpListener();
        private readonly CompanionRouter _router;
        private readonly IGameBridge _game;
        private readonly string _prefix;
        private Thread? _thread;
        private volatile bool _running;

        public CompanionHttpServer(CompanionRouter router, IGameBridge game, string host, int port)
        {
            _router = router;
            _game = game;
            _prefix = "http://" + host + ":" + port + "/";
            _listener.Prefixes.Add(_prefix);
        }

        public void Start()
        {
            _listener.Start();
            _running = true;
            _thread = new Thread(Loop)
            {
                IsBackground = true,
                Name = "aurum-companion-http",
            };
            _thread.Start();
            _game.Log("Companion слушает " + _prefix);
        }

        public void Stop()
        {
            _running = false;
            try { _listener.Stop(); } catch (Exception) { /* уже остановлен */ }
            _thread?.Join(TimeSpan.FromSeconds(3));
            _thread = null;
        }

        private void Loop()
        {
            while (_running)
            {
                HttpListenerContext context;
                try
                {
                    context = _listener.GetContext();
                }
                catch (Exception)
                {
                    // Остановка листенера прилетает сюда исключением — это не
                    // ошибка, а способ разбудить блокирующий вызов.
                    if (!_running) return;
                    continue;
                }

                // Обработка на месте, без пула: панель ходит редко и по одному
                // запросу, а лишние потоки внутри процесса игры не бесплатны.
                try
                {
                    Respond(context);
                }
                catch (Exception e)
                {
                    _game.LogError("Не удалось ответить панели", e);
                }
            }
        }

        private void Respond(HttpListenerContext context)
        {
            string body = "";
            if (context.Request.HasEntityBody)
            {
                using (var reader = new StreamReader(context.Request.InputStream, context.Request.ContentEncoding ?? Encoding.UTF8))
                {
                    body = reader.ReadToEnd();
                }
            }

            var request = new HttpRequestData(
                context.Request.HttpMethod ?? "GET",
                context.Request.Url?.AbsolutePath ?? "/",
                body);

            HttpResponseData response = _router.Handle(request, context.Request.Headers["Authorization"]);

            byte[] payload = new UTF8Encoding(false).GetBytes(response.Json);
            context.Response.StatusCode = response.Status;
            context.Response.ContentType = "application/json; charset=utf-8";
            context.Response.ContentLength64 = payload.Length;
            using (Stream output = context.Response.OutputStream)
            {
                output.Write(payload, 0, payload.Length);
            }
        }

        public void Dispose()
        {
            Stop();
            ((IDisposable)_listener).Dispose();
        }
    }
}
