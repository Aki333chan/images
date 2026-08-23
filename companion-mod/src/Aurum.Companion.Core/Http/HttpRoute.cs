using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Http
{
    /// <summary>Что пришло от панели.</summary>
    public sealed class HttpRequestData
    {
        public HttpRequestData(string method, string path, string body)
        {
            Method = method;
            Path = path;
            Body = body;
        }

        public string Method { get; }
        public string Path { get; }
        public string Body { get; }

        /// <summary>Части пути без пустых: «/players/Steam_1/message» -> [players, Steam_1, message].</summary>
        public string[] Segments => Path.Split(new[] { '/' }, StringSplitOptions.RemoveEmptyEntries);
    }

    /// <summary>Что мод отвечает.</summary>
    public sealed class HttpResponseData
    {
        private HttpResponseData(int status, string json)
        {
            Status = status;
            Json = json;
        }

        public int Status { get; }
        public string Json { get; }

        public static HttpResponseData Ok(string json) => new HttpResponseData(200, json);

        /// <summary>
        /// Ошибка. Текст уходит в панель и оттуда — человеку, поэтому пишется
        /// по-русски и без внутренних подробностей вроде путей и стека.
        /// </summary>
        public static HttpResponseData Error(int status, string message) =>
            new HttpResponseData(status, JsonWriter.Object(new[]
            {
                new KeyValuePair<string, string>("error", JsonWriter.String(message)),
            }));

        public static HttpResponseData NotFound(string message = "Не найдено") => Error(404, message);
        public static HttpResponseData BadRequest(string message) => Error(400, message);
    }
}
