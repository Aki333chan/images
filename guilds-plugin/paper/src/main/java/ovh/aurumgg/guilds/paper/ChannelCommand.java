package ovh.aurumgg.guilds.paper;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.PartyService;

/**
 * Приватные каналы: /p — пати, /g — гильдия.
 *
 * <h2>Строго между своими</h2>
 *
 * Сообщение уходит ровно участникам группы и больше никому: ни в общий чат, ни
 * в консоль, ни администраторам. Событие чата при этом не используется вовсе —
 * текст рассылается напрямую, — поэтому его не увидят и плагины-логгеры,
 * подписанные на чат.
 *
 * <h2>Одна реализация на два канала</h2>
 *
 * Каналы отличаются только тем, кому рассылать и каким цветом. Разводить их на
 * два почти одинаковых класса значило бы чинить каждую правку дважды.
 */
final class ChannelCommand implements CommandExecutor {

    /** Какой это канал. */
    enum Channel {
        PARTY("&b[Пати]", "&bПати"),
        GUILD("&a[Гильдия]", "&aГильдия");

        private final String prefix;
        private final String name;

        Channel(String prefix, String name) {
            this.prefix = prefix;
            this.name = name;
        }
    }

    private final Channel channel;
    private final GuildService guilds;
    private final PartyService parties;

    ChannelCommand(Channel channel, GuildService guilds, PartyService parties) {
        this.channel = channel;
        this.guilds = guilds;
        this.parties = parties;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "Этот чат — для игроков в игре");
            return true;
        }
        if (args.length == 0) {
            Msg.send(player, "Использование: /" + label + " <сообщение>");
            return true;
        }

        List<UUID> recipients = recipients(player);
        if (recipients.isEmpty()) {
            Msg.send(player, channel == Channel.PARTY
                    ? "Вы не в пати" : "Вы не состоите в гильдии");
            return true;
        }

        String text = String.join(" ", args);
        // Цветные коды из пользовательского текста НЕ разбираются: иначе любой
        // участник смог бы подделать сообщение под системное или сделать его
        // невидимым цветом фона.
        var message = Msg.colored(channel.prefix + " &f" + player.getName() + "&7: &f")
                .append(net.kyori.adventure.text.Component.text(text));

        int delivered = 0;
        for (UUID uuid : recipients) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null) continue;
            member.sendMessage(message);
            delivered++;
        }
        if (delivered <= 1) {
            // Иначе человек пишет в пустоту и не понимает, почему ему не
            // отвечают.
            Msg.send(player, "Кроме вас, из группы сейчас никого нет в сети");
        }
        return true;
    }

    private List<UUID> recipients(Player player) {
        if (channel == Channel.PARTY) return parties.members(player.getUniqueId());
        return guilds.guildOf(player.getUniqueId())
                .map(guild -> guilds.memberUuids(guild.id()))
                .orElse(List.of());
    }
}
