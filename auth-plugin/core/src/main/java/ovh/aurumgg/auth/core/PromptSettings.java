package ovh.aurumgg.auth.core;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import ovh.aurumgg.auth.api.AuthStatus;

/**
 * Как выглядит просьба войти или зарегистрироваться.
 *
 * <h2>Зачем это вообще понадобилось</h2>
 *
 * Подсказка в чате не работает. EssentialsX показывает свой MOTD сразу после
 * входа игрока — и делает это НЕ событием, а прямой отправкой текста
 * (motdFlow → TextPager.showPage(..., user.getSource()) в его исходниках).
 * Перехватить сообщение, отправленное игроку, в Bukkit нечем: события «игроку
 * пришёл текст» не существует. Значит, чужой MOTD в несколько строк неизбежно
 * вытолкнет нашу единственную строчку вверх, и человек её не увидит.
 *
 * Вывод, из которого выросла эта настройка: бороться за место в чате
 * бессмысленно, подсказку нужно показывать там, откуда её нечем вытеснить.
 * Это title на весь экран и строка над горячей панелью — их не сдвинет ни MOTD,
 * ни разговор в чате, ни что-либо ещё. Чат остаётся, но уже как третий,
 * вспомогательный канал, и повторяется, чтобы не потеряться.
 *
 * <h2>Что здесь настраивается</h2>
 *
 * Каждая из четырёх ступеней входа (обычный вход, регистрация, код
 * двухфакторки, новый пароль после сброса) имеет свой набор текстов: title,
 * подзаголовок, строка над горячей панелью и строка в чат. Пустая строка
 * означает «эту часть не показывать» — так можно, например, оставить только
 * title, не трогая переключатели.
 *
 * Разбор живёт в core и покрыт тестами: он состоит из зажимания чисел и
 * подстановки значений по умолчанию, то есть ровно из того кода, который
 * ломается молча и обнаруживается уже на живом сервере.
 *
 * @param prefix            общий префикс сообщений плагина, цветными кодами
 * @param textColor         цвет остального текста сообщений плагина
 * @param titleEnabled      показывать title на весь экран
 * @param fadeIn            появление title
 * @param stay              сколько title висит
 * @param fadeOut           исчезновение title
 * @param actionBarEnabled  показывать строку над горячей панелью
 * @param chatEnabled       показывать строку в чате
 * @param repeat            как часто повторять title и строку над панелью, 0 — не повторять
 * @param chatReminder      как часто повторять строку в чате, 0 — только один раз
 * @param prompts           тексты по ступеням входа
 */
public record PromptSettings(
        String prefix,
        String textColor,
        boolean titleEnabled,
        Duration fadeIn,
        Duration stay,
        Duration fadeOut,
        boolean actionBarEnabled,
        boolean chatEnabled,
        Duration repeat,
        Duration chatReminder,
        Map<Stage, Prompt> prompts) {

    /** Ступень входа: на каждой человеку нужно своё указание. */
    public enum Stage {
        LOGIN("login"),
        REGISTER("register"),
        TOTP("totp"),
        NEW_PASSWORD("new-password");

        private final String key;

        Stage(String key) {
            this.key = key;
        }

        /** Имя раздела в config.yml. */
        public String key() {
            return key;
        }

        /**
         * Ступень по состоянию игрока. null — показывать нечего: игрок уже вошёл.
         */
        public static Stage of(AuthStatus status) {
            if (status == null) return LOGIN;
            return switch (status) {
                case AWAITING_REGISTRATION -> REGISTER;
                case AWAITING_TOTP -> TOTP;
                case AWAITING_NEW_PASSWORD -> NEW_PASSWORD;
                case AWAITING_LOGIN -> LOGIN;
                default -> null;
            };
        }
    }

    /**
     * Тексты одной ступени.
     *
     * @param title     крупная надпись на весь экран
     * @param subtitle  строка под ней
     * @param actionBar строка над горячей панелью
     * @param chat      строка в чат
     */
    public record Prompt(String title, String subtitle, String actionBar, String chat) {

        /** Пустая строка — осмысленное значение: «эту часть не показывать». */
        public boolean hasTitle() {
            return notBlank(title) || notBlank(subtitle);
        }

        public boolean hasActionBar() {
            return notBlank(actionBar);
        }

        public boolean hasChat() {
            return notBlank(chat);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public static final String DEFAULT_PREFIX = "&6[&eВход&6]&r ";
    public static final String DEFAULT_TEXT_COLOR = "&f";

    /**
     * Тексты по умолчанию.
     *
     * Заметность здесь — не украшательство, а вся суть: подсказка, которую
     * видно, отличается от подсказки, которой нет, ровно на количество людей,
     * ушедших с сервера, ничего не поняв. Отсюда крупный цветной title,
     * стрелки в строке над панелью и цвет вместо белого текста по умолчанию.
     *
     * Плейсхолдеры: {player} — ник, {seconds} — сколько секунд осталось до
     * кика, {online}, {max} — сколько игроков сейчас и сколько вмещается.
     */
    static final Map<Stage, Prompt> DEFAULTS = defaults();

    private static Map<Stage, Prompt> defaults() {
        Map<Stage, Prompt> map = new EnumMap<>(Stage.class);
        map.put(Stage.LOGIN, new Prompt(
                "&c&lВОЙДИТЕ",
                "&fнапишите в чат &e/login <пароль>",
                "&e» &fнапишите &e/login <пароль> &7({seconds} с) &e«",
                "&7Этот ник уже зарегистрирован. &aВойдите: &f/login <пароль>"));
        map.put(Stage.REGISTER, new Prompt(
                "&a&lРЕГИСТРАЦИЯ",
                "&fнапишите в чат &e/register <пароль> <пароль>",
                "&e» &fнапишите &e/register <пароль> <пароль> &7({seconds} с) &e«",
                "&aДобро пожаловать, &f{player}&a! Зарегистрируйтесь: "
                        + "&f/register <пароль> <пароль ещё раз>"));
        map.put(Stage.TOTP, new Prompt(
                "&6&lКОД ИЗ ПРИЛОЖЕНИЯ",
                "&fнапишите в чат &e/2fa <код>",
                "&e» &fнапишите &e/2fa <код> &7({seconds} с) &e«",
                "&6Остался код двухфакторки: &f/2fa <код>"));
        map.put(Stage.NEW_PASSWORD, new Prompt(
                "&b&lНОВЫЙ ПАРОЛЬ",
                "&fнапишите в чат &e/reset <пароль> <пароль>",
                "&e» &fнапишите &e/reset <пароль> <пароль> &7({seconds} с) &e«",
                "&bПридумайте новый пароль: &f/reset <пароль> <пароль ещё раз>"));
        return Map.copyOf(map);
    }

    public static PromptSettings fromMap(Map<String, Object> raw) {
        Duration fadeIn = millis(clamp(integer(raw, "prompt.title.fade-in-ms", 200), 0, 5_000));
        Duration fadeOut = millis(clamp(integer(raw, "prompt.title.fade-out-ms", 400), 0, 5_000));
        Duration stay = millis(clamp(integer(raw, "prompt.title.stay-ms", 6_000), 500, 60_000));
        // Повтор: 0 — «показать один раз». Верхняя граница — минута: реже, чем
        // раз в минуту, напоминание уже не напоминание, а таймаут входа всё
        // равно не длиннее пяти минут.
        Duration repeat = seconds(clamp(integer(raw, "prompt.repeat-seconds", 4), 0, 60));
        Duration chatReminder = seconds(clamp(integer(raw, "prompt.chat-reminder-seconds", 15), 0, 300));

        // Title гаснет через stay и снова появляется только на следующем
        // повторе — в промежутке экран пустой, и подсказка мигает. Поэтому
        // время показа подтягивается до периода повтора, а не оставляется
        // как написано: настройка «висеть секунду, повторять раз в десять»
        // почти наверняка опечатка, а не замысел.
        Duration minimumStay = repeat.plus(fadeOut);
        if (!repeat.isZero() && stay.compareTo(minimumStay) < 0) stay = minimumStay;

        Map<Stage, Prompt> prompts = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            Prompt fallback = DEFAULTS.get(stage);
            String base = "prompt." + stage.key() + ".";
            prompts.put(stage, new Prompt(
                    text(raw, base + "title", fallback.title()),
                    text(raw, base + "subtitle", fallback.subtitle()),
                    text(raw, base + "action-bar", fallback.actionBar()),
                    text(raw, base + "chat", fallback.chat())));
        }

        return new PromptSettings(
                text(raw, "prompt.prefix", DEFAULT_PREFIX),
                colorCode(text(raw, "prompt.text-color", DEFAULT_TEXT_COLOR)),
                bool(raw, "prompt.title.enabled", true),
                fadeIn,
                stay,
                fadeOut,
                bool(raw, "prompt.action-bar", true),
                bool(raw, "prompt.chat", true),
                repeat,
                chatReminder,
                Map.copyOf(prompts));
    }

    /** Настройки по умолчанию — для тестов и на случай пустого конфига. */
    public static PromptSettings defaultSettings() {
        return fromMap(Map.of());
    }

    /**
     * Цвет текста сообщений плагина.
     *
     * Принимается только код вида &amp;e — один цвет и ничего больше. Причина
     * не в лени: сюда подставляется префикс к произвольным сообщениям плагина,
     * и разреши мы здесь любую строку, опечатка вроде «&amp;» без буквы
     * превратила бы каждое сообщение о входе в мусор. Негодное значение
     * заменяется на белый, а не отбрасывает весь конфиг.
     */
    static String colorCode(String raw) {
        if (raw == null) return DEFAULT_TEXT_COLOR;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 1) value = "&" + value;
        return value.matches("&[0-9a-f]") ? value : DEFAULT_TEXT_COLOR;
    }

    private static String text(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        // Пустая строка — осмысленное значение: «эту часть не показывать».
        // Поэтому на значение по умолчанию она НЕ заменяется, в отличие от
        // отсутствующего ключа.
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Duration millis(int value) {
        return Duration.ofMillis(value);
    }

    private static Duration seconds(int value) {
        return Duration.ofSeconds(value);
    }
}
