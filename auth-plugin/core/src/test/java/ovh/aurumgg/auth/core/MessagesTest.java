package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Тексты из файлов языка.
 *
 * ПОЧЕМУ ЭТО ПРОВЕРЯЕТСЯ. Файлы правит админ руками — за тем они и заведены.
 * Значит рано или поздно строку удалят, переименуют или потеряют скобку, и
 * плагин должен пережить это без пустого чата: пустая реплика выглядит как
 * «сервер завис», а не как «перевода нет».
 */
class MessagesTest {

    private static final Map<String, Object> RU = Map.of(
            "auth.loggedIn", "Вы вошли",
            "auth.throttled", "Слишком часто. Попробуйте через {seconds} с",
            "auth.motd", List.of("Привет, {player}!", "Онлайн: {online}"));

    @Test
    @DisplayName("Подстановки заполняются, лишние скобки остаются видимыми")
    void подстановки() {
        Messages messages = new Messages(RU, RU);

        assertEquals("Вы вошли", messages.get("auth.loggedIn"));
        assertEquals("Слишком часто. Попробуйте через 5 с",
                messages.get("auth.throttled", Map.of("seconds", "5")));
        // Значение не передали — плейсхолдер остаётся на экране. Так опечатку
        // в файле видно сразу, а не «пропало слово».
        assertEquals("Слишком часто. Попробуйте через {seconds} с",
                messages.get("auth.throttled", Map.of()));
    }

    @Test
    @DisplayName("Ключа нет в выбранном языке — берётся русский")
    void запаснойЯзык() {
        // Файл от старой версии плагина: новых ключей в нём ещё нет.
        Map<String, Object> en = Map.of("auth.loggedIn", "You are logged in");
        Messages messages = new Messages(en, RU);

        assertEquals("You are logged in", messages.get("auth.loggedIn"));
        assertEquals("Слишком часто. Попробуйте через 5 с",
                messages.get("auth.throttled", Map.of("seconds", "5")),
                "недостающий ключ должен прийти из русского, а не пропасть");
    }

    @Test
    @DisplayName("Ключа нет нигде — показывается он сам, а не пустота")
    void неизвестныйКлюч() {
        Messages messages = new Messages(RU, RU);
        assertEquals("auth.чегоТоНет", messages.get("auth.чегоТоНет"));
        assertFalse(messages.has("auth.чегоТоНет"));
        assertTrue(messages.has("auth.loggedIn"));
    }

    @Test
    @DisplayName("Многострочные тексты подставляются построчно")
    void списки() {
        Messages messages = new Messages(RU, RU);
        List<String> lines = messages.list("auth.motd", Map.of("player", "Стив", "online", "7"));

        assertEquals(List.of("Привет, Стив!", "Онлайн: 7"), lines);
    }

    @Test
    @DisplayName("Одиночная строка на месте списка не роняет вывод")
    void строкаВместоСписка() {
        // Обычная правка руками: убрали «- » перед единственной строкой.
        Map<String, Object> broken = Map.of("auth.motd", "Привет, {player}!");
        Messages messages = new Messages(broken, RU);

        assertEquals(List.of("Привет, Стив!"), messages.list("auth.motd", Map.of("player", "Стив")));
    }

    @Test
    @DisplayName("Незнакомый язык в конфиге означает русский, а не отказ старта")
    void разборЯзыка() {
        assertEquals("ru", Messages.normalizeLanguage("ru"));
        assertEquals("pl", Messages.normalizeLanguage(" PL "));
        assertEquals("ru", Messages.normalizeLanguage("de"));
        assertEquals("ru", Messages.normalizeLanguage(null));
        assertEquals("ru", Messages.normalizeLanguage(""));
    }
}
