using System;
using System.Text;

namespace Aurum.Companion.Core.Http
{
    /// <summary>
    /// Сверка предъявленного токена с настроенным.
    /// </summary>
    public static class TokenAuth
    {
        /// <summary>Вынимает токен из заголовка Authorization. Схема Bearer необязательна.</summary>
        public static string? Extract(string? authorizationHeader)
        {
            if (string.IsNullOrWhiteSpace(authorizationHeader)) return null;
            string value = authorizationHeader!.Trim();
            const string bearer = "Bearer ";
            if (value.StartsWith(bearer, StringComparison.OrdinalIgnoreCase))
            {
                value = value.Substring(bearer.Length).Trim();
            }
            return value.Length == 0 ? null : value;
        }

        /// <summary>
        /// Сравнение за постоянное время.
        /// </summary>
        /// <remarks>
        /// Обычное сравнение строк выходит на первом несовпавшем символе, и по
        /// времени ответа токен подбирается посимвольно. Сама длина секретом не
        /// является, поэтому её различие можно вернуть сразу.
        /// </remarks>
        public static bool Equals(string? expected, string? provided)
        {
            if (expected == null || provided == null) return false;
            byte[] a = Encoding.UTF8.GetBytes(expected);
            byte[] b = Encoding.UTF8.GetBytes(provided);
            if (a.Length != b.Length) return false;

            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }
    }
}
