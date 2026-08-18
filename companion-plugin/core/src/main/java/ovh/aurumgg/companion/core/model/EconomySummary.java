package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Экономика сервера целиком: сколько всего денег и у кого больше всех.
 *
 * Считается обходом ВСЕХ, кто когда-либо заходил (Bukkit.getOfflinePlayers),
 * а не только тех, кто онлайн: деньги никуда не деваются, когда игрок вышел.
 * Операция не бесплатная, поэтому панель её кэширует.
 *
 * @param total          сумма балансов
 * @param playersCounted сколько игроков учтено
 * @param top            доска богатства, уже отсортированная по убыванию
 */
public record EconomySummary(
        double total, String totalFormatted, String currency, int playersCounted, List<TopEntry> top) {

    /** Строка доски богатства. */
    public record TopEntry(String name, String uuid, double balance, String formatted) {}
}
