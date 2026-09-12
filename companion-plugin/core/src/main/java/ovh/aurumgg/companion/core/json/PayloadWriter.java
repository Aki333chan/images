package ovh.aurumgg.companion.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.GiveResult;
import ovh.aurumgg.companion.core.model.GuildActionOutcome;
import ovh.aurumgg.companion.core.model.GuildBonusInfo;
import ovh.aurumgg.companion.core.model.GuildInfo;
import ovh.aurumgg.companion.core.model.GuildMembershipInfo;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.IpRecordInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.JailedPlayer;
import ovh.aurumgg.companion.core.model.JailsInfo;
import ovh.aurumgg.companion.core.model.PlayerJailState;
import ovh.aurumgg.companion.core.model.KnownPlayer;
import ovh.aurumgg.companion.core.model.KnownPlayersPage;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;
import ovh.aurumgg.companion.core.model.PluginToggle;

/** Сериализация ответов плагина. Формат зафиксирован в docs/companion.md. */
public final class PayloadWriter {

    private PayloadWriter() {}

    /**
     * Исторический список игроков — страницей.
     *
     * {@code alias} и {@code registered} могут быть null: первое значит
     * «ника нет или нет EssentialsX», второе — «плагина авторизации нет».
     * Отдаём именно null, а не пустую строку и не false: панель должна
     * отличать «неизвестно» от «нет», иначе она пометит всех
     * незарегистрированными на сервере без плагина авторизации.
     */
    public static String knownPlayers(KnownPlayersPage page) {
        List<String> items = new ArrayList<>(page.players().size());
        for (KnownPlayer p : page.players()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("uuid", Json.string(p.uuid().toString()));
            fields.put("name", Json.string(p.name()));
            fields.put("alias", p.alias() == null ? "null" : Json.string(p.alias()));
            fields.put("op", p.op() ? "true" : "false");
            fields.put("online", p.online() ? "true" : "false");
            fields.put("registered",
                    p.registered() == null ? "null" : (p.registered() ? "true" : "false"));
            fields.put("lastSeen", Json.number(p.lastSeen()));
            items.add(Json.object(fields));
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("players", Json.array(items));
        body.put("total", Json.number(page.total()));
        body.put("authAvailable", page.authAvailable() ? "true" : "false");
        return Json.object(body);
    }

    /** Адреса игрока. Пусто — либо плагина авторизации нет, либо не записано. */
    public static String ipHistory(List<IpRecordInfo> records) {
        List<String> items = new ArrayList<>(records.size());
        for (IpRecordInfo record : records) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ip", Json.string(record.ip()));
            fields.put("firstSeen", Json.number(record.firstSeen()));
            fields.put("lastSeen", Json.number(record.lastSeen()));
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("addresses", Json.array(items)));
    }

    public static String players(List<PlayerInfo> players) {
        List<String> items = new ArrayList<>(players.size());
        for (PlayerInfo p : players) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("uuid", Json.string(p.uuid().toString()));
            fields.put("name", Json.string(p.name()));
            fields.put("health", Json.number(p.health()));
            fields.put("maxHealth", Json.number(p.maxHealth()));
            fields.put("world", Json.string(p.world()));
            fields.put("x", Json.number(round(p.x())));
            fields.put("y", Json.number(round(p.y())));
            fields.put("z", Json.number(round(p.z())));
            fields.put("ping", Json.number(p.ping()));
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("players", Json.array(items)));
    }

    public static String inventory(InventoryInfo inventory) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("items", itemArray(inventory.items()));
        root.put("armor", itemArray(inventory.armor()));
        root.put("offhand", inventory.offhand() == null ? "null" : item(inventory.offhand()));
        return Json.object(root);
    }

    public static String plugins(List<PluginInfo> plugins) {
        List<String> items = new ArrayList<>(plugins.size());
        for (PluginInfo p : plugins) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", Json.string(p.name()));
            fields.put("version", Json.string(p.version()));
            fields.put("enabled", p.enabled() ? "true" : "false");
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("plugins", Json.array(items)));
    }

    public static String permissions(PermissionsInfo info) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("primaryGroup", Json.string(info.primaryGroup()));

        List<String> groups = new ArrayList<>(info.groups().size());
        for (String group : info.groups()) groups.add(Json.string(group));
        root.put("groups", Json.array(groups));

        List<String> nodes = new ArrayList<>(info.permissions().size());
        for (PermissionsInfo.PermissionEntry entry : info.permissions()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("permission", Json.string(entry.permission()));
            fields.put("value", entry.value() ? "true" : "false");
            nodes.add(Json.object(fields));
        }
        root.put("permissions", Json.array(nodes));
        return Json.object(root);
    }

    public static String balance(BalanceInfo info) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("balance", Json.number(info.balance()));
        fields.put("formatted", Json.string(info.formatted()));
        fields.put("currency", Json.string(info.currency()));
        return Json.object(fields);
    }

    /**
     * Результат начисления или списания.
     *
     * ok = false — это не HTTP-ошибка: провайдер вправе отказать (не хватило
     * денег, отрицательная сумма), и панели нужен именно его текст отказа,
     * а не подменённый нами.
     */
    public static String balanceChange(BalanceChange change) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ok", change.ok() ? "true" : "false");
        fields.put("error", Json.string(change.error()));
        fields.put("balanceBefore", Json.number(change.before()));
        fields.put("balanceAfter", Json.number(change.after()));
        fields.put("formatted", Json.string(change.formatted()));
        return Json.object(fields);
    }

    public static String economy(EconomySummary summary) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("total", Json.number(summary.total()));
        root.put("totalFormatted", Json.string(summary.totalFormatted()));
        root.put("currency", Json.string(summary.currency()));
        // null, а не ноль: «не считали» и «ноль игроков» — разные ответы, и
        // панель по ним рисует разное.
        if (summary.playersCounted() != null) {
            root.put("playersCounted", Json.number(summary.playersCounted()));
        }
        EconomySummary.Ledger ledger = summary.ledger();
        root.put("source", Json.string(ledger == null ? "vault" : "aurum"));
        if (ledger != null) {
            root.put("treasury", Json.number(ledger.treasury()));
            root.put("treasuryFormatted", Json.string(ledger.treasuryFormatted()));
            root.put("moneySupply", Json.number(ledger.moneySupply()));
            root.put("moneySupplyFormatted", Json.string(ledger.moneySupplyFormatted()));
            root.put("taxesCollected", Json.number(ledger.taxesCollected()));
            root.put("taxesFormatted", Json.string(ledger.taxesFormatted()));
        }

        List<String> top = new ArrayList<>(summary.top().size());
        for (EconomySummary.TopEntry entry : summary.top()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", Json.string(entry.name()));
            fields.put("uuid", Json.string(entry.uuid()));
            fields.put("balance", Json.number(entry.balance()));
            fields.put("formatted", Json.string(entry.formatted()));
            top.add(Json.object(fields));
        }
        root.put("top", Json.array(top));
        return Json.object(root);
    }

    public static String pluginToggle(PluginToggle toggle) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ok", toggle.ok() ? "true" : "false");
        fields.put("enabled", toggle.enabled() ? "true" : "false");
        fields.put("error", Json.string(toggle.error()));
        return Json.object(fields);
    }

    /** Варианты автодополнения. */
    public static String suggestions(List<String> suggestions) {
        List<String> items = new ArrayList<>(suggestions.size());
        for (String suggestion : suggestions) items.add(Json.string(suggestion));
        return Json.object(Map.of("suggestions", Json.array(items)));
    }

    /**
     * Ошибка с машиночитаемым кодом. Панель по коду решает, что показать:
     * «поставьте LuckPerms» — это не то же самое, что «игрок не найден»,
     * а разбирать русский текст на той стороне никуда не годится.
     */
    /**
     * Кому принадлежит одноразовый код входа в панель.
     *
     * Сам код в ответ НЕ кладётся: он уже израсходован, и повторять его в
     * теле ответа — значит без нужды оставить его ещё и в логах панели.
     */
    public static String webToken(java.util.UUID playerUuid, String username) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("uuid", Json.string(playerUuid.toString()));
        fields.put("name", Json.string(username));
        return Json.object(fields);
    }

    /** Токен сброса пароля: он же одноразовый ключ, поэтому в лог его не пишут. */
    public static String passwordReset(ovh.aurumgg.companion.core.model.PasswordReset reset) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", Json.string(reset.username()));
        fields.put("token", Json.string(reset.token()));
        fields.put("expiresAt", String.valueOf(reset.expiresAtEpochMs()));
        return Json.object(fields);
    }

    public static String guilds(List<GuildInfo> guilds) {
        List<String> items = new ArrayList<>(guilds.size());
        for (GuildInfo guild : guilds) items.add(guild(guild));
        return Json.object(Map.of("guilds", Json.array(items)));
    }

    public static String guild(GuildInfo guild) {
        Map<String, String> fields = new LinkedHashMap<>();
        // id числом, а не строкой: он же ключ в маршрутах панели.
        fields.put("id", Json.number(guild.id()));
        fields.put("name", Json.string(guild.name()));
        fields.put("tag", Json.string(guild.tag()));
        fields.put("leaderUuid", Json.string(guild.leaderUuid()));
        fields.put("leaderName", Json.string(guild.leaderName()));
        fields.put("memberCount", Json.number(guild.memberCount()));
        fields.put("bankBalance", Json.number(guild.bankBalance()));
        fields.put("createdAt", Json.number(guild.createdAtEpochMs()));

        List<String> members = new ArrayList<>(guild.members().size());
        for (GuildInfo.Member member : guild.members()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("uuid", Json.string(member.uuid()));
            row.put("name", Json.string(member.name()));
            row.put("rank", Json.string(member.rank()));
            row.put("joinedAt", Json.number(member.joinedAtEpochMs()));
            members.add(Json.object(row));
        }
        fields.put("members", Json.array(members));
        return Json.object(fields);
    }

    public static String guildMembership(GuildMembershipInfo membership) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("guildId", Json.number(membership.guildId()));
        fields.put("guildName", Json.string(membership.guildName()));
        fields.put("guildTag", Json.string(membership.guildTag()));
        fields.put("rank", Json.string(membership.rank()));
        fields.put("joinedAt", Json.number(membership.joinedAtEpochMs()));
        return Json.object(Map.of("membership", Json.object(fields)));
    }

    public static String guildOutcome(GuildActionOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ok", outcome.ok() ? "true" : "false");
        // message — КЛЮЧ, а не фраза: переводит его панель, на языке того, кто
        // смотрит. Две карты подстановок рядом — см. GuildActionOutcome.
        fields.put("message", Json.string(outcome.messageKey()));
        fields.put("values", strings(outcome.values()));
        fields.put("keys", strings(outcome.keys()));
        return Json.object(fields);
    }

    /**
     * Тюрьмы и сидящие.
     *
     * {@code available: false} и пустые списки — EssentialsX нет либо его
     * версия несовместима. Панель отличает это от «тюрем не создано»: в
     * первом случае она даёт ввести имя руками, во втором говорить не о чем.
     */
    public static String jails(JailsInfo info) {
        List<String> names = new ArrayList<>(info.jails().size());
        for (String jail : info.jails()) names.add(Json.string(jail));

        List<String> jailed = new ArrayList<>(info.jailed().size());
        for (JailedPlayer player : info.jailed()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("uuid", Json.string(player.uuid().toString()));
            fields.put("name", Json.string(player.name()));
            fields.put("jail", Json.string(player.jail()));
            // 0 — срок не назначен: сидит до отмены. Отдельного поля-флага для
            // этого не нужно, ноль в прошлом не бывает.
            fields.put("releaseAt", Json.number(player.releaseAt()));
            jailed.add(Json.object(fields));
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("available", info.available() ? "true" : "false");
        body.put("jails", Json.array(names));
        body.put("jailed", Json.array(jailed));
        return Json.object(body);
    }

    /**
     * Сидит ли конкретный игрок.
     *
     * {@code known: false} — EssentialsX такого игрока не знает: ник с
     * опечаткой либо человек, который ни разу не заходил. Это НЕ то же
     * самое, что «не сидит», и панель должна их различать: посадить того,
     * кого сервер не знает, всё равно не выйдет.
     */
    public static String playerJail(PlayerJailState state) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("known", state.known() ? "true" : "false");
        body.put("jailed", state.jailed() ? "true" : "false");
        body.put("jail", Json.string(state.jail()));
        body.put("releaseAt", Json.number(state.releaseAt()));
        body.put("online", state.online() ? "true" : "false");
        return Json.object(body);
    }

    /** Карта «имя → строка» как объект JSON. */
    private static String strings(Map<String, String> map) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            fields.put(entry.getKey(), Json.string(entry.getValue()));
        }
        return Json.object(fields);
    }

    /**
     * Итог выдачи — построчно, в том же порядке, в каком пришёл список.
     *
     * Ответ всегда 200, даже когда не легло ничего: запрос выполнен, а вот
     * инвентарь оказался полон или в строке опечатка. Это не отказ сервера, и
     * панели нужно показать человеку именно построчную картину.
     */
    public static String giveResults(List<GiveResult> results) {
        List<String> items = new ArrayList<>(results.size());
        for (GiveResult r : results) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("id", Json.string(r.id()));
            fields.put("requested", Json.number(r.requested()));
            fields.put("given", Json.number(r.given()));
            fields.put("error", Json.string(r.error()));
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("results", Json.array(items)));
    }

    public static String guildBonuses(List<GuildBonusInfo> bonuses) {
        List<String> items = new ArrayList<>(bonuses.size());
        for (GuildBonusInfo bonus : bonuses) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("type", Json.string(bonus.type()));
            fields.put("title", Json.string(bonus.title()));
            fields.put("magnitude", Json.number(bonus.magnitude()));
            fields.put("multiplier", bonus.multiplier() ? "true" : "false");
            // 0 — постоянный. Отдельного флага нет: он и дата однажды
            // разойдутся, и оба случая читаются как порча данных.
            fields.put("expiresAt", Json.number(bonus.expiresAtEpochMs()));
            fields.put("grantedBy", Json.string(bonus.grantedBy()));
            fields.put("grantedAt", Json.number(bonus.grantedAtEpochMs()));
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("bonuses", Json.array(items)));
    }

    public static String error(String message, String code) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("error", Json.string(message));
        fields.put("code", Json.string(code));
        return Json.object(fields);
    }

    public static String error(String message) {
        return Json.object(Map.of("error", Json.string(message)));
    }

    public static String ok() {
        return Json.object(Map.of("ok", "true"));
    }

    private static String itemArray(List<ItemInfo> items) {
        List<String> parts = new ArrayList<>(items.size());
        for (ItemInfo item : items) parts.add(item(item));
        return Json.array(parts);
    }

    private static String item(ItemInfo item) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("slot", Json.number(item.slot()));
        fields.put("id", Json.string(item.id()));
        fields.put("count", Json.number(item.count()));
        fields.put("displayName", Json.string(item.displayName()));

        Map<String, String> enchants = new LinkedHashMap<>();
        if (item.enchantments() != null) {
            item.enchantments().forEach((key, level) -> enchants.put(key, Json.number(level)));
        }
        fields.put("enchantments", Json.object(enchants));

        List<String> lore = new ArrayList<>();
        if (item.lore() != null) {
            for (String line : item.lore()) lore.add(Json.string(line));
        }
        fields.put("lore", Json.array(lore));
        return Json.object(fields);
    }

    /** Координаты с точностью до сотых — большего в интерфейсе не нужно. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
