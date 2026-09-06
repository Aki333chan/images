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

    /**
     * Встроенный префикс: им подписаны сообщения до загрузки языка и в тестах.
     * Обычно вместо него берётся ключ {@code prefix} из файла языка.
     */
    private static final String DEFAULT_PREFIX = "&6[&eГильдии&6]&r ";

    /**
     * Плагин, у которого спрашивают тексты.
     *
     * static и volatile по той же причине, что и префикс: Msg зовут из
     * десятка мест, в том числе из обработчиков команд, которым плагин не
     * передан, а /guild admin reload меняет тексты на живом сервере.
     */
    private static volatile AurumGuildsPlugin plugin;

    static void use(AurumGuildsPlugin owner) {
        plugin = owner;
    }

    /** Текст по ключу на языке сервера. */
    static String text(String key) {
        AurumGuildsPlugin owner = plugin;
        return owner == null ? key : owner.text(key);
    }

    static String text(String key, java.util.Map<String, String> values) {
        AurumGuildsPlugin owner = plugin;
        return owner == null ? key : owner.text(key, values);
    }

    /** Несколько строк по одному ключу — для пояснений в меню. */
    static java.util.List<String> lines(String key, java.util.Map<String, String> values) {
        AurumGuildsPlugin owner = plugin;
        return owner == null ? java.util.List.of(key) : owner.lines(key, values);
    }

    /** Подписи сайдбара и словарь для строк, которые собираются из чисел. */
    static ovh.aurumgg.guilds.core.HudLines.Labels hudLabels() {
        AurumGuildsPlugin owner = plugin;
        return owner == null ? ovh.aurumgg.guilds.core.HudLines.RU : owner.hudLabels();
    }

    /**
     * Локаль сервера — для дат и чисел, которые форматирует сама Java.
     *
     * Не для текстов: их берут по ключу. Здесь нужна ровно там, где формат
     * определяется не словарём, а языком, — «6 сент. 2026» против «Sep 6, 2026».
     */
    static java.util.Locale locale() {
        AurumGuildsPlugin owner = plugin;
        return owner == null ? java.util.Locale.forLanguageTag("ru") : owner.locale();
    }

    /** Подписи справки — чтобы их не собирал каждый вызов сам. */
    static ovh.aurumgg.guilds.core.HelpBook.Labels helpLabels() {
        AurumGuildsPlugin owner = plugin;
        return owner == null
                ? ovh.aurumgg.guilds.core.HelpBook.Labels.DEFAULT
                : owner.helpLabels();
    }

    /**
     * Текст результата действия на языке сервера.
     *
     * Подстановки-ключи ({@code keyKeys}) переводятся ПЕРЕД тем, как попасть в
     * шаблон: «{player} теперь {rank}» ждёт на месте {rank} слово, а не
     * «mc.rank.officer». Ники и имена гильдий из {@code values} при этом
     * остаются как есть — искать их в словаре нельзя, иначе игрок с ником
     * вроде «hud.bank» увидел бы вместо него «Банк».
     */
    static String render(ovh.aurumgg.guilds.api.GuildActionResult result) {
        AurumGuildsPlugin owner = plugin;
        // До onEnable плагина ещё нет — тогда показываем ключ, а не падаем:
        // сообщение в этот момент всё равно некому читать.
        if (owner == null) return result.messageKey();
        return owner.text(result.messageKey(), merge(owner, result));
    }

    private static java.util.Map<String, String> merge(
            AurumGuildsPlugin owner, ovh.aurumgg.guilds.api.GuildActionResult result) {
        if (result.keyKeys().isEmpty()) return result.values();
        java.util.Map<String, String> all = new java.util.HashMap<>(result.values());
        result.keyKeys().forEach((name, key) -> all.put(name, owner.text(key)));
        return all;
    }

    private Msg() {}

    private static Component prefix() {
        AurumGuildsPlugin owner = plugin;
        return COLORS.deserialize(owner == null ? DEFAULT_PREFIX : owner.text("prefix"));
    }

    static Component of(String text) {
        return prefix().append(Component.text(text).color(NamedTextColor.WHITE));
    }

    static Component ok(String text) {
        return prefix().append(Component.text(text).color(NamedTextColor.GREEN));
    }

    static Component fail(String text) {
        return prefix().append(Component.text(text).color(NamedTextColor.RED));
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
        String text = render(result);
        to.sendMessage(result.ok() ? ok(text) : fail(text));
    }
}
