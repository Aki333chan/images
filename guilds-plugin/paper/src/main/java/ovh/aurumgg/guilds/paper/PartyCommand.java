package ovh.aurumgg.guilds.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.PartyView;
import ovh.aurumgg.guilds.core.HelpBook;
import ovh.aurumgg.guilds.core.PartyService;

/**
 * /party — временные группы.
 *
 * <pre>
 * /party create           создать
 * /party invite &lt;ник&gt;     позвать (может любой участник)
 * /party accept [ник]     принять приглашение
 * /party leave            выйти
 * /party kick &lt;ник&gt;       выгнать (только лидер)
 * /party promote &lt;ник&gt;    передать лидерство (только лидер)
 * /party list             кто в пати
 * </pre>
 *
 * Все правила — в {@link PartyService}, здесь только разбор аргументов и
 * рассылка сообщений остальным участникам. Разделение не формальное: правила
 * проверяются тестами, а этот класс без запущенного сервера не проверить.
 */
final class PartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "invite", "accept", "leave", "kick", "promote", "list", "help");

    private final PartyService parties;

    PartyCommand(PartyService parties) {
        this.parties = parties;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "Пати собирают в игре, из консоли этого не сделать");
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create", "создать" -> {
                GuildActionResult result = parties.create(player.getUniqueId());
                Msg.result(player, result);
            }
            case "invite", "позвать" -> invite(player, args);
            case "accept", "принять" -> accept(player, args);
            case "leave", "выйти" -> leave(player);
            case "kick", "выгнать" -> kick(player, args);
            case "promote", "лидер" -> promote(player, args);
            case "list", "список" -> list(player);
            case "help", "помощь", "?" -> usage(player);
            default -> {
                Msg.send(player, "Нет такой команды: " + args[0]);
                usage(player);
            }
        }
        return true;
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /party invite <ник>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // В пати зовут того, с кем сейчас играют. Приглашать офлайн-игрока
            // бессмысленно: приглашение истечёт раньше, чем он зайдёт.
            Msg.send(player, "Игрока " + args[1] + " нет в сети");
            return;
        }

        GuildActionResult result = parties.invite(player.getUniqueId(), target.getUniqueId());
        Msg.result(player, result);
        if (result.ok()) {
            target.sendMessage(Msg.ok(player.getName() + " зовёт вас в пати. Принять: /party accept "
                    + player.getName()));
        }
    }

    private void accept(Player player, String[] args) {
        UUID from = args.length >= 2 ? PlayerNames.uuidOf(args[1]) : null;
        GuildActionResult result = parties.accept(player.getUniqueId(), from);
        Msg.result(player, result);
        if (result.ok()) broadcast(player, player.getName() + " присоединился к пати", player);
    }

    private void leave(Player player) {
        // Сообщаем ОСТАВШИМСЯ, поэтому состав читаем до выхода, а рассылаем
        // после: иначе вышедший получил бы известие о собственном уходе, а
        // новый лидер не узнал бы, что он теперь лидер.
        List<Player> before = onlineMembers(player);
        GuildActionResult result = parties.leave(player.getUniqueId());
        Msg.result(player, result);
        if (!result.ok()) return;

        for (Player member : before) {
            if (member.equals(player)) continue;
            member.sendMessage(Msg.of(player.getName() + " вышел из пати"));
        }
        announceLeader(player);
    }

    private void kick(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /party kick <ник>");
            return;
        }
        UUID target = PlayerNames.uuidOf(args[1]);
        List<Player> before = onlineMembers(player);

        GuildActionResult result = parties.kick(player.getUniqueId(), target);
        Msg.result(player, result);
        if (!result.ok()) return;

        for (Player member : before) {
            if (member.getUniqueId().equals(target)) {
                member.sendMessage(Msg.fail("Вас выгнали из пати"));
            } else if (!member.equals(player)) {
                member.sendMessage(Msg.of(args[1] + " выгнан из пати"));
            }
        }
    }

    private void promote(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /party promote <ник>");
            return;
        }
        GuildActionResult result =
                parties.promote(player.getUniqueId(), PlayerNames.uuidOf(args[1]));
        Msg.result(player, result);
        if (result.ok()) broadcast(player, args[1] + " теперь лидер пати", null);
    }

    private void list(Player player) {
        PartyView view = parties.view(player.getUniqueId()).orElse(null);
        if (view == null) {
            Msg.send(player, "Вы не в пати");
            return;
        }
        Msg.send(player, "В пати " + view.size() + " из " + parties.maxMembers() + ":");
        for (UUID uuid : view.members()) {
            Player member = Bukkit.getPlayer(uuid);
            boolean online = member != null;
            String name = online ? member.getName() : "офлайн";
            player.sendMessage(Msg.colored((online ? "&f" : "&8") + "  " + name
                    + (uuid.equals(view.leader()) ? " &6★" : "")));
        }
    }

    /**
     * Справка: строка на команду.
     *
     * Команд у пати немного, и все влезают на одну страницу — листать нечего,
     * но формат тот же, что и у гильдий: набирать одно, читать другое.
     */
    private void usage(Player player) {
        List<String> lines = HelpBook.titled("Пати", "/party help")
                .add("/party create", "собрать пати, стать её лидером")
                .add("/party invite <ник>", "позвать игрока; приглашение живёт недолго")
                .add("/party accept [ник]", "принять приглашение")
                .add("/party list", "кто сейчас в пати и сколько у кого здоровья")
                .add("/party kick <ник>", "выгнать участника (лидер)")
                .add("/party promote <ник>", "передать лидерство другому")
                .add("/party leave", "выйти из пати; лидер уходит — пати достаётся следующему")
                .add("/p <сообщение>", "написать в чат пати, видят только свои")
                .build()
                .page(1);
        for (String line : lines) player.sendMessage(Msg.colored(line));
    }

    /** Всем в пати, кроме исключённого. */
    private void broadcast(Player player, String text, Player except) {
        for (Player member : onlineMembers(player)) {
            if (member.equals(except)) continue;
            member.sendMessage(Msg.of(text));
        }
    }

    /** Кому сообщили, что он теперь лидер. */
    private void announceLeader(Player former) {
        parties.view(former.getUniqueId()).ifPresent(view -> {
            Player leader = Bukkit.getPlayer(view.leader());
            if (leader != null) leader.sendMessage(Msg.ok("Вы теперь лидер пати"));
        });
    }

    private List<Player> onlineMembers(Player player) {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : parties.members(player.getUniqueId())) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) result.add(member);
        }
        return result;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefixed(SUBCOMMANDS, args[0]);
        if (args.length == 2 && List.of("invite", "kick", "promote", "accept")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return prefixed(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
