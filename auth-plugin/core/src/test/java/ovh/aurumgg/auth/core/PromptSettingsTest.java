package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.auth.api.AuthStatus;

/**
 * Разбор настроек подсказки.
 *
 * Проверяется то, что ломается молча: зажимание чисел, пустая строка как
 * осмысленное «не показывать» и согласование времени показа с периодом
 * повтора. Ошибки здесь не падают, а дают мигающий title или подсказку,
 * которой нет, — и обнаруживаются уже на живом сервере.
 */
class PromptSettingsTest {

    @Test
    @DisplayName("Пустой конфиг даёт рабочие значения по умолчанию")
    void поУмолчаниюВсёВключено() {
        PromptSettings settings = PromptSettings.defaultSettings();

        assertTrue(settings.titleEnabled());
        assertTrue(settings.actionBarEnabled());
        assertTrue(settings.chatEnabled());
        assertEquals(PromptSettings.DEFAULT_PREFIX, settings.prefix());
        assertEquals("&f", settings.textColor());

        PromptSettings.Prompt login = settings.prompts().get(PromptSettings.Stage.LOGIN);
        assertTrue(login.hasTitle());
        assertTrue(login.hasActionBar());
        assertTrue(login.hasChat());
    }

    @Test
    @DisplayName("Ступень выбирается по состоянию игрока, у вошедшего её нет")
    void ступеньПоСостоянию() {
        assertEquals(PromptSettings.Stage.LOGIN, PromptSettings.Stage.of(AuthStatus.AWAITING_LOGIN));
        assertEquals(PromptSettings.Stage.REGISTER,
                PromptSettings.Stage.of(AuthStatus.AWAITING_REGISTRATION));
        assertEquals(PromptSettings.Stage.TOTP, PromptSettings.Stage.of(AuthStatus.AWAITING_TOTP));
        assertEquals(PromptSettings.Stage.NEW_PASSWORD,
                PromptSettings.Stage.of(AuthStatus.AWAITING_NEW_PASSWORD));

        // Вошедшему показывать нечего — ни в одном из четырёх видов входа.
        assertNull(PromptSettings.Stage.of(AuthStatus.AUTHENTICATED));
        assertNull(PromptSettings.Stage.of(AuthStatus.AUTHENTICATED_BY_SESSION));
        assertNull(PromptSettings.Stage.of(AuthStatus.AUTHENTICATED_BY_PREMIUM));
        assertNull(PromptSettings.Stage.of(AuthStatus.AUTHENTICATED_BY_BYPASS));
    }

    @Test
    @DisplayName("Пустая строка — это «не показывать», а не «взять значение по умолчанию»")
    void пустаяСтрокаВыключаетЧасть() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("prompt.login.title", "");
        raw.put("prompt.login.subtitle", "");
        raw.put("prompt.login.action-bar", "");

        PromptSettings.Prompt login =
                PromptSettings.fromMap(raw).prompts().get(PromptSettings.Stage.LOGIN);

        assertFalse(login.hasTitle());
        assertFalse(login.hasActionBar());
        // Не тронутая часть осталась своей, а не пропала заодно.
        assertTrue(login.hasChat());
    }

    @Test
    @DisplayName("Title, гаснущий раньше следующего повтора, подтягивается до периода")
    void времяПоказаНеМеньшеПериодаПовтора() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("prompt.title.stay-ms", 1000);
        raw.put("prompt.title.fade-out-ms", 400);
        raw.put("prompt.repeat-seconds", 10);

        PromptSettings settings = PromptSettings.fromMap(raw);

        // Иначе девять секунд из десяти экран был бы пустым, и подсказка
        // выглядела бы как мигающая ошибка.
        assertEquals(Duration.ofMillis(10_400), settings.stay());
    }

    @Test
    @DisplayName("Без повтора время показа остаётся ровно таким, как написано")
    void безПовтораВремяПоказаНеТрогаем() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("prompt.title.stay-ms", 1000);
        raw.put("prompt.repeat-seconds", 0);

        PromptSettings settings = PromptSettings.fromMap(raw);

        assertEquals(Duration.ofMillis(1000), settings.stay());
        assertTrue(settings.repeat().isZero());
    }

    @Test
    @DisplayName("Числа вне разумного зажимаются, а не принимаются молча")
    void числаЗажимаются() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("prompt.title.fade-in-ms", -100);
        raw.put("prompt.title.stay-ms", 10_000_000);
        raw.put("prompt.repeat-seconds", 9999);
        raw.put("prompt.chat-reminder-seconds", -5);

        PromptSettings settings = PromptSettings.fromMap(raw);

        assertEquals(Duration.ZERO, settings.fadeIn());
        assertEquals(Duration.ofSeconds(60), settings.repeat());
        assertEquals(Duration.ZERO, settings.chatReminder());
        // Верхняя граница показа — минута, но период повтора её подтянул выше:
        // повтор раз в минуту с показом на минуту как раз и даёт непрерывность.
        assertEquals(Duration.ofSeconds(60).plus(settings.fadeOut()), settings.stay());
    }

    @Test
    @DisplayName("Цвет текста: принимается только один код, всё прочее — белый")
    void цветТекстаПроверяется() {
        assertEquals("&e", PromptSettings.colorCode("&e"));
        assertEquals("&e", PromptSettings.colorCode("e"));
        assertEquals("&a", PromptSettings.colorCode("  &A  "));

        // Опечатка в конфиге не должна раскрашивать сообщения мусором.
        assertEquals("&f", PromptSettings.colorCode("&"));
        assertEquals("&f", PromptSettings.colorCode("&z"));
        assertEquals("&f", PromptSettings.colorCode("&ejail"));
        assertEquals("&f", PromptSettings.colorCode(null));
        // Форматирование без цвета — тоже не цвет: &l покрасить нечем.
        assertEquals("&f", PromptSettings.colorCode("&l"));
    }

    @Test
    @DisplayName("Плейсхолдеры в текстах подставляются теми же правилами, что и в сообщениях")
    void плейсхолдерыПодставляются() {
        PromptSettings.Prompt register =
                PromptSettings.defaultSettings().prompts().get(PromptSettings.Stage.REGISTER);

        String chat = MessageSettings.apply(register.chat(), Map.of("player", "Steve"));

        assertTrue(chat.contains("Steve"), chat);
        assertFalse(chat.contains("{player}"), chat);
    }

    @Test
    @DisplayName("Свой текст ступени заменяет только её собственный, остальные не трогает")
    void свойТекстНеЗатираетСоседние() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("prompt.totp.title", "&5КОД");

        PromptSettings settings = PromptSettings.fromMap(raw);

        assertEquals("&5КОД", settings.prompts().get(PromptSettings.Stage.TOTP).title());
        assertEquals("&c&lВОЙДИТЕ", settings.prompts().get(PromptSettings.Stage.LOGIN).title());
    }
}
