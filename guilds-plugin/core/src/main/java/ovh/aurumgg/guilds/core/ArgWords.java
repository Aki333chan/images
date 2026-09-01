package ovh.aurumgg.guilds.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор аргументов команды, общий для выполнения и для автодополнения.
 *
 * <h2>Зачем одно место на двоих</h2>
 *
 * Подсказка и разбор обязаны понимать аргументы ОДИНАКОВО. Если команда
 * считает «7d» сроком, а автодополнение — первым словом имени гильдии, то Tab
 * предлагает продолжение имени там, где его уже не будет, и человек набирает
 * команду, которая откажет. Разойтись эти два места могут молча: подсказка
 * ничего не выполняет, и ошибку в ней замечают не сразу.
 *
 * Поэтому обе стороны зовут отсюда, а здесь нет ни одного класса Bukkit —
 * значит, это проверяется тестами.
 */
public final class ArgWords {

    private ArgWords() {}

    /**
     * Срок вида «30m», «2h», «7d» — или {@code null}, если это не срок.
     *
     * {@code null} здесь значит не «ошибка», а «следующий аргумент не срок»:
     * в {@code /guild admin bonus grant} срок необязателен, и отличить его от
     * начала имени гильдии можно только по виду. «30m» — срок, «Драконы» —
     * имя.
     *
     * Русские буквы принимаются наравне с латинскими: раскладку при наборе
     * команды переключают не всегда, а «30м» человек имел в виду ровно то же
     * самое.
     */
    public static Duration duration(String raw) {
        if (raw == null || raw.length() < 2) return null;
        char unit = Character.toLowerCase(raw.charAt(raw.length() - 1));
        String digits = raw.substring(0, raw.length() - 1);
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0) return null;
        return switch (unit) {
            case 'm', 'м' -> Duration.ofMinutes(value);
            case 'h', 'ч' -> Duration.ofHours(value);
            case 'd', 'д' -> Duration.ofDays(value);
            default -> null;
        };
    }

    /**
     * Очередное слово имён, у которых предыдущие слова уже совпали.
     *
     * <h2>Почему по словам, а не именем целиком</h2>
     *
     * Bukkit режет команду по пробелам и отдаёт автодополнению отдельные
     * токены. Имя «Ночные волки» одним токеном не дополнить: чем бы мы ни
     * ответили, клиент подставит это вместо ПОСЛЕДНЕГО слова, а не вместо
     * всей строки. Поэтому подсказывается по слову за раз: набрал «Ночные» —
     * получил «волки».
     *
     * Совпадение предыдущих слов обязательно. Без этой проверки на «Ночные»
     * предлагались бы вторые слова всех гильдий подряд, включая те, что
     * начинаются иначе, — и человек дополнял бы имя, которого не существует.
     *
     * @param names список имён целиком
     * @param args  аргументы команды как их отдал Bukkit
     * @param from  индекс аргумента, с которого начинается имя
     * @return слова-кандидаты без повторов; пусто, если подсказывать нечего
     */
    public static List<String> nextWords(List<String> names, String[] args, int from) {
        int index = args.length - 1 - from;
        if (index < 0) return List.of();

        List<String> words = new ArrayList<>();
        for (String name : names) {
            String[] parts = name.split(" ");
            if (parts.length <= index) continue;

            boolean matches = true;
            for (int i = 0; i < index; i++) {
                if (!parts[i].equalsIgnoreCase(args[from + i])) {
                    matches = false;
                    break;
                }
            }
            if (matches && !words.contains(parts[index])) words.add(parts[index]);
        }
        return words;
    }
}
