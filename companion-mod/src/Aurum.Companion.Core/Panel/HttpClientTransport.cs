using System;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using Aurum.Companion.Core.Http;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>
    /// Настоящая отправка. Единственный класс ядра, который ходит в сеть.
    /// </summary>
    /// <remarks>
    /// ПОЧЕМУ HttpWebRequest, А НЕ HttpClient. Мод грузится в процесс Unity, а
    /// там своя Mono с собственной сборкой System.Net.Http — и именно она
    /// известна расхождениями версий и странностями. HttpWebRequest живёт в
    /// System.dll, которая в этом окружении есть всегда и работает предсказуемо.
    /// Ради «современности» рисковать тем, что мод не загрузится у игрока на
    /// живом сервере, незачем: нам нужен один POST с заголовком.
    ///
    /// Синхронно намеренно: вызывающий — выделенный поток отправки, блокировать
    /// ему некого. Смешивать async в мод внутри Unity — лишний источник тонких
    /// ошибок с контекстом синхронизации.
    ///
    /// Ошибки сети превращаются в <see cref="PanelResponse"/> со статусом 0, а
    /// не в исключение: для вызывающего «сеть легла» и «панель ответила 503» —
    /// одно и то же событие.
    /// </remarks>
    public sealed class HttpClientTransport : IHttpTransport
    {
        private readonly int _timeoutMs;
        private readonly int _maxResponseBytes;

        public HttpClientTransport(int timeoutMs = 8000, int maxResponseBytes = 65536)
        {
            if (timeoutMs < 1 || timeoutMs > 60000) throw new ArgumentOutOfRangeException(nameof(timeoutMs));
            if (maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024) throw new ArgumentOutOfRangeException(nameof(maxResponseBytes));
            _timeoutMs = timeoutMs;
            _maxResponseBytes = maxResponseBytes;
        }

        public PanelResponse Post(string url, string jsonBody, string token)
        {
            HttpWebRequest? request = null;
            Timer? deadline = null;
            try
            {
                request = (HttpWebRequest)WebRequest.Create(url);
                var pending = request;
                // Absolute deadline also covers a slow trickle of response bytes.
                deadline = new Timer(_ => { try { pending.Abort(); } catch (Exception) { } },
                    null, _timeoutMs, Timeout.Infinite);
                request.Method = "POST";
                request.ContentType = "application/json; charset=utf-8";
                request.Headers["Authorization"] = "Bearer " + token;
                request.Timeout = _timeoutMs;
                request.ReadWriteTimeout = _timeoutMs;
                // Панель внутри туннеля: ни прокси, ни редиректы здесь не нужны,
                // а системный прокси увёл бы приватный трафик наружу.
                request.Proxy = null;
                request.AllowAutoRedirect = false;
                request.KeepAlive = true;

                byte[] payload = new UTF8Encoding(false).GetBytes(jsonBody);
                request.ContentLength = payload.Length;
                using (Stream stream = request.GetRequestStream())
                {
                    stream.Write(payload, 0, payload.Length);
                }

                using (var response = (HttpWebResponse)request.GetResponse())
                {
                    return new PanelResponse((int)response.StatusCode, ReadBody(response));
                }
            }
            catch (WebException e)
            {
                // Ответ с кодом 4xx/5xx прилетает сюда же исключением — но это
                // полноценный ответ панели, и терять его текст нельзя: в нём
                // причина отказа, которую увидит игрок.
                if (e.Response is HttpWebResponse response)
                {
                    using (response)
                    {
                        // Preserve 4xx status even if the error body is oversized or stalls.
                        try { return new PanelResponse((int)response.StatusCode, ReadBody(response)); }
                        catch (Exception) { return new PanelResponse((int)response.StatusCode, ""); }
                    }
                }
                return new PanelResponse(0, e.Status.ToString());
            }
            catch (Exception e)
            {
                // Текст исключения может содержать адрес панели — он приватный,
                // поэтому наружу отдаём только тип.
                return new PanelResponse(0, e.GetType().Name);
            }
            finally
            {
                deadline?.Dispose();
                try { request?.Abort(); } catch (Exception) { }
            }
        }

        private string ReadBody(HttpWebResponse response)
        {
            if (response.ContentLength > _maxResponseBytes) throw new BodyTooLargeException();
            using (Stream? stream = response.GetResponseStream())
            {
                if (stream == null) return "";
                return BoundedBody.Read(stream, _maxResponseBytes);
            }
        }
    }
}
