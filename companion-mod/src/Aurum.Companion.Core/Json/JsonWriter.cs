using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Aurum.Companion.Core.Json
{
    /// <summary>
    /// Минимальная запись JSON.
    /// </summary>
    /// <remarks>
    /// Своя, а не Newtonsoft.Json, и это не изобретение велосипеда.
    /// Мод грузится ВНУТРЬ процесса игры, где уже живут чужие сборки. Притащить
    /// туда свою копию популярной библиотеки — верный способ однажды получить
    /// конфликт версий, который проявится не у нас, а у игрока на живом сервере
    /// и в виде «мод молча не загрузился». Нам нужно записать несколько плоских
    /// объектов; это дешевле, чем такая зависимость.
    /// </remarks>
    public static class JsonWriter
    {
        /// <summary>Строка со всеми обязательными экранированиями, вместе с кавычками.</summary>
        public static string String(string? value)
        {
            if (value == null) return "null";

            var sb = new StringBuilder(value.Length + 2);
            sb.Append('"');
            foreach (char c in value)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        // Управляющие символы обязаны уходить в \uXXXX, иначе
                        // получится JSON, который не примет ни один разборщик.
                        if (c < 0x20)
                        {
                            sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        }
                        else
                        {
                            sb.Append(c);
                        }
                        break;
                }
            }
            sb.Append('"');
            return sb.ToString();
        }

        /// <summary>Число. Всегда инвариантной культурой: на русской локали иначе была бы запятая.</summary>
        public static string Number(double value)
        {
            if (double.IsNaN(value) || double.IsInfinity(value)) return "null";
            return value.ToString("R", CultureInfo.InvariantCulture);
        }

        public static string Number(long value) => value.ToString(CultureInfo.InvariantCulture);

        /// <summary>
        /// Координата мира.
        /// </summary>
        /// <remarks>
        /// Отдельно от <see cref="Number(double)"/>, и не из брезгливости.
        /// Координаты игра хранит в float, а расширение float до double тащит
        /// за собой мусор двоичного представления: 342.4f превращается в
        /// 342.3999938964844, и ровно это уехало бы в панель и в глаза человеку.
        ///
        /// Сотых достаточно: это координаты мира в блоках, и сама игра печатает
        /// их с одним знаком (pos=(342.4, 49.0, -541.9)). Формат «0.##» ведёт
        /// себя одинаково и в .NET Framework внутри игры, и в .NET тестов —
        /// в отличие от «R», у которого с float историческая разница между
        /// средами.
        /// </remarks>
        public static string Coordinate(float value)
        {
            if (float.IsNaN(value) || float.IsInfinity(value)) return "null";
            return Math.Round(value, 2).ToString("0.##", CultureInfo.InvariantCulture);
        }

        public static string Bool(bool value) => value ? "true" : "false";

        /// <summary>Объект из уже сериализованных значений. Порядок ключей сохраняется.</summary>
        public static string Object(IEnumerable<KeyValuePair<string, string>> fields)
        {
            var sb = new StringBuilder("{");
            bool first = true;
            foreach (var field in fields)
            {
                if (!first) sb.Append(',');
                first = false;
                sb.Append(String(field.Key)).Append(':').Append(field.Value);
            }
            return sb.Append('}').ToString();
        }

        public static string Array(IEnumerable<string> items)
        {
            var sb = new StringBuilder("[");
            bool first = true;
            foreach (string item in items)
            {
                if (!first) sb.Append(',');
                first = false;
                sb.Append(item);
            }
            return sb.Append(']').ToString();
        }

        /// <summary>Момент времени в ISO 8601 с зоной UTC — панель разбирает именно такой.</summary>
        public static string Timestamp(DateTimeOffset value) =>
            String(value.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture));
    }
}
