using System;
using System.IO;
using System.Net;
using System.Text;

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

        public HttpClientTransport(int timeoutMs = 8000)
        {
            _timeoutMs = timeoutMs;
        }

        public PanelResponse Post(string url, string jsonBody, string token)
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create(url);
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
                        return new PanelResponse((int)response.StatusCode, ReadBody(response));
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
        }

        private static string ReadBody(HttpWebResponse response)
        {
            using (Stream? stream = response.GetResponseStream())
            {
                if (stream == null) return "";
                using (var reader = new StreamReader(stream, Encoding.UTF8))
                {
                    return reader.ReadToEnd();
                }
            }
        }
    }
}
