package ovh.aurumgg.companion.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    @DisplayName("Экранирует кавычки, слэши и переводы строк")
    void escapesSpecialCharacters() {
        assertEquals("он сказал \\\"привет\\\"", Json.escape("он сказал \"привет\""));
        assertEquals("C:\\\\путь", Json.escape("C:\\путь"));
        assertEquals("строка\\nвторая", Json.escape("строка\nвторая"));
    }

    @Test
    @DisplayName("Управляющие символы уходят \\u-последовательностью")
    void escapesControlCharacters() {
        assertEquals("\\u0000", Json.escape("\u0000"));
        assertEquals("\\u001f", Json.escape("\u001f"));
    }

    @Test
    @DisplayName("Кириллица не ломается")
    void keepsCyrillic() {
        assertEquals("\"Гриферство\"", Json.string("Гриферство"));
    }

    @Test
    @DisplayName("null становится JSON-литералом null, а не строкой")
    void nullBecomesLiteral() {
        assertEquals("null", Json.string(null));
    }

    @Test
    @DisplayName("Целые числа печатаются без хвоста .0")
    void integersHaveNoDecimalTail() {
        assertEquals("64", Json.number(64.0));
        assertEquals("-200.5", Json.number(-200.5));
    }

    @Test
    @DisplayName("Экранированная строка разбирается обратно без потерь")
    void roundTripThroughParser() {
        String tricky = "кавычка \" слэш \\ перевод\nстроки\tтабуляция";
        Object parsed = JsonParser.parse(Json.string(tricky));
        assertEquals(tricky, parsed);
    }
}

class JsonParserTest {

    @Test
    @DisplayName("Разбирает объект с разными типами значений")
    void parsesObject() {
        Map<String, Object> parsed =
                JsonParser.parseObject("{\"id\":\"minecraft:stone\",\"count\":3,\"ok\":true,\"none\":null}");
        assertEquals("minecraft:stone", parsed.get("id"));
        assertEquals(3.0, parsed.get("count"));
        assertEquals(Boolean.TRUE, parsed.get("ok"));
        assertTrue(parsed.containsKey("none"));
        assertEquals(null, parsed.get("none"));
    }

    @Test
    @DisplayName("Разбирает вложенные массивы и объекты")
    void parsesNested() {
        Map<String, Object> parsed = JsonParser.parseObject("{\"a\":[1,2,{\"b\":\"c\"}]}");
        List<?> list = (List<?>) parsed.get("a");
        assertEquals(3, list.size());
        assertEquals("c", ((Map<?, ?>) list.get(2)).get("b"));
    }

    @Test
    @DisplayName("Пустой объект и массив")
    void parsesEmpty() {
        assertTrue(JsonParser.parseObject("{}").isEmpty());
        assertEquals(List.of(), JsonParser.parse("[]"));
    }

    @Test
    @DisplayName("Понимает \\u-последовательности")
    void parsesUnicodeEscapes() {
        assertEquals("A", JsonParser.parse("\"\\u0041\""));
    }

    @Test
    @DisplayName("Отвергает мусор вместо тихого проглатывания")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":1}трейлинг"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\""));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parseObject("[1,2]"));
    }
}
