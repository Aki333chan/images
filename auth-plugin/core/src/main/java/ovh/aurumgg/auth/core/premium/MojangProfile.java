package ovh.aurumgg.auth.core.premium;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Профиль лицензионной учётки: то немногое, что нужно из ответа Mojang.
 *
 * @param uuid настоящий UUID учётки
 * @param name ник в том написании, в котором его хранит Mojang
 */
public record MojangProfile(UUID uuid, String name) {

    /** Mojang отдаёт UUID без дефисов — 32 шестнадцатеричных символа. */
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Разбор ответа Mojang.
     *
     * ПОЧЕМУ КОДЫ РАЗВЕДЕНЫ ТАК ПОДРОБНО. Здесь решается, спрашивать ли у
     * человека пароль, и «нет такой учётки» ни в коем случае не должно
     * получаться из «Mojang нас притормозил». Поэтому:
     *
     * <ul>
     *   <li>200 с телом — учётка есть;</li>
     *   <li>404 и 204 — учётки нет. Оба кода настоящие: исторически Mojang
     *       отвечал на неизвестный ник пустым 204, позже — 404 с телом ошибки,
     *       и встретить можно оба;</li>
     *   <li>всё прочее (429 при лимите запросов, 5xx, что угодно) — исключение,
     *       то есть «спросить не удалось». Молча превратить это в «не premium»
     *       значило бы менять поведение входа при чужой аварии.</li>
     * </ul>
     */
    public static Optional<MojangProfile> parse(int statusCode, String body) {
        if (statusCode == 404 || statusCode == 204) return Optional.empty();
        if (statusCode != 200) {
            throw new IllegalStateException("Mojang ответил " + statusCode);
        }
        if (body == null || body.isBlank()) return Optional.empty();

        Matcher id = ID.matcher(body);
        if (!id.find()) {
            // 200 без пригодного id — не «учётки нет», а непонятный ответ.
            throw new IllegalStateException("В ответе Mojang нет поля id");
        }
        Matcher name = NAME.matcher(body);
        return Optional.of(new MojangProfile(
                withDashes(id.group(1)), name.find() ? name.group(1) : ""));
    }

    /** 32 символа без дефисов → обычный UUID. */
    public static UUID withDashes(String undashed) {
        return UUID.fromString(new StringBuilder(undashed)
                .insert(20, '-').insert(16, '-').insert(12, '-').insert(8, '-')
                .toString());
    }
}
