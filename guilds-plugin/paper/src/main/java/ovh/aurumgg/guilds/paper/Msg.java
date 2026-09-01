package ovh.aurumgg.guilds.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * Сообщения плагина: общий префикс и цвет.
 *
 * Цвета задаются кодами вида &amp;a — так их пишут в конфигах почти всех
 * плагинов. Сам текст сообщения в цветные коды НЕ разбирается: сюда попадают и
 * ники, и имена гильдий, то есть данные, которые вводил игрок. Разбирать в них
 * «&amp;» значило бы дать любому желающему раскрашивать служебные сообщения, а
 * заодно и подделывать их под чужие.
 */
final class Msg {

    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    private static final Component PREFIX = COLORS.deserialize("&6[&eГильдии&6]&r ");

    private Msg() {}

    static Component of(String text) {
        return PREFIX.append(Component.text(text).color(NamedTextColor.WHITE));
    }

    static Component ok(String text) {
        return PREFIX.append(Component.text(text).color(NamedTextColor.GREEN));
    }

    static Component fail(String text) {
        return PREFIX.append(Component.text(text).color(NamedTextColor.RED));
    }

    /** Строка с цветными кодами — для настраиваемых текстов и заголовков. */
    static Component colored(String text) {
        return COLORS.deserialize(text);
    }

    /**
     * Подсказка по команде: как набирать и что она делает.
     *
     * Без префикса плагина и тем же видом, что строка в {@code /guild help},
     * — человек, ошибившийся аргументом, должен увидеть ровно ту строку,
     * которую уже видел в справке, а не другую формулировку того же самого.
     */
    static void usage(CommandSender to, String command, String description) {
        to.sendMessage(colored(ovh.aurumgg.guilds.core.HelpBook.line(command, description)));
    }

    /** Несколько строк справки подряд — без префикса на каждой. */
    static void lines(CommandSender to, java.util.List<String> lines) {
        for (String line : lines) to.sendMessage(colored(line));
    }

    static void send(CommandSender to, String text) {
        to.sendMessage(of(text));
    }

    /** Ответ команды: зелёный при успехе, красный при отказе. */
    static void result(CommandSender to, ovh.aurumgg.guilds.api.GuildActionResult result) {
        to.sendMessage(result.ok() ? ok(result.message()) : fail(result.message()));
    }
}
