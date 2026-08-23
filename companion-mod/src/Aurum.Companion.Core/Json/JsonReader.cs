using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Aurum.Companion.Core.Json
{
    /// <summary>
    /// Минимальный разбор JSON — ровно столько, сколько нужно моду.
    /// </summary>
    /// <remarks>
    /// Мод читает чужой JSON в двух местах: ответ панели на отправленный тикет
    /// и тело запроса от панели. Оба — плоские объекты из строк, чисел и
    /// логических значений.
    ///
    /// Разбор намеренно строгий: на некорректном вводе бросает, а не
    /// возвращает полупустой объект. Мод стоит на живом сервере, и «тихо
    /// разобралось наполовину» здесь хуже, чем явная ошибка в журнале.
    /// </remarks>
    public static class JsonReader
    {
        public sealed class JsonException : Exception
        {
            public JsonException(string message) : base(message) { }
        }

        /// <summary>
        /// Разбирает объект верхнего уровня в плоскую карту.
        /// Вложенные объекты и массивы сохраняются как исходный текст.
        /// </summary>
        public static Dictionary<string, object?> ParseObject(string json)
        {
            if (json == null) throw new JsonException("Пустой ответ");
            int i = 0;
            var value = ParseValue(json, ref i);
            SkipWhitespace(json, ref i);
            if (i != json.Length) throw new JsonException("Лишние символы после JSON");
            if (value is Dictionary<string, object?> map) return map;
            throw new JsonException("Ожидался объект JSON");
        }

        /// <summary>Строковое поле или null, если поля нет либо оно другого типа.</summary>
        public static string? StringOrNull(Dictionary<string, object?> map, string key) =>
            map.TryGetValue(key, out var value) ? value as string : null;

        public static bool BoolOrDefault(Dictionary<string, object?> map, string key, bool fallback = false) =>
            map.TryGetValue(key, out var value) && value is bool b ? b : fallback;

        private static object? ParseValue(string s, ref int i)
        {
            SkipWhitespace(s, ref i);
            if (i >= s.Length) throw new JsonException("Неожиданный конец JSON");

            char c = s[i];
            switch (c)
            {
                case '{': return ParseObjectBody(s, ref i);
                case '[': return ParseRaw(s, ref i, '[', ']');
                case '"': return ParseString(s, ref i);
                case 't': Expect(s, ref i, "true"); return true;
                case 'f': Expect(s, ref i, "false"); return false;
                case 'n': Expect(s, ref i, "null"); return null;
                default: return ParseNumber(s, ref i);
            }
        }

        private static Dictionary<string, object?> ParseObjectBody(string s, ref int i)
        {
            var map = new Dictionary<string, object?>(StringComparer.Ordinal);
            i++; // '{'
            SkipWhitespace(s, ref i);
            if (i < s.Length && s[i] == '}') { i++; return map; }

            while (true)
            {
                SkipWhitespace(s, ref i);
                if (i >= s.Length || s[i] != '"') throw new JsonException("Ожидалось имя поля");
                string key = ParseString(s, ref i);
                SkipWhitespace(s, ref i);
                if (i >= s.Length || s[i] != ':') throw new JsonException("Ожидалось двоеточие");
                i++;
                map[key] = ParseValue(s, ref i);
                SkipWhitespace(s, ref i);
                if (i >= s.Length) throw new JsonException("Незакрытый объект");
                if (s[i] == ',') { i++; continue; }
                if (s[i] == '}') { i++; return map; }
                throw new JsonException("Ожидалась запятая или конец объекта");
            }
        }

        /// <summary>Вложенная структура возвращается как есть: моду её содержимое не нужно.</summary>
        private static string ParseRaw(string s, ref int i, char open, char close)
        {
            int start = i;
            int depth = 0;
            bool inString = false;
            for (; i < s.Length; i++)
            {
                char c = s[i];
                if (inString)
                {
                    if (c == '\\') i++;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == open) depth++;
                else if (c == close)
                {
                    depth--;
                    if (depth == 0) { i++; return s.Substring(start, i - start); }
                }
            }
            throw new JsonException("Незакрытая структура");
        }

        private static string ParseString(string s, ref int i)
        {
            i++; // открывающая кавычка
            var sb = new StringBuilder();
            while (i < s.Length)
            {
                char c = s[i++];
                if (c == '"') return sb.ToString();
                if (c != '\\') { sb.Append(c); continue; }

                if (i >= s.Length) break;
                char esc = s[i++];
                switch (esc)
                {
                    case '"': sb.Append('"'); break;
                    case '\\': sb.Append('\\'); break;
                    case '/': sb.Append('/'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'u':
                        if (i + 4 > s.Length) throw new JsonException("Обрезанная \\u-последовательность");
                        sb.Append((char)int.Parse(s.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                        i += 4;
                        break;
                    default: throw new JsonException("Неизвестное экранирование \\" + esc);
                }
            }
            throw new JsonException("Незакрытая строка");
        }

        private static double ParseNumber(string s, ref int i)
        {
            int start = i;
            while (i < s.Length && (char.IsDigit(s[i]) || "+-.eE".IndexOf(s[i]) >= 0)) i++;
            string text = s.Substring(start, i - start);
            if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out double value)) return value;
            throw new JsonException("Не число: " + text);
        }

        private static void Expect(string s, ref int i, string literal)
        {
            if (i + literal.Length > s.Length || string.CompareOrdinal(s, i, literal, 0, literal.Length) != 0)
            {
                throw new JsonException("Ожидалось " + literal);
            }
            i += literal.Length;
        }

        private static void SkipWhitespace(string s, ref int i)
        {
            while (i < s.Length && char.IsWhiteSpace(s[i])) i++;
        }
    }
}
