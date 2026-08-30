package ovh.aurumgg.guilds.api;

/**
 * Чем закончилось действие с гильдией.
 *
 * Текст сообщения формируется рядом с условием, при котором он возникает, — и
 * поэтому проверяется тестом. Слой Bukkit и мост к панели занимаются только
 * доставкой этого текста.
 *
 * @param ok      получилось ли
 * @param message что показать человеку
 */
public record GuildActionResult(boolean ok, String message) {

    public static GuildActionResult ok(String message) {
        return new GuildActionResult(true, message);
    }

    public static GuildActionResult fail(String message) {
        return new GuildActionResult(false, message);
    }
}
