using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Json;
using Xunit;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Свой JSON — значит, он и должен быть проверен. Библиотеку берут готовой
/// именно потому, что за неё уже отвечает кто-то другой; здесь отвечаем мы.
/// </summary>
public class JsonWriterTests
{
    [Fact]
    public void Кавычки_и_обратная_косая_экранируются()
    {
        Assert.Equal("\"он сказал \\\"привет\\\"\"", JsonWriter.String("он сказал \"привет\""));
        Assert.Equal("\"C:\\\\путь\"", JsonWriter.String(@"C:\путь"));
    }

    // Ник или сообщение с переводом строки иначе развалили бы весь запрос.
    [Fact]
    public void Переводы_строк_и_табуляции_экранируются()
    {
        Assert.Equal("\"а\\nб\\tв\\r\"", JsonWriter.String("а\nб\tв\r"));
    }

    [Fact]
    public void Управляющие_символы_уходят_в_uXXXX()
    {
        Assert.Equal("\"\\u0001\"", JsonWriter.String("\u0001"));
    }

    [Fact]
    public void Кириллица_не_ломается_и_не_экранируется_зря()
    {
        Assert.Equal("\"Гриферша\"", JsonWriter.String("Гриферша"));
    }

    [Fact]
    public void Null_это_null_а_не_пустая_строка()
    {
        Assert.Equal("null", JsonWriter.String(null));
    }

    // На русской локали иначе получилась бы запятая вместо точки.
    [Fact]
    public void Числа_пишутся_инвариантной_культурой()
    {
        Assert.Equal("16.75", JsonWriter.Number(16.75));
        Assert.Equal("-42", JsonWriter.Number(-42L));
    }

    /// <remarks>
    /// Ради этого случая координаты и вынесены в отдельный метод: игра хранит
    /// их в float, а расширение до double тащит мусор двоичного представления.
    /// </remarks>
    [Fact]
    public void Координата_не_обрастает_мусором_после_расширения_float()
    {
        Assert.Equal("342.4", JsonWriter.Coordinate(342.4f));
        Assert.Equal("-541.9", JsonWriter.Coordinate(-541.9f));
        Assert.Equal("49", JsonWriter.Coordinate(49.0f));
    }

    [Fact]
    public void Не_число_становится_null_а_не_ломает_запрос()
    {
        Assert.Equal("null", JsonWriter.Number(double.NaN));
        Assert.Equal("null", JsonWriter.Coordinate(float.PositiveInfinity));
    }

    [Fact]
    public void Порядок_полей_сохраняется()
    {
        string json = JsonWriter.Object(new[]
        {
            new KeyValuePair<string, string>("b", JsonWriter.Number(1L)),
            new KeyValuePair<string, string>("a", JsonWriter.Number(2L)),
        });
        Assert.Equal("{\"b\":1,\"a\":2}", json);
    }

    [Fact]
    public void Время_пишется_в_UTC_по_ISO()
    {
        var moment = new DateTimeOffset(2026, 8, 23, 15, 4, 5, 123, TimeSpan.FromHours(3));
        Assert.Equal("\"2026-08-23T12:04:05.123Z\"", JsonWriter.Timestamp(moment));
    }
}

/// <summary>
/// Разбор ответов панели. Строгий намеренно: «разобралось наполовину» на
/// живом сервере хуже явной ошибки в журнале.
/// </summary>
public class JsonReaderTests
{
    [Fact]
    public void Плоский_объект_разбирается()
    {
        var map = JsonReader.ParseObject("{\"created\":true,\"ticketId\":\"t-1\",\"count\":3}");
        Assert.True(JsonReader.BoolOrDefault(map, "created"));
        Assert.Equal("t-1", JsonReader.StringOrNull(map, "ticketId"));
    }

    [Fact]
    public void Экранирование_разбирается_обратно()
    {
        var map = JsonReader.ParseObject("{\"m\":\"он сказал \\\"да\\\"\\nи ушёл\"}");
        Assert.Equal("он сказал \"да\"\nи ушёл", JsonReader.StringOrNull(map, "m"));
    }

    [Fact]
    public void Юникодное_экранирование_разбирается()
    {
        var map = JsonReader.ParseObject("{\"m\":\"\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\"}");
        Assert.Equal("Привет", JsonReader.StringOrNull(map, "m"));
    }

    [Fact]
    public void Вложенные_структуры_не_ломают_разбор()
    {
        var map = JsonReader.ParseObject("{\"a\":{\"b\":[1,2,{\"c\":\"}\"}]},\"ok\":true}");
        Assert.True(JsonReader.BoolOrDefault(map, "ok"));
    }

    [Fact]
    public void Отсутствующее_поле_это_null_а_не_исключение()
    {
        var map = JsonReader.ParseObject("{}");
        Assert.Null(JsonReader.StringOrNull(map, "ticketId"));
        Assert.False(JsonReader.BoolOrDefault(map, "created"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("не json вовсе")]
    [InlineData("{\"a\":1")]
    [InlineData("{\"a\" 1}")]
    [InlineData("{\"a\":1} лишнее")]
    [InlineData("[1,2,3]")]
    public void Битый_ввод_отвергается_явно(string input)
    {
        Assert.Throws<JsonReader.JsonException>(() => JsonReader.ParseObject(input));
    }

    [Fact]
    public void Записанное_читается_обратно()
    {
        string written = JsonWriter.Object(new[]
        {
            new KeyValuePair<string, string>("text", JsonWriter.String("строка\nс \"кавычками\" и \\")),
        });
        var map = JsonReader.ParseObject(written);
        Assert.Equal("строка\nс \"кавычками\" и \\", JsonReader.StringOrNull(map, "text"));
    }
}
