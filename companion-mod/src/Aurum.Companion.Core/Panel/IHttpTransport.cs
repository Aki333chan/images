using System;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>Ответ панели, сведённый к тому, что моду нужно знать.</summary>
    public sealed class PanelResponse
    {
        public PanelResponse(int status, string body)
        {
            Status = status;
            Body = body;
        }

        public int Status { get; }
        public string Body { get; }

        /// <summary>2xx.</summary>
        public bool IsSuccess => Status >= 200 && Status < 300;

        /// <summary>
        /// Стоит ли повторять.
        /// </summary>
        /// <remarks>
        /// Повторяем только то, что может пройти позже: сеть легла (0), панель
        /// перезапускается (5xx), слишком часто (429). На 4xx повтор бессмысленен —
        /// панель поняла запрос и отказала, и сто повторов ничего не изменят,
        /// зато забьют очередь.
        /// </remarks>
        public bool IsRetryable => Status == 0 || Status == 429 || Status >= 500;
    }

    /// <summary>
    /// Отправка HTTP наружу.
    /// </summary>
    /// <remarks>
    /// Интерфейс, а не прямой вызов HttpClient, ровно ради тестов: настоящая
    /// сеть в тестах превращает проверку логики повторов в проверку удачи.
    /// </remarks>
    public interface IHttpTransport
    {
        PanelResponse Post(string url, string jsonBody, string token);
    }
}
