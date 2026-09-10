package ovh.aurumgg.guilds.paper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ovh.aurumgg.guilds.api.*;
import ovh.aurumgg.guilds.core.*;

/** Player-scoped social UI. All mutations use the same services as chat commands. */
final class SocialUiProvider {
    private final AurumGuildsPlugin plugin;
    private final GuildService guilds;
    private final PartyService parties;
    private final Map<UUID, Pending> confirmations = new HashMap<>();
    private record Pending(String action, String id, long deadline) {}

    SocialUiProvider(AurumGuildsPlugin plugin, GuildService guilds, PartyService parties) {
        this.plugin = plugin; this.guilds = guilds; this.parties = parties;
    }

    private boolean admin(Player player) {
        return player.hasPermission("aurumui.admin") && player.hasPermission("aurumguilds.admin");
    }

    private boolean available(Player player) {
        if (!player.isOnline() || player.hasMetadata("NPC")) return false;
        var auth = Bukkit.getPluginManager().getPlugin("AurumAuth");
        if (auth == null) return true;
        if (!auth.isEnabled()) return false;
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!type.getName().equals("ovh.aurumgg.auth.api.AurumAuthApi")) continue;
            try {
                Object provider = Bukkit.getServicesManager().load(type);
                return Boolean.TRUE.equals(type.getMethod("isAuthenticated", UUID.class).invoke(provider, player.getUniqueId()));
            } catch (ReflectiveOperationException error) { return false; }
        }
        return false;
    }

    List<Map<String, String>> snapshot(Player player, String requestedScope) {
        String[] paged = requestedScope.split("@", 2);
        String scope = paged[0];
        List<Map<String, String>> cards = allCards(player, scope);
        int page = paged.length > 1 ? Math.max(0, Integer.parseInt(paged[1])) : 0;
        int pages = Math.max(1, (cards.size() + 19) / 20);
        page = Math.min(page, pages - 1);
        List<Map<String, String>> result = new ArrayList<>(cards.subList(page * 20, Math.min(cards.size(), page * 20 + 20)));
        if (page > 0) result.add(nav(scope + "@" + (page - 1), "guild.ui.previous"));
        if (page + 1 < pages) result.add(nav(scope + "@" + (page + 1), "guild.ui.next"));
        return result;
    }

    private List<Map<String, String>> allCards(Player player, String scope) {
        if (!available(player)) return List.of();
        List<Map<String, String>> cards = new ArrayList<>();
        UUID actor = player.getUniqueId();
        if (scope.equals("guild")) {
            var own = guilds.guildOf(actor).orElse(null);
            if (own == null) cards.add(card("guild-home", player.getName(), "", createAllowed(player) ? "guild_create" : ""));
            if (own != null) cards.add(guildCard(player, own));
            for (StoredGuild invited : guilds.pendingGuilds(actor))
                cards.add(card("guild-invite:" + invited.id(), invited.name(), Msg.text("guild.ui.invitation"), "guild_join"));
            for (StoredGuild guild : guilds.allGuilds()) if (own == null || own.id() != guild.id()) cards.add(guildCard(player, guild));
        } else if (scope.startsWith("guild-details:")) {
            StoredGuild guild = guilds.byId(Long.parseLong(scope.substring(14))).orElse(null);
            if (guild != null) {
                cards.add(card("detail:policy", Msg.text("info.joinPolicy"), Msg.text(guild.settings().joinPolicy().titleKey()), ""));
                cards.add(card("detail:fire", Msg.text("info.friendlyFire"), Msg.text(guild.settings().friendlyFire() ? "menu.enabled" : "menu.disabled"), ""));
                cards.add(card("detail:bankAccess", Msg.text("guild.ui.bankAccess"), Msg.text(guild.settings().bankAccess().titleKey()), ""));
                cards.add(card("detail:motd", Msg.text("guild.ui.motd"), guild.settings().motd(), ""));
                for (GuildBonus bonus : guilds.bonuses(guild.id())) cards.add(card("detail:" + bonus.type(), Msg.text(bonus.type().titleKey()),
                        String.valueOf(bonus.magnitude()) + " · " + (bonus.expiresAt() == null ? Msg.text("guild.ui.permanent") : bonus.expiresAt().toString()), ""));
            }
        } else if (scope.startsWith("guild-bonuses:") && admin(player)) {
            long id = Long.parseLong(scope.substring(14));
            if (guilds.byId(id).isPresent()) for (BonusType type : BonusType.values()) {
                var bonus = guilds.bonuses(id).stream().filter(b -> b.type() == type).findFirst().orElse(null);
                var row = card("guild-bonus:" + id + ":" + type.name(), Msg.text(type.titleKey()),
                        bonus == null ? Msg.text("guild.ui.inactive") : bonus.magnitude() + " · " + (bonus.expiresAt() == null ? Msg.text("guild.ui.permanent") : bonus.expiresAt().toString()),
                        bonus == null ? "admin_bonus_grant" : "admin_bonus_grant,admin_bonus_revoke");
                row.put("magnitude", bonus == null ? String.valueOf(type.min()) : String.valueOf(bonus.magnitude()));
                cards.add(row);
            }
        } else if (scope.startsWith("guild-members:")) {
            StoredGuild guild = guilds.byId(Long.parseLong(scope.substring(14))).orElse(null);
            if (guild != null) for (GuildMember member : guild.members()) {
                GuildRank rank = guilds.membership(actor).filter(m -> m.guildId() == guild.id()).map(GuildMembership::rank).orElse(null);
                List<String> actions = new ArrayList<>();
                if (!actor.equals(member.uuid()) && rank != null) {
                    if (rank.canManageMembers() && rank.weight() > member.rank().weight()) actions.add("guild_kick");
                    if (rank == GuildRank.LEADER) {
                        actions.add(member.rank() == GuildRank.MEMBER ? "guild_promote" : "guild_demote");
                        actions.add("guild_transfer");
                    }
                }
                if (admin(player)) { actions.add("admin_guild_remove"); actions.add("admin_guild_transfer"); }
                cards.add(card("guild-member:" + guild.id() + ":" + member.uuid(), member.username(),
                        Msg.text(member.rank().titleKey()) + " · " + online(member.uuid()), String.join(",", actions)));
            }
        } else if (scope.startsWith("guild-players:") || scope.startsWith("party-players:")) {
            boolean guild = scope.startsWith("guild-");
            long id = Long.parseLong(scope.substring(scope.indexOf(':') + 1));
            boolean permitted = guild ? guilds.membership(actor).filter(m -> m.guildId() == id && m.rank().canManageMembers()).isPresent()
                    : parties.view(actor).filter(p -> p.id() == id).isPresent();
            if (permitted) Bukkit.getOnlinePlayers().stream().filter(p -> player.canSee(p) && available(p) && !p.equals(player))
                    .filter(p -> guild ? guilds.membership(p.getUniqueId()).map(m -> m.guildId() != id).orElse(true)
                            : parties.view(p.getUniqueId()).isEmpty())
                    .sorted(Comparator.comparing(Player::getName)).forEach(p -> cards.add(card(
                            (guild ? "guild-player:" : "party-player:") + id + ":" + p.getUniqueId(), p.getName(), "",
                            guild ? "guild_invite" : "party_invite")));
        } else if (scope.equals("party")) {
            var own = parties.view(actor).orElse(null);
            if (own == null) cards.add(card("party-home", player.getName(), "", "party_create"));
            else cards.add(partyCard(player, own));
            for (UUID inviter : parties.pendingInviters(actor)) cards.add(card("party-invite:" + inviter,
                    name(inviter), Msg.text("guild.ui.invitation"), "party_accept"));
            if (admin(player)) for (PartyView party : parties.allParties())
                if (own == null || own.id() != party.id()) cards.add(partyCard(player, party));
        } else if (scope.startsWith("party-members:")) {
            long id = Long.parseLong(scope.substring(14));
            PartyView party = parties.allParties().stream().filter(p -> p.id() == id).findFirst().orElse(null);
            if (party != null && (party.members().contains(actor) || admin(player))) for (UUID member : party.members()) {
                List<String> actions = new ArrayList<>();
                if (actor.equals(party.leader()) && !actor.equals(member)) { actions.add("party_kick"); actions.add("party_promote"); }
                if (admin(player)) actions.add("admin_party_remove");
                cards.add(card("party-member:" + id + ":" + member, name(member),
                        (member.equals(party.leader()) ? Msg.text("mc.rank.leader") + " · " : "") + online(member), String.join(",", actions)));
            }
        }
        return cards;
    }

    private Map<String, String> guildCard(Player player, StoredGuild guild) {
        UUID actor = player.getUniqueId();
        GuildRank rank = guilds.membership(actor).filter(m -> m.guildId() == guild.id()).map(GuildMembership::rank).orElse(null);
        List<String> actions = new ArrayList<>(List.of("guild_members", "guild_details"));
        if (rank != null) {
            actions.add("guild_leave");
            if (guilds.bankAvailable()) {
                actions.add("guild_deposit"); actions.add("guild_bank_log");
                if (guild.settings().bankAccess().allows(rank)) actions.add("guild_withdraw");
            }
            if (rank.canManageMembers()) actions.add("guild_players");
            if (rank == GuildRank.LEADER) actions.addAll(List.of("guild_settings", "guild_tag", "guild_disband"));
        } else if (guild.settings().joinPolicy() == JoinPolicy.OPEN) actions.add("guild_join");
        if (admin(player)) actions.addAll(List.of("guild_bonuses", "admin_guild_debug", "admin_guild_disband"));
        var result = card("guild:" + guild.id(), guild.name() + " [" + guild.tag() + "]",
                guild.members().size() + " · " + Msg.text(guild.settings().joinPolicy().titleKey())
                        + ((rank != null || admin(player)) && guilds.bankAvailable() ? " · " + guilds.economy().format(guild.bank()) : ""), String.join(",", actions));
        result.put("friendlyFire", String.valueOf(guild.settings().friendlyFire()));
        result.put("joinPolicy", guild.settings().joinPolicy().name());
        result.put("bankAccess", guild.settings().bankAccess().name());
        result.put("motd", guild.settings().motd()); result.put("tag", guild.tag());
        return result;
    }

    private Map<String, String> partyCard(Player player, PartyView party) {
        List<String> actions = new ArrayList<>(List.of("party_members"));
        if (party.members().contains(player.getUniqueId())) actions.addAll(List.of("party_players", "party_leave"));
        if (admin(player)) actions.addAll(List.of("admin_party_debug", "admin_party_disband"));
        return card("party:" + party.id(), name(party.leader()), party.size() + "/" + parties.maxMembers(), String.join(",", actions));
    }

    private Map<String, String> card(String id, String title, String summary, String actions) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", id); result.put("kind", "social"); result.put("title", title);
        result.put("summary", summary); result.put("actions", actions); return result;
    }
    private Map<String, String> nav(String scope, String label) {
        var result = card("page:" + scope, Msg.text(label), "", "social_page"); result.put("scope", scope); return result;
    }
    private String name(UUID uuid) { var p = Bukkit.getPlayer(uuid); return p != null ? p.getName() : Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString()); }
    private String online(UUID uuid) { return Msg.text(Bukkit.getPlayer(uuid) == null ? "guild.ui.offline" : "guild.ui.online"); }
    private boolean createAllowed(Player player) { return !guilds.config().requireCreatePermission() || player.hasPermission("aurumguilds.create"); }

    CompletableFuture<String> action(Player player, String id, String action, Map<String, String> args) {
        if (!available(player)) return CompletableFuture.completedFuture("error.permission");
        try {
            // Reconstruct the authorized card from current server state. Client action lists are never trusted.
            String kind = id.split(":", 2)[0];
            String scope = switch (kind) {
                case "guild-member" -> "guild-members:" + id.split(":")[1];
                case "guild-bonus" -> "guild-bonuses:" + id.split(":")[1];
                case "party-member" -> "party-members:" + id.split(":")[1];
                case "guild-player" -> "guild-players:" + id.split(":")[1];
                case "party-player" -> "party-players:" + id.split(":")[1];
                default -> kind.startsWith("guild") ? "guild" : "party";
            };
            boolean allowed = allCards(player, scope).stream().anyMatch(c -> c.get("id").equals(id)
                    && Arrays.asList(c.get("actions").split(",")).contains(action));
            if (!allowed) return CompletableFuture.completedFuture("error.permission");
            UUID actor = player.getUniqueId();
            if (Set.of("guild_disband", "admin_guild_disband", "admin_party_disband").contains(action)) {
                long now = System.currentTimeMillis();
                confirmations.entrySet().removeIf(e -> e.getValue().deadline() <= now || Bukkit.getPlayer(e.getKey()) == null);
                Pending pending = confirmations.remove(actor);
                if (pending == null || !pending.id().equals(id) || !pending.action().equals(action)) {
                    confirmations.put(actor, new Pending(action, id, now + 30_000));
                    return CompletableFuture.completedFuture("social.confirm");
                }
            }
            String[] parts = id.split(":");
            long group = parts.length > 1 && !kind.equals("party-invite") ? Long.parseLong(parts[1]) : -1;
            UUID target = parts.length > 2 && !kind.equals("guild-bonus") ? UUID.fromString(parts[2]) : null;
            Set<UUID> notify = new HashSet<>();
            if (Set.of("guild_join", "guild_leave", "guild_kick", "guild_transfer", "guild_promote", "guild_demote",
                    "guild_disband", "admin_guild_disband", "admin_guild_remove", "admin_guild_transfer").contains(action)) {
                guilds.guildOf(actor).ifPresent(g -> g.members().forEach(m -> notify.add(m.uuid())));
                guilds.byId(group).ifPresent(g -> g.members().forEach(m -> notify.add(m.uuid())));
            }
            if (Set.of("party_accept", "party_leave", "party_kick", "party_promote", "admin_party_remove", "admin_party_disband").contains(action)) {
                notify.addAll(parties.members(actor));
                parties.allParties().stream().filter(p -> p.id() == group).forEach(p -> notify.addAll(p.members()));
            }
            CompletableFuture<GuildActionResult> result;
            switch (action) {
                case "guild_create" -> result = guilds.create(actor, args.getOrDefault("name", ""), args.getOrDefault("tag", ""));
                case "guild_join" -> {
                    StoredGuild guild = guilds.byId(Long.parseLong(parts[1])).orElseThrow();
                    result = guilds.join(actor, guild.name());
                }
                case "guild_leave" -> result = guilds.leave(actor);
                case "guild_invite" -> {
                    UUID invited = target;
                    result = guilds.invite(actor, invited);
                    result.thenAccept(r -> { if (r.ok()) sync(() -> {
                        Player receiver = Bukkit.getPlayer(invited);
                        if (receiver != null) guilds.guildOf(actor).ifPresent(g -> receiver.sendMessage(Msg.ok(Msg.text("guild.inviteReceived", Map.of("player", player.getName(), "guild", g.name())))));
                    }); });
                }
                case "guild_kick" -> result = guilds.kick(actor, target);
                case "guild_promote", "guild_demote" -> result = guilds.setRank(actor, target, action.equals("guild_promote") ? GuildRank.OFFICER : GuildRank.MEMBER);
                case "guild_transfer" -> result = guilds.transfer(actor, target);
                case "guild_disband" -> result = guilds.disband(actor);
                case "guild_tag" -> result = guilds.changeTag(actor, args.getOrDefault("tag", ""));
                case "guild_settings" -> {
                    String motd = args.getOrDefault("motd", "");
                    if (motd.length() > 190 || motd.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                    String fire = args.getOrDefault("friendlyFire", "");
                    if (!fire.equals("true") && !fire.equals("false")) throw new IllegalArgumentException();
                    GuildSettings settings = new GuildSettings(Boolean.parseBoolean(fire), JoinPolicy.valueOf(args.get("joinPolicy")), motd, BankAccess.valueOf(args.get("bankAccess")));
                    result = guilds.updateSettings(actor, ignored -> settings);
                }
                case "guild_deposit", "guild_withdraw" -> {
                    double amount = Double.parseDouble(args.getOrDefault("amount", "").replace(',', '.'));
                    if (!Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException();
                    result = action.equals("guild_deposit") ? guilds.deposit(actor, amount) : guilds.withdraw(actor, amount);
                }
                case "guild_details", "guild_bank_log" -> {
                    // Fixed command executor; no arbitrary command text or console privileges.
                    String[] command = action.equals("guild_details") ? new String[]{"info", guilds.byId(group).orElseThrow().name()} : new String[]{"bank", "log"};
                    plugin.getCommand("guild").getExecutor().onCommand(player, plugin.getCommand("guild"), "guild", command);
                    return CompletableFuture.completedFuture("social.chat");
                }
                case "admin_guild_disband" -> result = guilds.adminDisband(group, player.getName());
                case "admin_guild_remove" -> result = guilds.adminRemove(name(target), player.getName());
                case "admin_guild_transfer" -> result = guilds.adminTransfer(group, name(target), player.getName());
                case "admin_bonus_grant" -> {
                    double magnitude = Double.parseDouble(args.getOrDefault("magnitude", ""));
                    if (!Double.isFinite(magnitude) || magnitude <= 0) throw new IllegalArgumentException();
                    String rawDuration = args.getOrDefault("duration", "");
                    java.time.Duration duration = rawDuration.equals("0") ? java.time.Duration.ZERO : ArgWords.duration(rawDuration);
                    if (duration == null || duration.isNegative()) throw new IllegalArgumentException();
                    result = guilds.grantBonus(group, BonusType.valueOf(parts[2]), magnitude, duration, player.getName());
                }
                case "admin_bonus_revoke" -> result = guilds.revokeBonus(group, BonusType.valueOf(parts[2]), player.getName());
                case "party_create" -> result = CompletableFuture.completedFuture(parties.create(actor));
                case "party_accept" -> result = CompletableFuture.completedFuture(parties.accept(actor, UUID.fromString(parts[1])));
                case "party_leave" -> result = CompletableFuture.completedFuture(parties.leave(actor));
                case "party_invite" -> {
                    GuildActionResult r = parties.invite(actor, target);
                    Player receiver = Bukkit.getPlayer(target);
                    if (r.ok() && receiver != null) receiver.sendMessage(Msg.ok(Msg.text("party.inviteReceived", Map.of("player", player.getName()))));
                    result = CompletableFuture.completedFuture(r);
                }
                case "party_kick" -> result = CompletableFuture.completedFuture(parties.kick(actor, target));
                case "party_promote" -> result = CompletableFuture.completedFuture(parties.promote(actor, target));
                case "admin_party_remove" -> result = CompletableFuture.completedFuture(parties.leave(target));
                case "admin_party_disband" -> result = CompletableFuture.completedFuture(parties.adminDisband(group));
                case "admin_guild_debug", "admin_party_debug" -> {
                    String detail = action.equals("admin_guild_debug")
                            ? guilds.byId(group).map(g -> "members=" + g.members().size() + ", leader=" + g.leader()
                                    + ", bank=" + g.bank() + ", bonuses=" + guilds.bonuses(g.id()).size()
                                    + ", regions=" + guilds.regions(g.id()).size()
                                    + ", membershipMismatches=" + g.members().stream().filter(m -> guilds.membership(m.uuid())
                                    .map(v -> v.guildId() != g.id()).orElse(true)).count()).orElse("missing")
                            : parties.allParties().stream().filter(p -> p.id() == group).findFirst()
                                    .map(p -> "leader=" + p.leader() + ", members=" + p.size() + ", max=" + parties.maxMembers()).orElse("missing");
                    player.sendMessage("[AurumUI] " + id + " · " + detail);
                    return CompletableFuture.completedFuture("social.chat");
                }
                default -> { return CompletableFuture.completedFuture("error.permission"); }
            }
            if (action.startsWith("admin_")) plugin.getLogger().info("Social UI: " + player.getName() + " " + action + " " + id);
            CompletableFuture<String> response = new CompletableFuture<>();
            result.whenComplete((outcome, error) -> sync(() -> {
                if (error != null) { response.complete("error.internal"); return; }
                if (player.isOnline()) Msg.result(player, outcome);
                if (outcome.ok()) {
                    if (action.equals("party_accept")) notify.addAll(parties.members(actor));
                    for (UUID uuid : notify) {
                        if (uuid.equals(actor)) continue;
                        Player member = Bukkit.getPlayer(uuid);
                        if (member != null) member.sendMessage(Msg.of(Msg.text("guild.ui.changed",
                                Map.of("player", player.getName(), "result", Msg.render(outcome)))));
                    }
                }
                response.complete(outcome.messageKey().equals("guild.join.confirmSwitch") ? "social.confirmSwitch" : outcome.ok() ? "social.done" : "social.failed");
            }));
            return response;
        } catch (IllegalArgumentException | NoSuchElementException | ArithmeticException error) {
            return CompletableFuture.completedFuture("error.value");
        }
    }

    void purge() {
        long now = System.currentTimeMillis();
        confirmations.entrySet().removeIf(e -> e.getValue().deadline() <= now || Bukkit.getPlayer(e.getKey()) == null);
    }
    private void sync(Runnable action) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action); }
}
