package ovh.aurumgg.guilds.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.core.ArgWords;
import ovh.aurumgg.guilds.core.GuildRegion;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.HelpBook;
import ovh.aurumgg.guilds.core.HudLines;
import ovh.aurumgg.guilds.core.StoredGuild;

/**
 * /guild — постоянные объединения игроков.
 *
 * <h2>Права</h2>
 *
 * Своя простая проверка permission-нод, а не наш RBAC из панели: на уровне
 * игрового сервера RBAC панели не существует, и тянуть его сюда значило бы
 * связать плагин с панелью намертво. Нод ровно две:
 * {@code aurumguilds.create} (нужна, только если включено в конфиге) и
 * {@code aurumguilds.admin} на всё вмешательство извне.
 *
 * <h2>Роспуск с подтверждением</h2>
 *
 * {@code /guild disband} нужно ввести дважды. Это не формальность: команда
 * необратима, уносит состав и общак, а набирается в одну строку рядом с
 * безобидным {@code /guild list}.
 */
final class GuildCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION_CREATE = "aurumguilds.create";
    static final String PERMISSION_ADMIN = "aurumguilds.admin";

    /** Сколько действует подтверждение роспуска. */
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "invite", "join", "leave", "kick", "promote", "demote", "transfer",
            "disband", "info", "list", "settings", "tag", "bank", "bonuses", "claim", "help",
            "admin");

    private final Plugin plugin;
    private final GuildService guilds;
    private final GuildSettingsMenu menu;
    /** Мост к WorldGuard или null, если его на сервере нет. */
    private final WorldGuardBridge regions;
    /** Кто уже нажал «распустить» и до какого момента это засчитывается. */
    private final Map<UUID, Instant> pendingDisband = new ConcurrentHashMap<>();

    /** Псевдонимы перезагрузки — русский вариант наравне с английским. */
    private static final List<String> ADMIN_RELOAD = List.of("reload", "перезагрузить", "рл");

    private static final List<String> ADMIN_PARTY_FF =
            List.of("friendlyfire", "ff", "свойогонь");

    private static final List<String> ADMIN_BONUS = List.of("bonus", "бонус", "бонусы");

    private static final List<String> YES = List.of("on", "true", "yes", "вкл", "да");
    private static final List<String> NO = List.of("off", "false", "no", "выкл", "нет");

    GuildCommand(Plugin plugin, GuildService guilds, GuildSettingsMenu menu, WorldGuardBridge regions) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.menu = menu;
        this.regions = regions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, args);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // Административные команды и список гильдий доступны из консоли:
        // администратор чаще сидит в панели, чем в игре.
        if (sub.equals("admin")) {
            admin(sender, args);
            return true;
        }
        if (sub.equals("list") || sub.equals("список")) {
            list(sender, args);
            return true;
        }

        if (!(sender instanceof Player player)) {
            Msg.send(sender, Msg.text("guild.err.consoleLimited"));
            return true;
        }

        switch (sub) {
            case "create", "создать" -> create(player, args);
            case "invite", "позвать" -> invite(player, args);
            case "join", "вступить" -> join(player, args);
            case "leave", "выйти" -> reply(player, guilds.leave(player.getUniqueId()));
            case "kick", "выгнать" -> kick(player, args);
            case "promote", "повысить" -> setRank(player, args, GuildRank.OFFICER);
            case "demote", "понизить" -> setRank(player, args, GuildRank.MEMBER);
            case "transfer", "передать" -> transfer(player, args);
            case "disband", "распустить" -> disband(player);
            case "info", "инфо" -> info(player, args);
            case "settings", "настройки" -> menu.open(player);
            case "tag", "тег" -> tag(player, args);
            case "bank", "банк" -> bank(player, args);
            case "bonuses", "бонусы" -> bonuses(player);
            case "claim", "приват" -> claim(player, args);
            case "help", "помощь", "?" -> usage(player, args);
            default -> {
                Msg.send(player, Msg.text("guild.err.noSuchCommand",
                        Map.of("command", args[0])));
                usage(player, new String[] {"help"});
            }
        }
        return true;
    }

    // ------------------------------------------------------------ игроку

    private void create(Player player, String[] args) {
        if (guilds.config().requireCreatePermission() && !player.hasPermission(PERMISSION_CREATE)) {
            Msg.send(player, Msg.text("guild.err.createPermission"));
            return;
        }
        if (args.length < 3) {
            Msg.usage(player, Msg.text("help.guild.create.use"),
                    Msg.text("help.guild.create.what"));
            Msg.send(player, Msg.text("guild.tagHint",
                    Map.of("max", String.valueOf(guilds.config().maxTagLength()))));
            return;
        }
        // Имя может быть из нескольких слов, тег — всегда последний аргумент.
        String tag = args[args.length - 1];
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 1));
        reply(player, guilds.create(player.getUniqueId(), name, tag));
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            Msg.usage(player, Msg.text("help.guild.invite.use"),
                    Msg.text("help.guild.invite.what"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID uuid = target != null ? target.getUniqueId() : PlayerNames.uuidOf(args[1]);

        guilds.invite(player.getUniqueId(), uuid).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (result.ok() && target != null) {
                guilds.guildOf(player.getUniqueId()).ifPresent(guild -> target.sendMessage(
                        Msg.ok(Msg.text("guild.inviteReceived", Map.of(
                                "player", player.getName(), "guild", guild.name())))));
            }
        }));
    }

    private void join(Player player, String[] args) {
        String name = args.length >= 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : null;
        guilds.join(player.getUniqueId(), name).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (result.ok()) {
                announce(player.getUniqueId(),
                        Msg.text("guild.said.joined", Map.of("player", player.getName())), player);
                showMotd(player);
            }
        }));
    }

    private void kick(Player player, String[] args) {
        if (args.length < 2) {
            Msg.usage(player, Msg.text("help.guild.kick.use"), Msg.text("help.guild.kick.what"));
            return;
        }
        UUID target = PlayerNames.uuidOf(args[1]);
        // Состав читаем до исключения: после него исключённого в нём уже нет,
        // и сообщить остальным было бы некому.
        List<UUID> before = guilds.guildOf(player.getUniqueId())
                .map(guild -> guilds.memberUuids(guild.id())).orElse(List.of());

        guilds.kick(player.getUniqueId(), target).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (!result.ok()) return;
            for (UUID uuid : before) {
                Player member = Bukkit.getPlayer(uuid);
                if (member == null || member.equals(player)) continue;
                member.sendMessage(uuid.equals(target)
                        ? Msg.fail(Msg.text("guild.said.kickedYou"))
                        : Msg.of(Msg.text("guild.said.kicked", Map.of("player", args[1]))));
            }
        }));
    }

    private void setRank(Player player, String[] args, GuildRank rank) {
        if (args.length < 2) {
            String key = rank == GuildRank.OFFICER ? "help.guild.promote" : "help.guild.demote";
            Msg.usage(player, Msg.text(key + ".use"), Msg.text(key + ".what"));
            return;
        }
        reply(player, guilds.setRank(player.getUniqueId(), PlayerNames.uuidOf(args[1]), rank));
    }

    private void transfer(Player player, String[] args) {
        if (args.length < 2) {
            Msg.usage(player, Msg.text("help.guild.transfer.use"),
                    Msg.text("help.guild.transfer.what"));
            return;
        }
        guilds.transfer(player.getUniqueId(), PlayerNames.uuidOf(args[1])).thenAccept(result ->
                sync(() -> {
                    Msg.result(player, result);
                    if (result.ok()) {
                        announce(player.getUniqueId(), Msg.text("guild.said.newLeader",
                                Map.of("player", args[1])), null);
                    }
                }));
    }

    private void disband(Player player) {
        Instant pending = pendingDisband.get(player.getUniqueId());
        Instant now = Instant.now();
        if (pending == null || pending.isBefore(now)) {
            pendingDisband.put(player.getUniqueId(), now.plus(CONFIRM_WINDOW));
            Msg.send(player, Msg.text("guild.disband.warn"));
            player.sendMessage(Msg.fail(Msg.text("guild.disband.confirm", Map.of(
                    "seconds", String.valueOf(CONFIRM_WINDOW.toSeconds())))));
            return;
        }
        pendingDisband.remove(player.getUniqueId());

        List<UUID> before = guilds.guildOf(player.getUniqueId())
                .map(guild -> guilds.memberUuids(guild.id())).orElse(List.of());
        guilds.disband(player.getUniqueId()).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (!result.ok()) return;
            for (UUID uuid : before) {
                Player member = Bukkit.getPlayer(uuid);
                if (member != null && !member.equals(player)) {
                    member.sendMessage(Msg.fail(Msg.text("guild.said.disbandedByLeader")));
                }
            }
        }));
    }

    private void tag(Player player, String[] args) {
        if (args.length < 2) {
            Msg.usage(player, Msg.text("help.guild.tag.use"), Msg.text("help.guild.tag.what"));
            return;
        }
        guilds.changeTag(player.getUniqueId(), args[1]).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (result.ok()) {
                announce(player.getUniqueId(),
                        Msg.text("guild.tagSet", Map.of("tag", args[1])), null);
            }
        }));
    }

    private void bank(Player player, String[] args) {
        if (!guilds.bankAvailable()) {
            // Честно про причину: без Vault банка нет не потому, что сломалось.
            Msg.send(player, Msg.text("guild.err.bankNoVault"));
            return;
        }
        if (args.length < 2) {
            Msg.send(player, Msg.text("guild.bank.usage"));
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("log") || action.equals("лог")) {
            bankLog(player);
            return;
        }
        if (args.length < 3) {
            boolean depositing = action.startsWith("d") || action.startsWith("в");
            Msg.usage(player, Msg.text("guild.bank.amountUsage", Map.of("action", action)),
                    Msg.text(depositing
                            ? "help.guild.bank.deposit.what"
                            : "help.guild.bank.withdraw.what"));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            Msg.send(player, Msg.text("guild.err.notAnAmount", Map.of("value", args[2])));
            return;
        }

        switch (action) {
            case "deposit", "внести", "вложить" ->
                    guilds.deposit(player.getUniqueId(), amount)
                            .thenAccept(result -> sync(() -> Msg.result(player, result)));
            case "withdraw", "снять" ->
                    guilds.withdraw(player.getUniqueId(), amount)
                            .thenAccept(result -> sync(() -> Msg.result(player, result)));
            default -> Msg.send(player, Msg.text("guild.bank.usageFull"));
        }
    }

    private void bankLog(Player player) {
        guilds.guildOf(player.getUniqueId()).ifPresentOrElse(guild ->
                guilds.bankHistory(guild.id(), 10).thenAccept(entries -> sync(() -> {
                    if (entries.isEmpty()) {
                        Msg.send(player, Msg.text("guild.bank.logEmpty"));
                        return;
                    }
                    Msg.send(player, Msg.text("guild.bank.logTitle"));
                    for (var entry : entries) {
                        player.sendMessage(Msg.colored((entry.deposit() ? "&a+ " : "&c− ")
                                + guilds.economy().format(entry.amount())
                                + " &7" + entry.actorName()));
                    }
                })), () -> Msg.send(player, Msg.text("guild.err.notInGuild")));
    }

    private void info(Player player, String[] args) {
        StoredGuild guild = args.length >= 2
                ? guilds.byName(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)))
                        .orElse(null)
                : guilds.guildOf(player.getUniqueId()).orElse(null);
        if (guild == null) {
            Msg.send(player, Msg.text(
                    args.length >= 2 ? "guild.err.noSuchName" : "guild.err.notInGuild"));
            return;
        }

        // Карточка блоками с подписями, а не сплошной лентой: на вопрос «что у
        // нас за гильдия» отвечают сразу несколько разных фактов, и без
        // подписи слева читатель каждый раз гадает, что за число он видит.
        Msg.lines(player, List.of(HelpBook.header(guild.name() + " [" + guild.tag() + "]")));
        if (!guild.settings().motd().isBlank()) {
            player.sendMessage(Msg.colored("&7" + guild.settings().motd()));
        }

        int online = 0;
        for (GuildMember member : guild.members()) {
            if (Bukkit.getPlayer(member.uuid()) != null) online++;
        }

        row(player, Msg.text("info.leader"), "&f" + leaderName(guild));
        row(player, Msg.text("info.members"), Msg.text("info.membersValue", Map.of(
                "total", String.valueOf(guild.members().size()),
                "online", String.valueOf(online))));
        row(player, Msg.text("info.created"), "&f" + date(guild.createdAt()));
        row(player, Msg.text("info.joinPolicy"),
                "&f" + Msg.text(guild.settings().joinPolicy().titleKey()));
        row(player, Msg.text("info.friendlyFire"), guild.settings().friendlyFire()
                ? "&c" + Msg.text("menu.enabled")
                : "&a" + Msg.text("menu.disabled"));
        // Банк — только если он вообще работает: строка «Банк: 0» на сервере
        // без Vault выглядит как пропавшие деньги, а не как отсутствие банка.
        if (guilds.bankAvailable()) {
            row(player, Msg.text("info.bank"), "&6" + guilds.economy().format(guild.bank()));
        }

        List<GuildBonus> active = guilds.bonuses(guild.id());
        if (!active.isEmpty()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < active.size(); i++) {
                if (i > 0) line.append("&7, ");
                line.append("&a").append(shortBonus(active.get(i)));
            }
            row(player, Msg.text("info.bonuses"), line.toString());
        }

        // Дома — только своей гильдии: где стоит чужой приват, посторонним
        // знать незачем, это подсказка, куда идти грабить.
        if (guilds.membership(player.getUniqueId())
                .filter(own -> own.guildId() == guild.id()).isPresent()) {
            List<GuildRegion> homes = guilds.regions(guild.id());
            if (!homes.isEmpty()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < homes.size(); i++) {
                    if (i > 0) line.append("&7, ");
                    line.append("&f").append(homes.get(i).regionId());
                }
                row(player, Msg.text("info.homes"), line.toString());
            }
        }

        player.sendMessage(Msg.colored("&7" + Msg.text("info.roster")));
        for (GuildMember member : guild.members()) {
            boolean isOnline = Bukkit.getPlayer(member.uuid()) != null;
            player.sendMessage(Msg.colored((isOnline ? "&a● &f" : "&8● &7") + member.username()
                    + " &8— " + Msg.text(member.rank().titleKey())));
        }
    }

    /**
     * Дата создания без времени: час и минуты тут никому ничего не говорят.
     *
     * Формат берётся у самой Java по языку сервера, а не задаётся шаблоном:
     * «06.09.2026» для англоязычного читателя — это 9 июня, и разбираться, что
     * тут день, а что месяц, он не обязан.
     */
    private static String date(Instant when) {
        return java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(Msg.locale())
                .format(when.atZone(java.time.ZoneId.systemDefault()));
    }

    /**
     * Строка карточки: подпись слева, значение справа.
     *
     * Подпись всегда серая, значение — цветное. Глаз бежит по левому столбцу и
     * останавливается на нужном, а не вычитывает предложение целиком.
     */
    private static void row(Player player, String label, String value) {
        player.sendMessage(Msg.colored("&7" + label + ": " + value));
    }

    /** Ник лидера из состава: в StoredGuild лежит только его uuid. */
    private static String leaderName(StoredGuild guild) {
        for (GuildMember member : guild.members()) {
            if (member.rank() == GuildRank.LEADER) return member.username();
        }
        return "?";
    }

    /**
     * Бонус в одну короткую запись: «Блоки ×1.5 (7д)».
     *
     * Тот же короткий вид, что и в сайдбаре, — человек, увидевший строку там,
     * должен узнать её здесь. Подробности со сроком до минуты и тем, кто
     * выдал, остаются в {@code /guild bonuses}.
     */
    private static String shortBonus(GuildBonus bonus) {
        String value = bonus.type().kind() == BonusType.Kind.MULTIPLIER
                ? "\u00D7" + HudLines.multiplierText(bonus.magnitude())
                : String.valueOf(Math.round(bonus.magnitude()));
        String left = bonus.expiresAt() == null
                ? ""
                : " (" + HudLines.shortDurationText(
                        Math.max(0, Duration.between(Instant.now(), bonus.expiresAt()).toSeconds()),
                        Msg.hudLabels())
                        + ")";
        return Msg.text(bonus.type().shortTitleKey()) + " " + value + left;
    }

    private void list(CommandSender sender, String[] args) {
        String query = args.length >= 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : null;
        guilds.summaries(query, 20).thenAccept(summaries -> sync(() -> {
            if (summaries.isEmpty()) {
                Msg.send(sender, Msg.text(query == null ? "list.empty" : "list.nothingFound"));
                return;
            }
            Msg.send(sender, Msg.text("list.title",
                    Map.of("count", String.valueOf(summaries.size()))));
            for (var summary : summaries) {
                sender.sendMessage(Msg.colored("&b[" + summary.tag() + "] &f" + summary.name()
                        + " &8— &7" + Msg.text("list.row", Map.of(
                                "members", String.valueOf(summary.memberCount()),
                                "leader", summary.leaderName()))));
            }
        }));
    }

    // ------------------------------------------------------- вмешательство

    /**
     * Дом гильдии: привязать регион WorldGuard к гильдии.
     *
     * Только лидер: регион — это общая собственность, и раздавать доступ к
     * сундукам гильдии офицер не должен.
     */
    private void claim(Player player, String[] args) {
        if (regions == null) {
            Msg.send(player, Msg.text("claim.err.noWorldGuard"));
            return;
        }
        var membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty()) {
            Msg.send(player, Msg.text("guild.err.notInGuild"));
            return;
        }
        long guildId = membership.get().guildId();
        if (membership.get().rank() != GuildRank.LEADER) {
            Msg.send(player, Msg.text("claim.err.leaderOnly"));
            return;
        }

        if (args.length < 2) {
            List<GuildRegion> attached = guilds.regions(guildId);
            if (attached.isEmpty()) {
                Msg.send(player, Msg.text("claim.none"));
            } else {
                Msg.send(player, Msg.text("claim.listTitle"));
                for (GuildRegion region : attached) {
                    Msg.send(player, "&8• &f" + region.regionId() + " &8(" + region.world() + ")");
                }
            }
            Msg.lines(player, HelpBook.titled(
                            Msg.text("help.claim.title"), "/guild claim", Msg.helpLabels())
                    .add(Msg.text("help.guild.claim.use"), Msg.text("help.guild.claim.what"))
                    .add(Msg.text("help.guild.claimRemove.use"),
                            Msg.text("help.guild.claimRemove.what"))
                    .build().page(1));
            Msg.send(player, Msg.text("claim.ownerOnlyHint"));
            return;
        }

        boolean detaching = args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("убрать");
        String regionId = detaching ? (args.length > 2 ? args[2] : null) : args[1];
        if (regionId == null) {
            Msg.usage(player, Msg.text("help.guild.claimRemove.use"),
                    Msg.text("help.guild.claimRemove.what"));
            return;
        }

        String world = player.getWorld().getName();
        List<UUID> members = guilds.memberUuids(guildId);

        if (detaching) {
            if (!guilds.detachRegion(guildId, world, regionId)) {
                Msg.send(player, Msg.text("claim.err.notAttached", Map.of("region", regionId)));
                return;
            }
            // Убираем именно участников гильдии. Владелец региона (обычно сам
            // лидер) в другом списке и не трогается.
            regions.removeMembers(player.getWorld(), regionId, members);
            Msg.send(player, Msg.text("claim.detached", Map.of("region", regionId)));
            return;
        }

        // Чужой регион, уже отданный другой гильдии, перехватывать нельзя:
        // иначе достаточно было бы стать владельцем на минуту.
        var owner = guilds.regionOwner(world, regionId);
        if (owner.isPresent() && owner.get() != guildId) {
            Msg.send(player, Msg.text("claim.err.otherGuild"));
            return;
        }

        WorldGuardBridge.Result result = regions.attach(
                player.getWorld(), regionId, player.getUniqueId(), members);
        switch (result) {
            case NO_MANAGER -> Msg.send(player, Msg.text("claim.err.noManager"));
            case NO_REGION -> Msg.send(player, Msg.text("claim.err.noRegion",
                    Map.of("world", world, "region", regionId)));
            case NOT_OWNER -> Msg.send(player, Msg.text("claim.err.notOwner"));
            case OK -> {
                guilds.attachRegion(guildId, world, regionId);
                Msg.send(player, Msg.text("claim.attached", Map.of("region", regionId)));
            }
        }
    }

    /**
     * Что сейчас действует у моей гильдии.
     *
     * Отдельной командой, а не строкой в /guild info: бонусов бывает до пяти,
     * у каждого величина и остаток срока, и в общей сводке это заняло бы
     * больше места, чем всё остальное вместе.
     */
    private void bonuses(Player player) {
        var membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty()) {
            Msg.send(player, Msg.text("guild.err.notInGuild"));
            return;
        }
        List<GuildBonus> active = guilds.bonuses(membership.get().guildId());
        if (active.isEmpty()) {
            Msg.send(player, Msg.text("bonus.noneYours"));
            return;
        }
        Msg.lines(player, List.of(HelpBook.header(Msg.text("bonus.title"))));
        for (GuildBonus bonus : active) Msg.send(player, "&8• " + describe(bonus));
    }

    /**
     * Бонусы гильдии: выдать, снять, посмотреть.
     *
     * Гильдия называется ИМЕНЕМ, а не внутренним номером: номер знает база, а
     * администратор в игре — имя, которое видит в списке.
     */
    private void adminBonus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            bonusUsage(sender);
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);

        if (action.equals("list") || action.equals("список")) {
            if (args.length < 4) {
                Msg.usage(sender, Msg.text("help.admin.bonusList.use"),
                        Msg.text("help.admin.bonusList.what"));
                return;
            }
            withGuild(sender, join(args, 3), guild -> {
                List<GuildBonus> active = guilds.bonuses(guild.id());
                if (active.isEmpty()) {
                    Msg.send(sender, Msg.text("bonus.noneGuild",
                            Map.of("guild", guild.name())));
                    return;
                }
                Msg.lines(sender, List.of(HelpBook.header(
                        Msg.text("bonus.titleGuild", Map.of("guild", guild.name())))));
                for (GuildBonus bonus : active) Msg.send(sender, "&8• " + describe(bonus));
            });
            return;
        }

        boolean granting = action.equals("grant") || action.equals("выдать");
        boolean revoking = action.equals("revoke") || action.equals("снять");
        if (!granting && !revoking) {
            Msg.send(sender, Msg.text("guild.err.noSuchAction", Map.of("action", args[2])));
            bonusUsage(sender);
            return;
        }
        if (args.length < 5) {
            bonusUsage(sender);
            return;
        }

        BonusType type = BonusType.parse(args[3]);
        if (type == null) {
            Msg.send(sender, Msg.text("bonus.err.noSuchType",
                    Map.of("list", "&f" + bonusTypeNames())));
            return;
        }

        if (revoking) {
            withGuild(sender, join(args, 4), guild ->
                    guilds.revokeBonus(guild.id(), type, sender.getName())
                            .thenAccept(r -> sync(() -> Msg.result(sender, r))));
            return;
        }

        // grant <вид> <величина> [<срок>] <гильдия>
        if (args.length < 6) {
            bonusUsage(sender);
            return;
        }
        double magnitude;
        try {
            magnitude = Double.parseDouble(args[4].replace(',', '.'));
        } catch (NumberFormatException e) {
            Msg.send(sender, Msg.text("bonus.err.notANumber", Map.of(
                    "bonus", Msg.text(type.titleKey()),
                    "range", range(type))));
            return;
        }

        // Срок необязателен, и отличить его от имени гильдии можно только по
        // виду: «30m» — срок, «Драконы» — имя. Поэтому разбираем следующий
        // аргумент как срок и, если не вышло, считаем началом имени.
        Duration duration = parseDuration(args.length > 5 ? args[5] : null);
        int nameFrom = duration == null ? 5 : 6;
        if (args.length <= nameFrom) {
            bonusUsage(sender);
            return;
        }
        String guildName = join(args, nameFrom);

        withGuild(sender, guildName, guild ->
                guilds.grantBonus(guild.id(), type, magnitude, duration, sender.getName())
                        .thenAccept(r -> sync(() -> Msg.result(sender, r))));
    }

    /**
     * Подсказка по бонусам: три действия и перечень видов с границами.
     *
     * Границы здесь, а не в отдельной команде: человек, который набрал
     * «bonus», следующим делом спросит «а сколько можно», и отправлять его за
     * этим в README значит заставить выйти из игры.
     */
    private void bonusUsage(CommandSender sender) {
        Msg.lines(sender, HelpBook.titled(
                        Msg.text("bonus.title"), "/guild admin bonus", Msg.helpLabels())
                .add(Msg.text("help.admin.bonusList.use"), Msg.text("help.admin.bonusList.what"))
                .add(Msg.text("help.admin.bonusGrant.use"), Msg.text("help.admin.bonusGrant.what"))
                .add(Msg.text("help.admin.bonusRevoke.use"), Msg.text("help.admin.bonusRevoke.what"))
                .build().page(1));
        Msg.send(sender, Msg.text("bonus.kinds"));
        for (BonusType type : BonusType.values()) {
            // Имя вида (miningspeed) — то, что НАБИРАЮТ, и потому остаётся как
            // есть; переводится только пояснение справа.
            Msg.lines(sender, List.of(HelpBook.line(
                    type.name().toLowerCase(Locale.ROOT),
                    Msg.text(type.titleKey()) + " \u2014 " + range(type))));
        }
        Msg.send(sender, Msg.text("bonus.playableHint"));
    }

    /** Найти гильдию по имени и сделать с ней что-то, иначе сказать, что её нет. */
    private void withGuild(CommandSender sender, String name, java.util.function.Consumer<StoredGuild> action) {
        var guild = guilds.byName(name);
        if (guild.isEmpty()) {
            Msg.send(sender, Msg.text("guild.err.notFound", Map.of("guild", name)));
            return;
        }
        action.accept(guild.get());
    }

    private static String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    /**
     * Срок вида «30m», «2h», «7d». null — не срок (значит, это уже имя
     * гильдии) либо аргумента нет вовсе, и бонус выдаётся навсегда.
     */
    static Duration parseDuration(String raw) {
        return ArgWords.duration(raw);
    }

    private static String bonusTypeNames() {
        return java.util.Arrays.stream(BonusType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Границы величины для этого вида: «уровень эффекта 1-2» или «множитель 1.0-3.0». */
    private static String range(BonusType type) {
        return type.kind() == BonusType.Kind.EFFECT_LEVEL
                ? Msg.text("bonus.range.level", Map.of("max", String.valueOf((int) type.max())))
                : Msg.text("bonus.range.multiplier", Map.of("max", String.valueOf(type.max())));
    }

    /** Строка бонуса для чата: что, сколько и до каких пор. */
    static String describe(GuildBonus bonus) {
        String left = bonus.permanent()
                ? "&a" + Msg.text("time.forever")
                : "&e" + Msg.text("bonus.left", Map.of("duration",
                        GuildService.humanDuration(bonus.remaining(Instant.now()), Msg.hudLabels())));
        return "&f" + Msg.text(bonus.type().titleKey()) + " &7"
                + GuildService.describe(bonus.type(), bonus.magnitude(), Msg.hudLabels())
                + " &8— " + left + " &8(" + Msg.text("bonus.grantedBy",
                        Map.of("actor", bonus.grantedBy())) + ")";
    }

    /**
     * Урон по своим внутри пати — настройка сервера, а не отдельной группы.
     *
     * Без аргумента показывает текущее состояние: переключатель, который
     * нельзя посмотреть, заставляет угадывать, и рано или поздно его
     * переключают «на всякий случай» в неверную сторону.
     */
    private void partyFriendlyFire(CommandSender sender, String[] args) {
        if (!(plugin instanceof AurumGuildsPlugin guildsPlugin)) {
            Msg.send(sender, Msg.text("admin.ff.unavailable"));
            return;
        }
        if (args.length < 3) {
            Msg.send(sender, Msg.text("admin.ff.state", Map.of("value",
                    guildsPlugin.partyFriendlyFire()
                            ? "&c" + Msg.text("menu.enabled")
                            : "&a" + Msg.text("menu.disabled"))));
            Msg.send(sender, Msg.text("admin.ff.howTo"));
            return;
        }
        String value = args[2].toLowerCase(Locale.ROOT);
        if (!YES.contains(value) && !NO.contains(value)) {
            Msg.send(sender, Msg.text("admin.ff.expected"));
            return;
        }
        boolean allowed = YES.contains(value);
        guildsPlugin.partyFriendlyFire(allowed);
        Msg.send(sender, Msg.text("admin.ff.saved", Map.of("value",
                allowed ? "&c" + Msg.text("menu.enabled") : "&a" + Msg.text("menu.disabled"))));
        // Про гильдии говорим отдельно: их настройка своя, и человек, только
        // что переключивший общесерверную, вправе ждать, что она главнее.
        Msg.send(sender, "&7" + Msg.text("admin.ff.guildsUnaffected"));
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            Msg.send(sender, Msg.text("guild.err.noPermission"));
            return;
        }
        // reload — единственная подкоманда без аргумента, поэтому проверяется
        // до общего требования трёх слов.
        if (args.length >= 2 && ADMIN_RELOAD.contains(args[1].toLowerCase(Locale.ROOT))) {
            if (plugin instanceof AurumGuildsPlugin guildsPlugin) {
                for (String line : guildsPlugin.reloadSettings()) Msg.send(sender, line);
            } else {
                Msg.send(sender, Msg.text("admin.reloadUnavailable"));
            }
            return;
        }
        if (args.length >= 2 && ADMIN_BONUS.contains(args[1].toLowerCase(Locale.ROOT))) {
            adminBonus(sender, args);
            return;
        }
        // friendlyfire без аргумента показывает текущее состояние, с
        // аргументом — переключает. Отдельно от общего требования трёх слов.
        if (args.length >= 2 && ADMIN_PARTY_FF.contains(args[1].toLowerCase(Locale.ROOT))) {
            partyFriendlyFire(sender, args);
            return;
        }
        if (args.length < 3) {
            adminUsage(sender);
            return;
        }

        String actor = sender.getName();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "remove", "исключить" ->
                    guilds.adminRemove(args[2], actor).thenAccept(r -> sync(() -> Msg.result(sender, r)));
            case "disband", "распустить" -> {
                var guild = guilds.byName(String.join(" ",
                        java.util.Arrays.copyOfRange(args, 2, args.length)));
                if (guild.isEmpty()) {
                    Msg.send(sender, Msg.text("guild.err.noSuchName"));
                    return;
                }
                List<UUID> before = guilds.memberUuids(guild.get().id());
                guilds.adminDisband(guild.get().id(), actor).thenAccept(r -> sync(() -> {
                    Msg.result(sender, r);
                    if (!r.ok()) return;
                    for (UUID uuid : before) {
                        Player member = Bukkit.getPlayer(uuid);
                        if (member != null) {
                            member.sendMessage(Msg.fail(Msg.text("guild.said.disbandedByAdmin")));
                        }
                    }
                }));
            }
            case "transfer", "передать" -> {
                if (args.length < 4) {
                    Msg.usage(sender, Msg.text("help.admin.transfer.use"),
                            Msg.text("help.admin.transfer.what"));
                    return;
                }
                // Имя гильдии — предпоследние аргументы, ник — последний:
                // имя может быть из нескольких слов, ник — никогда.
                String targetName = args[args.length - 1];
                String guildName = String.join(" ",
                        java.util.Arrays.copyOfRange(args, 2, args.length - 1));
                var guild = guilds.byName(guildName);
                if (guild.isEmpty()) {
                    Msg.send(sender, Msg.text("guild.err.notFound",
                            Map.of("guild", guildName)));
                    return;
                }
                guilds.adminTransfer(guild.get().id(), targetName, actor)
                        .thenAccept(r -> sync(() -> Msg.result(sender, r)));
            }
            default -> adminUsage(sender);
        }
    }

    // ------------------------------------------------------------ помощь

    /**
     * Справка: строка на команду, описание рядом.
     *
     * Администраторские команды показываются только тем, у кого есть право на
     * них: остальным они не подсказка, а перечень того, чего нельзя, — и
     * заодно лишняя страница пролистывать.
     */
    private void usage(CommandSender sender, String[] args) {
        send(sender, helpFor(sender).page(page(args)));
    }

    /**
     * Справка ровно того состава, который увидит этот человек.
     *
     * Одним методом, а не двумя: страницы для автодополнения должны считаться
     * по той же книге, которую команда потом покажет. Иначе игроку без права
     * администратора Tab предлагал бы третью страницу, а команда отвечала бы
     * второй — расхождение, которое замечают не сразу и объясняют багом.
     */
    private HelpBook helpFor(CommandSender sender) {
        HelpBook.Builder help = HelpBook.titled(
                        Msg.text("help.guild.title"), "/guild help", Msg.helpLabels());
        for (String entry : List.of("create", "join", "leave", "info", "list", "invite", "kick",
                "promote", "demote", "transfer", "disband", "tag", "settings", "bank", "bonuses",
                "claim", "claimRemove", "chat")) {
            help.add(Msg.text("help.guild." + entry + ".use"),
                    Msg.text("help.guild." + entry + ".what"));
        }

        if (sender.hasPermission(PERMISSION_ADMIN)) adminEntries(help);

        return help.build();
    }

    /**
     * Справка только по администрированию — ответ на «/guild admin» без
     * аргументов.
     *
     * Отдельной книгой, а не отсылкой к общей: человек уже набрал admin, и
     * посылать его листать до нужной страницы значило бы ответить не на тот
     * вопрос, который он задал.
     */
    private void adminUsage(CommandSender sender) {
        send(sender, adminEntries(HelpBook.titled(
                        Msg.text("help.admin.title"), "/guild admin", Msg.helpLabels()))
                .build().page(1));
    }

    /**
     * Строки администрирования — одним списком на оба места, где они нужны.
     *
     * Иначе один и тот же перечень пришлось бы держать в двух копиях, и первая
     * же новая подкоманда попала бы ровно в одну из них.
     */
    private static HelpBook.Builder adminEntries(HelpBook.Builder into) {
        for (String entry : List.of("remove", "transfer", "disband", "bonusList", "bonusGrant",
                "bonusRevoke", "friendlyFire", "reload")) {
            into.add(Msg.text("help.admin." + entry + ".use"),
                    Msg.text("help.admin." + entry + ".what"));
        }
        return into;
    }

    private static void send(CommandSender sender, List<String> lines) {
        for (String line : lines) sender.sendMessage(Msg.colored(line));
    }

    /**
     * Номер страницы из аргументов.
     *
     * Не число — первая страница: «/guild помощь» набирают чаще, чем
     * «/guild help 2», и отчитывать за это незачем. За границы страница не
     * выйдет — об этом позаботится сам {@link HelpBook}.
     */
    private static int page(String[] args) {
        if (args.length < 2) return 1;
        try {
            return Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** Показать вошедшему описание гильдии, если оно задано. */
    private void showMotd(Player player) {
        guilds.guildOf(player.getUniqueId()).ifPresent(guild -> {
            if (!guild.settings().motd().isBlank()) {
                player.sendMessage(Msg.colored("&7" + guild.settings().motd()));
            }
        });
    }

    /** Сообщение всем в гильдии игрока, кроме исключённого. */
    private void announce(UUID player, String text, Player except) {
        guilds.guildOf(player).ifPresent(guild -> {
            for (UUID uuid : guilds.memberUuids(guild.id())) {
                Player member = Bukkit.getPlayer(uuid);
                if (member != null && !member.equals(except)) member.sendMessage(Msg.of(text));
            }
        });
    }

    private void reply(CommandSender sender, java.util.concurrent.CompletableFuture<GuildActionResult> future) {
        future.thenAccept(result -> sync(() -> Msg.result(sender, result)));
    }

    /**
     * Вернуться в главный поток.
     *
     * Операции гильдий идут в рабочем потоке, а отправка сообщений и любое
     * обращение к Player — работа главного.
     */
    private void sync(Runnable action) {
        // Плагин может выключаться прямо сейчас: планировщик на этом бросает
        // IllegalPluginAccessException, а падать из-за недоставленного ответа
        // на уже выполненную команду незачем.
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, action);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        // Bukkit всегда передаёт хотя бы один (возможно пустой) токен, но
        // падать в автодополнении нельзя: исключение здесь ломает нажатие Tab
        // молча, и выглядит это как «подсказки просто не работают».
        if (args.length == 0) return List.of();
        return prefixed(options(sender, args), args[args.length - 1]);
    }

    /**
     * Что можно набрать на этом месте.
     *
     * <h2>Почему разбор по позициям, а не одна лесенка if-ов</h2>
     *
     * Прошлая версия на третьем аргументе {@code /guild admin} возвращала
     * имена гильдий ДЛЯ ЛЮБОГО действия — и на {@code /guild admin bonus}
     * подсказывала гильдии там, где нужно {@code grant|revoke|list}. Такое
     * получается само собой, когда общая ветка стоит после частных и ловит
     * всё, что до неё не дошло.
     *
     * Поэтому каждая команда разбирается до конца в своём методе, и общего
     * «а иначе имена гильдий» нет вовсе: место, для которого подсказки не
     * придумано, честно возвращает пустой список.
     *
     * <h2>Русские псевдонимы</h2>
     *
     * Сравнение идёт по спискам псевдонимов, а не по английскому слову: игрок,
     * набравший «/guild банк», должен получить подсказки банка, а не пустоту.
     */
    private List<String> options(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission(PERMISSION_ADMIN);
        if (args.length <= 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            if (!admin) options.remove("admin");
            return options;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (is(sub, "admin", "админ")) return admin ? adminOptions(sender, args) : List.of();
        if (is(sub, "bank", "банк")) return bankOptions(args);
        if (is(sub, "claim", "приват")) return claimOptions(sender, args);

        // Дальше — команды, у которых ровно один аргумент.
        if (args.length != 2) return List.of();

        if (is(sub, "invite", "позвать")) return onlineNames();
        // Выгнать и повысить можно только своего, и подсказывать весь онлайн
        // значит предлагать команду, которая откажет: состав гильдии тут
        // короче и точнее.
        if (is(sub, "kick", "выгнать", "promote", "повысить", "demote", "понизить",
                "transfer", "передать")) {
            return ownGuildMembers(sender);
        }
        if (is(sub, "join", "вступить", "info", "инфо", "list", "список")) return guildNames();
        if (is(sub, "help", "помощь", "?")) return helpPages(sender);
        return List.of();
    }

    private List<String> adminOptions(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("remove", "transfer", "disband", "bonus", "friendlyfire", "reload");
        }
        String action = args[1].toLowerCase(Locale.ROOT);

        if (is(action, "bonus", "бонус", "бонусы")) return adminBonusOptions(args);
        if (is(action, "friendlyfire", "ff", "свойогонь")) {
            return args.length == 3 ? List.of("on", "off") : List.of();
        }
        if (is(action, "remove", "исключить")) {
            return args.length == 3 ? onlineNames() : List.of();
        }
        if (is(action, "disband", "распустить")) return guildNameWords(args, 2);
        if (is(action, "transfer", "передать")) {
            // Имя гильдии может быть из нескольких слов, ник — последний
            // аргумент. Пока имя не набрано целиком, подсказываем его слова;
            // ники предлагаем заодно, потому что где кончается имя, знает
            // только тот, кто набирает.
            List<String> options = new ArrayList<>(guildNameWords(args, 2));
            options.addAll(onlineNames());
            return options;
        }
        return List.of();
    }

    /**
     * Бонусы: у каждой позиции своё.
     *
     * <pre>
     * 3: grant | revoke | list
     * 4: вид бонуса (у list — уже имя гильдии)
     * 5: величина (grant) | имя гильдии (revoke)
     * 6: срок ИЛИ имя гильдии — оба тут допустимы, разбор их и различает
     * 7+: имя гильдии, если на 6-й был срок
     * </pre>
     */
    private List<String> adminBonusOptions(String[] args) {
        if (args.length == 3) return List.of("list", "grant", "revoke");
        String action = args[2].toLowerCase(Locale.ROOT);

        if (is(action, "list", "список")) return guildNameWords(args, 3);
        boolean granting = is(action, "grant", "выдать");
        boolean revoking = is(action, "revoke", "снять");
        if (!granting && !revoking) return List.of();

        if (args.length == 4) return bonusTypes();
        if (revoking) return guildNameWords(args, 4);

        // grant <вид> <величина> [срок] <гильдия>
        if (args.length == 5) return magnitudeHints(BonusType.parse(args[3]));
        if (args.length == 6) {
            // Здесь законны оба: и срок, и первое слово имени. Сроки первыми —
            // их несколько штук и они короткие, имя всё равно допечатывается.
            List<String> options = new ArrayList<>(List.of("30m", "2h", "12h", "7d", "30d"));
            options.addAll(guildNameWords(args, 5));
            return options;
        }
        // Дальше имя гильдии — со следующего слова, если на шестой позиции
        // действительно стоял срок, и с той же самой, если это уже имя.
        return guildNameWords(args, parseDuration(args[5]) == null ? 5 : 6);
    }

    private static List<String> magnitudeHints(BonusType type) {
        if (type == null) return List.of();
        if (type.kind() == BonusType.Kind.EFFECT_LEVEL) return List.of("1", "2", "3");
        return List.of("1.25", "1.5", "2", "3");
    }

    private static List<String> bonusTypes() {
        return java.util.Arrays.stream(BonusType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static List<String> bankOptions(String[] args) {
        if (args.length == 2) return List.of("deposit", "withdraw", "log");
        // Сумму не подсказываем: любое число здесь было бы выдумкой, а
        // предложенное вслепую списание денег — плохая шутка.
        return List.of();
    }

    /**
     * Приват: {@code remove} и регионы.
     *
     * Для привязки предлагаются только регионы, ГДЕ ИГРОК ВЛАДЕЛЕЦ — привязать
     * всё равно можно только свои, а список чужих приватов это карта того, где
     * на сервере есть что взять. Для отвязки — только уже привязанные к его
     * гильдии.
     */
    private List<String> claimOptions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        long guildId = guilds.membership(player.getUniqueId()).map(m -> m.guildId()).orElse(-1L);

        if (args.length == 2) {
            List<String> options = new ArrayList<>();
            options.add("remove");
            if (regions != null) {
                options.addAll(regions.ownedRegions(player.getWorld(), player.getUniqueId()));
            }
            return options;
        }
        if (args.length == 3 && is(args[1].toLowerCase(Locale.ROOT), "remove", "убрать")) {
            if (guildId < 0) return List.of();
            String world = player.getWorld().getName();
            return guilds.regions(guildId).stream()
                    .filter(region -> region.world().equals(world))
                    .map(GuildRegion::regionId)
                    .toList();
        }
        return List.of();
    }

    /** Номера страниц справки — ровно столько, сколько их есть у этого игрока. */
    private List<String> helpPages(CommandSender sender) {
        int pages = helpFor(sender).pages();
        List<String> numbers = new ArrayList<>(pages);
        for (int i = 1; i <= pages; i++) numbers.add(String.valueOf(i));
        return numbers;
    }

    /** Состав гильдии того, кто набирает. Пусто, если он ни в какой не состоит. */
    private List<String> ownGuildMembers(CommandSender sender) {
        if (!(sender instanceof Player player)) return List.of();
        return guilds.guildOf(player.getUniqueId())
                .map(guild -> guild.members().stream()
                        .filter(member -> !member.uuid().equals(player.getUniqueId()))
                        .map(GuildMember::username)
                        .toList())
                .orElseGet(List::of);
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    /** Из памяти и синхронно: автодополнение идёт в главном потоке. */
    private List<String> guildNames() {
        return guilds.guildNames();
    }

    /**
     * Имя гильдии по словам.
     *
     * Bukkit режет строку по пробелам, и двусловное имя «Ночные волки» одним
     * токеном не дополнить. Поэтому подсказывается ОЧЕРЕДНОЕ СЛОВО тех имён,
     * у которых предыдущие слова уже совпали: набрал «Ночные» — получил
     * «волки». Без этого имена из двух слов не дополняются вовсе, а на сервере
     * такие как раз и заводят.
     *
     * @param from индекс аргумента, с которого начинается имя
     */
    private List<String> guildNameWords(String[] args, int from) {
        return ArgWords.nextWords(guilds.guildNames(), args, from);
    }

    /** Совпадает ли набранное с любым из псевдонимов команды. */
    private static boolean is(String typed, String... aliases) {
        for (String alias : aliases) {
            if (alias.equals(typed)) return true;
        }
        return false;
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
