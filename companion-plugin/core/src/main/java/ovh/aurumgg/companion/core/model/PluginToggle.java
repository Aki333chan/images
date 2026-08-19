package ovh.aurumgg.companion.core.model;

/**
 * Результат горячего включения или выключения плагина.
 *
 * Отдельный тип, а не boolean: причина отказа здесь важнее самого факта.
 * «Плагина нет на сервере» и «плагин упал при включении» требуют от человека
 * разных действий, и схлопывать их в false значит отнять у него эту разницу.
 */
public record PluginToggle(boolean ok, String error, boolean enabled) {

    public static PluginToggle ok(boolean enabled) {
        return new PluginToggle(true, null, enabled);
    }

    public static PluginToggle failed(String error) {
        return new PluginToggle(false, error, false);
    }
}
