package ovh.aurumgg.companion.paper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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

    private final Plugin plugin;
    private final TicketClient client;
    private final TicketCooldown cooldown;
    private final boolean enabled;

    public TicketCommand(Plugin plugin, TicketClient client, TicketCooldown cooldown, boolean enabled) {
        this.plugin = plugin;
        this.client = client;
        this.cooldown = cooldown;
        this.enabled = enabled;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна только игрокам.");
            return true;
        }
        if (!enabled) {
            player.sendMessage(ChatColor.RED + "Тикеты не настроены на этом сервере.");
            return true;
        }
        if (args.length == 0) {
            // Подсказка говорит и как набрать, и что произойдёт: иначе половина
            // игроков пишет второй тикет, решив, что первый никуда не ушёл.
            player.sendMessage(ChatColor.YELLOW + "/" + label
                    + " <сообщение> — написать администрации; ответ придёт сюда же, в чат");
            return true;
        }

        String text = String.join(" ", args).strip();
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }

        long wait = cooldown.secondsRemaining(player.getUniqueId());
        if (wait > 0) {
            player.sendMessage(ChatColor.YELLOW + "Подожди ещё " + wait + " с перед следующим сообщением.");
            return true;
        }

        final String message = text;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String reply;
            try {
                TicketClient.Result result = client.send(player.getUniqueId(), player.getName(), message);
                reply = result.created()
                        ? ChatColor.GREEN + "Тикет создан. Администрация ответит здесь же, в чате."
                        : ChatColor.GREEN + "Сообщение добавлено к твоему тикету.";
            } catch (TicketClient.TicketException e) {
                plugin.getLogger().warning("Не удалось отправить тикет: " + e.getMessage());
                reply = ChatColor.RED + "Не получилось отправить — попробуй позже.";
            }
            final String finalReply = reply;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.sendMessage(finalReply);
            });
        });
        return true;
    }
}
