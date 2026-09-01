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
import ovh.aurumgg.guilds.core.GuildRegion;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.HelpBook;
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

    private static final String BONUS_USAGE =
            "/guild admin bonus grant|revoke|list …";

    private static final String BONUS_GRANT_USAGE =
            "/guild admin bonus grant <вид> <величина> [30m|2h|7d] <гильдия>";

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
            Msg.send(sender, "Из консоли доступны только /guild list и /guild admin");
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
                Msg.send(player, "Нет такой команды: " + args[0]);
                usage(player, new String[] {"help"});
            }
        }
        return true;
    }

    // ------------------------------------------------------------ игроку

    private void create(Player player, String[] args) {
        if (guilds.config().requireCreatePermission() && !player.hasPermission(PERMISSION_CREATE)) {
            Msg.send(player, "Создавать гильдии на этом сервере может не каждый");
            return;
        }
        if (args.length < 3) {
            Msg.send(player, "Использование: /guild create <имя> <тег>");
            Msg.send(player, "Тег — до " + guilds.config().maxTagLength()
                    + " символов, он же будет видно рядом с ником.");
            return;
        }
        // Имя может быть из нескольких слов, тег — всегда последний аргумент.
        String tag = args[args.length - 1];
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 1));
        reply(player, guilds.create(player.getUniqueId(), name, tag));
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /guild invite <ник>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID uuid = target != null ? target.getUniqueId() : PlayerNames.uuidOf(args[1]);

        guilds.invite(player.getUniqueId(), uuid).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (result.ok() && target != null) {
                guilds.guildOf(player.getUniqueId()).ifPresent(guild -> target.sendMessage(Msg.ok(
                        player.getName() + " зовёт вас в гильдию «" + guild.name()
                                + "». Вступить: /guild join " + guild.name())));
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
                announce(player.getUniqueId(), player.getName() + " вступил в гильдию", player);
                showMotd(player);
            }
        }));
    }

    private void kick(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /guild kick <ник>");
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
                        ? Msg.fail("Вас исключили из гильдии")
                        : Msg.of(args[1] + " исключён из гильдии"));
            }
        }));
    }

    private void setRank(Player player, String[] args, GuildRank rank) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /guild "
                    + (rank == GuildRank.OFFICER ? "promote" : "demote") + " <ник>");
            return;
        }
        reply(player, guilds.setRank(player.getUniqueId(), PlayerNames.uuidOf(args[1]), rank));
    }

    private void transfer(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /guild transfer <ник>");
            return;
        }
        guilds.transfer(player.getUniqueId(), PlayerNames.uuidOf(args[1])).thenAccept(result ->
                sync(() -> {
                    Msg.result(player, result);
                    if (result.ok()) {
                        announce(player.getUniqueId(), args[1] + " теперь лидер гильдии", null);
                    }
                }));
    }

    private void disband(Player player) {
        Instant pending = pendingDisband.get(player.getUniqueId());
        Instant now = Instant.now();
        if (pending == null || pending.isBefore(now)) {
            pendingDisband.put(player.getUniqueId(), now.plus(CONFIRM_WINDOW));
            Msg.send(player, "Гильдия будет удалена вместе с составом и общаком. Отменить нельзя.");
            player.sendMessage(Msg.fail("Введите /guild disband ещё раз в течение "
                    + CONFIRM_WINDOW.toSeconds() + " секунд, чтобы подтвердить"));
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
                    member.sendMessage(Msg.fail("Гильдия распущена лидером"));
                }
            }
        }));
    }

    private void tag(Player player, String[] args) {
        if (args.length < 2) {
            Msg.send(player, "Использование: /guild tag <новый тег>");
            return;
        }
        guilds.changeTag(player.getUniqueId(), args[1]).thenAccept(result -> sync(() -> {
            Msg.result(player, result);
            if (result.ok()) announce(player.getUniqueId(), "Тег гильдии теперь [" + args[1] + "]", null);
        }));
    }

    private void bank(Player player, String[] args) {
        if (!guilds.bankAvailable()) {
            // Честно про причину: без Vault банка нет не потому, что сломалось.
            Msg.send(player, "Банк гильдий недоступен: на сервере нет плагина экономики (Vault)");
            return;
        }
        if (args.length < 2) {
            Msg.send(player, "/guild bank deposit <сумма> — внести, withdraw <сумма> — снять");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("log") || action.equals("лог")) {
            bankLog(player);
            return;
        }
        if (args.length < 3) {
            Msg.send(player, "Использование: /guild bank " + action + " <сумма>");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            Msg.send(player, "«" + args[2] + "» — это не сумма");
            return;
        }

        switch (action) {
            case "deposit", "внести", "вложить" ->
                    guilds.deposit(player.getUniqueId(), amount)
                            .thenAccept(result -> sync(() -> Msg.result(player, result)));
            case "withdraw", "снять" ->
                    guilds.withdraw(player.getUniqueId(), amount)
                            .thenAccept(result -> sync(() -> Msg.result(player, result)));
            default -> Msg.send(player, "/guild bank deposit <сумма> | withdraw <сумма> | log");
        }
    }

    private void bankLog(Player player) {
        guilds.guildOf(player.getUniqueId()).ifPresentOrElse(guild ->
                guilds.bankHistory(guild.id(), 10).thenAccept(entries -> sync(() -> {
                    if (entries.isEmpty()) {
                        Msg.send(player, "Операций с банком ещё не было");
                        return;
                    }
                    Msg.send(player, "Последние операции с банком:");
                    for (var entry : entries) {
                        player.sendMessage(Msg.colored((entry.deposit() ? "&a+ " : "&c− ")
                                + guilds.economy().format(entry.amount())
                                + " &7" + entry.actorName()));
                    }
                })), () -> Msg.send(player, "Вы не состоите в гильдии"));
    }

    private void info(Player player, String[] args) {
        StoredGuild guild = args.length >= 2
                ? guilds.byName(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)))
                        .orElse(null)
                : guilds.guildOf(player.getUniqueId()).orElse(null);
        if (guild == null) {
            Msg.send(player, args.length >= 2 ? "Гильдии с таким именем нет" : "Вы не состоите в гильдии");
            return;
        }

        Msg.send(player, "Гильдия «" + guild.name() + "» [" + guild.tag() + "]");
        if (!guild.settings().motd().isBlank()) {
            player.sendMessage(Msg.colored("&7" + guild.settings().motd()));
        }
        player.sendMessage(Msg.colored("&7Вступление: &f" + guild.settings().joinPolicy().title()
                + "&7, свой огонь: &f" + (guild.settings().friendlyFire() ? "разрешён" : "выключен")));
        if (guilds.bankAvailable()) {
            player.sendMessage(Msg.colored("&7Банк: &6" + guilds.economy().format(guild.bank())));
        }
        player.sendMessage(Msg.colored("&7Состав (&f" + guild.members().size() + "&7):"));
        for (GuildMember member : guild.members()) {
            boolean online = Bukkit.getPlayer(member.uuid()) != null;
            player.sendMessage(Msg.colored((online ? "&a● &f" : "&8● &7") + member.username()
                    + " &8— " + member.rank().title()));
        }
    }

    private void list(CommandSender sender, String[] args) {
        String query = args.length >= 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : null;
        guilds.summaries(query, 20).thenAccept(summaries -> sync(() -> {
            if (summaries.isEmpty()) {
                Msg.send(sender, query == null ? "Гильдий пока нет" : "Ничего не нашлось");
                return;
            }
            Msg.send(sender, "Гильдии (" + summaries.size() + "):");
            for (var summary : summaries) {
                sender.sendMessage(Msg.colored("&b[" + summary.tag() + "] &f" + summary.name()
                        + " &8— &7" + summary.memberCount() + " чел., лидер " + summary.leaderName()));
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
            Msg.send(player, "На сервере нет WorldGuard — привязывать регион не к чему");
            return;
        }
        var membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty()) {
            Msg.send(player, "Вы не состоите в гильдии");
            return;
        }
        long guildId = membership.get().guildId();
        if (membership.get().rank() != GuildRank.LEADER) {
            Msg.send(player, "Привязывать регионы может только лидер гильдии");
            return;
        }

        if (args.length < 2) {
            List<GuildRegion> attached = guilds.regions(guildId);
            if (attached.isEmpty()) {
                Msg.send(player, "К гильдии не привязано ни одного региона");
            } else {
                Msg.send(player, "Регионы гильдии:");
                for (GuildRegion region : attached) {
                    Msg.send(player, "&8• &f" + region.regionId() + " &8(" + region.world() + ")");
                }
            }
            Msg.send(player, "&7/guild claim <регион> — привязать, "
                    + "/guild claim remove <регион> — отвязать");
            return;
        }

        boolean detaching = args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("убрать");
        String regionId = detaching ? (args.length > 2 ? args[2] : null) : args[1];
        if (regionId == null) {
            Msg.send(player, "Использование: /guild claim remove <регион>");
            return;
        }

        String world = player.getWorld().getName();
        List<UUID> members = guilds.memberUuids(guildId);

        if (detaching) {
            if (!guilds.detachRegion(guildId, world, regionId)) {
                Msg.send(player, "Регион «" + regionId + "» не привязан к вашей гильдии "
                        + "в этом мире");
                return;
            }
            // Убираем именно участников гильдии. Владелец региона (обычно сам
            // лидер) в другом списке и не трогается.
            regions.removeMembers(player.getWorld(), regionId, members);
            Msg.send(player, "Регион «" + regionId + "» отвязан от гильдии");
            return;
        }

        // Чужой регион, уже отданный другой гильдии, перехватывать нельзя:
        // иначе достаточно было бы стать владельцем на минуту.
        var owner = guilds.regionOwner(world, regionId);
        if (owner.isPresent() && owner.get() != guildId) {
            Msg.send(player, "Этот регион уже принадлежит другой гильдии");
            return;
        }

        WorldGuardBridge.Result result = regions.attach(
                player.getWorld(), regionId, player.getUniqueId(), members);
        switch (result) {
            case NO_MANAGER -> Msg.send(player, "В этом мире регионы WorldGuard выключены");
            case NO_REGION -> Msg.send(player, "В мире «" + world + "» нет региона «" + regionId + "»");
            case NOT_OWNER -> Msg.send(player,
                    "Вы не владелец этого региона. Гильдии можно отдать только свою землю");
            case OK -> {
                guilds.attachRegion(guildId, world, regionId);
                Msg.send(player, "Регион «" + regionId + "» — теперь дом гильдии. "
                        + "Все участники добавлены, новые будут добавляться сами");
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
            Msg.send(player, "Вы не состоите в гильдии");
            return;
        }
        List<GuildBonus> active = guilds.bonuses(membership.get().guildId());
        if (active.isEmpty()) {
            Msg.send(player, "У вашей гильдии сейчас нет бонусов");
            return;
        }
        Msg.send(player, "Бонусы гильдии:");
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
            Msg.send(sender, BONUS_USAGE);
            Msg.send(sender, "Виды: &f" + bonusTypeNames());
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);

        if (action.equals("list") || action.equals("список")) {
            if (args.length < 4) {
                Msg.send(sender, "Использование: /guild admin bonus list <гильдия>");
                return;
            }
            withGuild(sender, join(args, 3), guild -> {
                List<GuildBonus> active = guilds.bonuses(guild.id());
                if (active.isEmpty()) {
                    Msg.send(sender, "У гильдии «" + guild.name() + "» нет бонусов");
                    return;
                }
                Msg.send(sender, "Бонусы гильдии «" + guild.name() + "»:");
                for (GuildBonus bonus : active) Msg.send(sender, "&8• " + describe(bonus));
            });
            return;
        }

        boolean granting = action.equals("grant") || action.equals("выдать");
        boolean revoking = action.equals("revoke") || action.equals("снять");
        if (!granting && !revoking) {
            Msg.send(sender, BONUS_USAGE);
            return;
        }
        if (args.length < 5) {
            Msg.send(sender, BONUS_USAGE);
            return;
        }

        BonusType type = BonusType.parse(args[3]);
        if (type == null) {
            Msg.send(sender, "Нет такого вида. Есть: &f" + bonusTypeNames());
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
            Msg.send(sender, "Использование: " + BONUS_GRANT_USAGE);
            return;
        }
        double magnitude;
        try {
            magnitude = Double.parseDouble(args[4].replace(',', '.'));
        } catch (NumberFormatException e) {
            Msg.send(sender, "Величина должна быть числом. Для «" + type.title() + "» это "
                    + (type.kind() == BonusType.Kind.EFFECT_LEVEL
                            ? "уровень эффекта, 1-" + (int) type.max()
                            : "множитель, 1.0-" + type.max()));
            return;
        }

        // Срок необязателен, и отличить его от имени гильдии можно только по
        // виду: «30m» — срок, «Драконы» — имя. Поэтому разбираем следующий
        // аргумент как срок и, если не вышло, считаем началом имени.
        Duration duration = parseDuration(args.length > 5 ? args[5] : null);
        int nameFrom = duration == null ? 5 : 6;
        if (args.length <= nameFrom) {
            Msg.send(sender, "Использование: " + BONUS_GRANT_USAGE);
            return;
        }
        String guildName = join(args, nameFrom);

        withGuild(sender, guildName, guild ->
                guilds.grantBonus(guild.id(), type, magnitude, duration, sender.getName())
                        .thenAccept(r -> sync(() -> Msg.result(sender, r))));
    }

    /** Найти гильдию по имени и сделать с ней что-то, иначе сказать, что её нет. */
    private void withGuild(CommandSender sender, String name, java.util.function.Consumer<StoredGuild> action) {
        var guild = guilds.byName(name);
        if (guild.isEmpty()) {
            Msg.send(sender, "Гильдия «" + name + "» не найдена");
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
        if (raw == null || raw.length() < 2) return null;
        char unit = Character.toLowerCase(raw.charAt(raw.length() - 1));
        String digits = raw.substring(0, raw.length() - 1);
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0) return null;
        return switch (unit) {
            case 'm', 'м' -> Duration.ofMinutes(value);
            case 'h', 'ч' -> Duration.ofHours(value);
            case 'd', 'д' -> Duration.ofDays(value);
            default -> null;
        };
    }

    private static String bonusTypeNames() {
        return java.util.Arrays.stream(BonusType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Строка бонуса для чата: что, сколько и до каких пор. */
    static String describe(GuildBonus bonus) {
        String left = bonus.permanent()
                ? "&aнавсегда"
                : "&eещё " + GuildService.humanDuration(bonus.remaining(Instant.now()));
        return "&f" + bonus.type().title() + " &7"
                + GuildService.describe(bonus.type(), bonus.magnitude())
                + " &8— " + left + " &8(выдал " + bonus.grantedBy() + ")";
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
            Msg.send(sender, "Настройка недоступна");
            return;
        }
        if (args.length < 3) {
            Msg.send(sender, "Урон по своим в пати сейчас: &f"
                    + (guildsPlugin.partyFriendlyFire() ? "&cразрешён" : "&aвыключен"));
            Msg.send(sender, "Переключить: /guild admin friendlyfire on|off");
            return;
        }
        String value = args[2].toLowerCase(Locale.ROOT);
        if (!YES.contains(value) && !NO.contains(value)) {
            Msg.send(sender, "Ожидается on или off");
            return;
        }
        boolean allowed = YES.contains(value);
        guildsPlugin.partyFriendlyFire(allowed);
        Msg.send(sender, allowed
                ? "Урон по своим в пати &cразрешён&7. Записано в config.yml."
                : "Урон по своим в пати &aвыключен&7. Записано в config.yml.");
        // Про гильдии говорим отдельно: их настройка своя, и человек, только
        // что переключивший общесерверную, вправе ждать, что она главнее.
        Msg.send(sender, "&7Гильдий это не касается — у каждой свой переключатель в /guild settings.");
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            Msg.send(sender, "Недостаточно прав");
            return;
        }
        // reload — единственная подкоманда без аргумента, поэтому проверяется
        // до общего требования трёх слов.
        if (args.length >= 2 && ADMIN_RELOAD.contains(args[1].toLowerCase(Locale.ROOT))) {
            if (plugin instanceof AurumGuildsPlugin guildsPlugin) {
                for (String line : guildsPlugin.reloadSettings()) Msg.send(sender, line);
            } else {
                Msg.send(sender, "Перезагрузка недоступна");
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
                    Msg.send(sender, "Гильдии с таким именем нет");
                    return;
                }
                List<UUID> before = guilds.memberUuids(guild.get().id());
                guilds.adminDisband(guild.get().id(), actor).thenAccept(r -> sync(() -> {
                    Msg.result(sender, r);
                    if (!r.ok()) return;
                    for (UUID uuid : before) {
                        Player member = Bukkit.getPlayer(uuid);
                        if (member != null) member.sendMessage(Msg.fail("Гильдия распущена администрацией"));
                    }
                }));
            }
            case "transfer", "передать" -> {
                if (args.length < 4) {
                    Msg.send(sender, "Использование: /guild admin transfer <гильдия> <ник>");
                    return;
                }
                // Имя гильдии — предпоследние аргументы, ник — последний:
                // имя может быть из нескольких слов, ник — никогда.
                String targetName = args[args.length - 1];
                String guildName = String.join(" ",
                        java.util.Arrays.copyOfRange(args, 2, args.length - 1));
                var guild = guilds.byName(guildName);
                if (guild.isEmpty()) {
                    Msg.send(sender, "Гильдии «" + guildName + "» нет");
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
        HelpBook.Builder help = HelpBook.titled("Гильдии", "/guild help")
                .add("/guild create <имя> <тег>", "создать гильдию, стать её лидером")
                .add("/guild join [имя]", "вступить — по приглашению или в открытую гильдию")
                .add("/guild leave", "выйти из своей гильдии")
                .add("/guild info [имя]", "состав, ранги и общак — свой или чужой гильдии")
                .add("/guild list [поиск]", "все гильдии сервера")
                .add("/guild invite <ник>", "позвать игрока к себе (лидер и офицеры)")
                .add("/guild kick <ник>", "выгнать участника (лидер и офицеры)")
                .add("/guild promote <ник>", "сделать офицером — он сможет звать и выгонять")
                .add("/guild demote <ник>", "снять офицера обратно в участники")
                .add("/guild transfer <ник>", "отдать гильдию другому: вы станете участником")
                .add("/guild disband", "распустить гильдию — спросит подтверждение")
                .add("/guild tag <тег>", "сменить тег — короткую метку у ника")
                .add("/guild settings", "меню настроек: описание, приём заявок, PvP своих")
                .add("/guild bank", "остаток общака; deposit и withdraw — внести и снять")
                .add("/guild bonuses", "какие усиления действуют на гильдию и сколько ещё")
                .add("/guild claim <регион>", "выдать всей гильдии доступ в приват — дом гильдии")
                .add("/guild claim remove <регион>", "отвязать регион от гильдии")
                .add("/g <сообщение>", "написать в чат гильдии, видят только свои");

        if (sender.hasPermission(PERMISSION_ADMIN)) adminEntries(help);

        send(sender, help.build().page(page(args)));
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
        send(sender, adminEntries(HelpBook.titled("Гильдии — администрирование", "/guild admin"))
                .build().page(1));
    }

    /**
     * Строки администрирования — одним списком на оба места, где они нужны.
     *
     * Иначе один и тот же перечень пришлось бы держать в двух копиях, и первая
     * же новая подкоманда попала бы ровно в одну из них.
     */
    private static HelpBook.Builder adminEntries(HelpBook.Builder into) {
        return into
                .add("/guild admin remove <ник>", "выгнать игрока из его гильдии")
                .add("/guild admin transfer <гильдия> <ник>", "назначить другого лидера")
                .add("/guild admin disband <гильдия>", "распустить чужую гильдию")
                .add("/guild admin bonus list <гильдия>", "что действует на гильдию")
                .add(BONUS_GRANT_USAGE, "выдать усиление; без срока — навсегда")
                .add("/guild admin bonus revoke <вид> <гильдия>", "снять усиление")
                .add("/guild admin friendlyfire [on|off]", "урон своим в пати, для всего сервера")
                .add("/guild admin reload", "перечитать config.yml без перезапуска");
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
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            if (!sender.hasPermission(PERMISSION_ADMIN)) options.remove("admin");
            return prefixed(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (List.of("invite", "kick", "promote", "demote", "transfer").contains(sub)) {
                return prefixed(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
            if (sub.equals("bank")) return prefixed(List.of("deposit", "withdraw", "log"), args[1]);
            if (sub.equals("admin")) {
                return prefixed(
                        List.of("remove", "transfer", "disband", "reload", "friendlyfire", "bonus"),
                        args[1]);
            }
            if (List.of("join", "info").contains(sub)) return prefixed(guildNames(), args[1]);
            if (List.of("help", "помощь").contains(sub)) return prefixed(List.of("1", "2"), args[1]);
        }
        if (args.length == 3 && sub.equals("admin")) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("remove")) {
                return prefixed(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
            }
            return prefixed(guildNames(), args[2]);
        }
        return List.of();
    }

    /** Из памяти и синхронно: автодополнение идёт в главном потоке. */
    private List<String> guildNames() {
        return guilds.guildNames();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
