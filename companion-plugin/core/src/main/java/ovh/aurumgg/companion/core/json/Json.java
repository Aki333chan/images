package ovh.aurumgg.companion.core.json;

import java.util.Collection;
import java.util.Map;

/**
 * Минимальный генератор JSON. Своя реализация вместо Gson/Jackson: плагин
 * должен оставаться одним небольшим jar без внешних зависимостей, а объём
 * нужного нам JSON — несколько типов объектов.
 */
public final class Json {

    private Json() {}

    public static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // Управляющие символы обязаны экранироваться шестнадцатеричной
                    // последовательностью вида "backslash-u-XXXX".
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String string(String value) {
        return value == null ? "null" : '"' + escape(value) + '"';
    }

    public static String number(double value) {
        // Целые значения печатаем без хвоста .0 — так JSON читаемее.
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Собирает объект из уже сериализованных значений. */
    public static String object(Map<String, String> rawFields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : rawFields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(string(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    /** Собирает массив из уже сериализованных элементов. */
    public static String array(Collection<String> rawItems) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : rawItems) {
            if (!first) sb.append(',');
            first = false;
            sb.append(item);
        }
        return sb.append(']').toString();
    }
}
