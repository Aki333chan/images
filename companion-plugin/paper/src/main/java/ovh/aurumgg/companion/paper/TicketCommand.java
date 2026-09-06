package ovh.aurumgg.companion.paper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.Map;
import ovh.aurumgg.companion.core.ticket.TicketClient;
import ovh.aurumgg.companion.core.ticket.TicketCooldown;

/**
 * /ticket <сообщение> — игрок пишет администрации.
 *
 * Сетевой вызов уходит в асинхронный поток: подвесить основной поток на время
 * HTTP-запроса нельзя, иначе лагает весь сервер. Ответ игроку отправляется
 * обратно из основного потока.
 */
public final class TicketCommand implements CommandExecutor {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final AurumCompanionPlugin plugin;
    private final TicketClient client;
    private final TicketCooldown cooldown;
    private final boolean enabled;

    public TicketCommand(
            AurumCompanionPlugin plugin,
            TicketClient client,
            TicketCooldown cooldown,
            boolean enabled) {
        this.plugin = plugin;
        this.client = client;
        this.cooldown = cooldown;
        this.enabled = enabled;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.text("ticket.playersOnly"));
            return true;
        }
        if (!enabled) {
            player.sendMessage(ChatColor.RED + plugin.text("ticket.notConfigured"));
            return true;
        }
        if (args.length == 0) {
            // Подсказка говорит и как набрать, и что произойдёт: иначе половина
            // игроков пишет второй тикет, решив, что первый никуда не ушёл.
            player.sendMessage(ChatColor.YELLOW
                    + plugin.text("ticket.usage", Map.of("command", label)));
            return true;
        }

        String text = String.join(" ", args).strip();
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }

        long wait = cooldown.secondsRemaining(player.getUniqueId());
        if (wait > 0) {
            player.sendMessage(ChatColor.YELLOW + plugin.text("ticket.cooldown",
                    Map.of("seconds", String.valueOf(wait))));
            return true;
        }

        final String message = text;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String reply;
            try {
                TicketClient.Result result = client.send(player.getUniqueId(), player.getName(), message);
                reply = ChatColor.GREEN
                        + plugin.text(result.created() ? "ticket.created" : "ticket.appended");
            } catch (TicketClient.TicketException e) {
                plugin.getLogger().warning("Не удалось отправить тикет: " + e.getMessage());
                reply = ChatColor.RED + plugin.text("ticket.failed");
            }
            final String finalReply = reply;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.sendMessage(finalReply);
            });
        });
        return true;
    }
}
