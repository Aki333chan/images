package ovh.aurumgg.companion.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный разборщик JSON — нужен только для тела POST-запроса на изменение
 * слота. Возвращает Map/List/String/Double/Boolean/null.
 */
public final class JsonParser {

    private final String src;
    private int pos;

    private JsonParser(String src) {
        this.src = src;
    }

    public static Object parse(String json) {
        JsonParser p = new JsonParser(json);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("Лишние символы после JSON на позиции " + p.pos);
        }
        return value;
    }

    /** Удобная обёртка: разбирает объект верхнего уровня. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Ожидался JSON-объект");
        }
        return (Map<String, Object>) value;
    }

    private Object readValue() {
        if (pos >= src.length()) throw new IllegalArgumentException("Неожиданный конец JSON");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') return result;
            if (c != ',') throw new IllegalArgumentException("Ожидалась ',' или '}' на позиции " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') return result;
            if (c != ',') throw new IllegalArgumentException("Ожидалась ',' или ']' на позиции " + pos);
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > src.length()) throw new IllegalArgumentException("Обрезанная \\u-последовательность");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("Неизвестная escape-последовательность \\" + esc);
            }
        }
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Некорректный литерал на позиции " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Некорректный литерал на позиции " + pos);
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw new IllegalArgumentException("Ожидалось число на позиции " + pos);
        return Double.parseDouble(src.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) throw new IllegalArgumentException("Неожиданный конец JSON");
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) throw new IllegalArgumentException("Неожиданный конец JSON");
        return src.charAt(pos++);
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new IllegalArgumentException("Ожидался '" + expected + "' на позиции " + (pos - 1));
        }
    }
}
