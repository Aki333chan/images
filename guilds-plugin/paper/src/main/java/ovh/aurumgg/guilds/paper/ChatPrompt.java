package ovh.aurumgg.guilds.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * «Напишите новый текст в чат» — ввод длинных значений из меню.
 *
 * <h2>Почему через чат, а не через книгу или знак</h2>
 *
 * Описание гильдии — это строка на сто с лишним символов, и вводить её в
 * табличке из четырёх строк неудобно. Книга умеет больше, но требует выдать
 * игроку предмет и потом забрать его обратно, а любой сбой посередине
 * оставляет мусор в инвентаре. Чат ничего не выдаёт и не забирает.
 *
 * <h2>Сообщение не уходит в общий чат</h2>
 *
 * Событие отменяется на LOWEST — раньше, чем до него доберётся плагин чата.
 * Иначе описание гильдии успело бы разойтись по серверу до того, как мы его
 * перехватили: отменять сообщение, которое уже разослали, поздно.
 */
final class ChatPrompt implements Listener {

    /** Что делать с введённым текстом. */
    private record Pending(String hint, BiConsumer<Player, String> action) {}

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Спросить у игрока текст.
     *
     * @param hint   что попросить написать
     * @param action что сделать с ответом; вызывается в главном потоке
     */
    void ask(Player player, String hint, BiConsumer<Player, String> action) {
        pending.put(player.getUniqueId(), new Pending(hint, action));
        // Инвентарь закрываем сами: чат за открытым меню игроку не виден.
        player.closeInventory();
        Msg.send(player, hint);
        Msg.send(player, "Напишите в чат новое значение или «отмена», чтобы передумать.");
    }

    boolean isWaiting(UUID player) {
        return pending.containsKey(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending waiting = pending.remove(event.getPlayer().getUniqueId());
        if (waiting == null) return;

        event.setCancelled(true);
        String text = PLAIN.serialize(event.message()).trim();
        Player player = event.getPlayer();

        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            sync(() -> Msg.send(player, "Отменено"));
            return;
        }
        // Обработчик получает управление в ГЛАВНОМ потоке: событие чата
        // асинхронное, а дальше почти наверняка пойдут обращения к Bukkit.
        sync(() -> waiting.action().accept(player, text));
    }

    /** Игрок вышел, не ответив, — иначе запись висела бы до перезапуска. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, action);
    }
}
