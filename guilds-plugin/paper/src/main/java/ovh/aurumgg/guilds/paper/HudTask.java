package ovh.aurumgg.guilds.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildMembership;
import ovh.aurumgg.guilds.api.PartyView;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.HealthGlyph;
import ovh.aurumgg.guilds.core.HudLines;
import ovh.aurumgg.guilds.core.HudModel;
import ovh.aurumgg.guilds.core.PartyService;
import ovh.aurumgg.guilds.core.StoredGuild;

/**
 * Раз в секунду собрать сайдбар каждому, кому есть что показать.
 *
 * Здесь только сбор данных из игры: кто в пати, сколько у кого здоровья, кто
 * из гильдии в сети. Во что это превращается — решает {@link HudLines} в
 * модуле core, и потому проверяется тестами.
 *
 * Задача синхронная, с главного потока: здоровье и состав читаются у живых
 * объектов Player, а Bukkit API не потокобезопасен.
 */
final class HudTask implements Runnable {

    private final GuildService guilds;
    private final PartyService parties;
    private final SidebarKeeper sidebar;

    HudTask(GuildService guilds, PartyService parties, SidebarKeeper sidebar) {
        this.guilds = guilds;
        this.parties = parties;
        this.sidebar = sidebar;
    }

    @Override
    public void run() {
        // Ответ на «работает ли банк» один на весь сервер и на весь проход, а
        // стоит он обращения к реестру служб Bukkit под общей блокировкой.
        // Спрашивать его на каждого игрока значило бы сотню таких обращений в
        // секунду ради одного и того же ответа.
        boolean bankAvailable = guilds.bankAvailable();
        for (Player player : Bukkit.getOnlinePlayers()) {
            HudModel model = modelFor(player, bankAvailable);
            if (model.isEmpty()) {
                // Ни пати, ни гильдии — показывать нечего, и пустая рамка на
                // экране раздражала бы всех, кто ими не пользуется.
                sidebar.hide(player);
                continue;
            }
            sidebar.show(player, HudLines.build(model));
        }
    }

    private HudModel modelFor(Player player, boolean bankAvailable) {
        List<HudModel.Member> members = new ArrayList<>();
        Optional<PartyView> party = parties.view(player.getUniqueId());
        party.ifPresent(view -> {
            for (UUID uuid : view.members()) {
                Player member = Bukkit.getPlayer(uuid);
                boolean online = member != null && member.isOnline();
                members.add(new HudModel.Member(
                        online ? member.getName() : offlineName(uuid),
                        online ? healthPercent(member) : 0,
                        online,
                        uuid.equals(view.leader())));
            }
        });

        Optional<GuildMembership> membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty()) {
            return new HudModel(members, parties.maxMembers(), null, null, null, 0, 0, null, List.of());
        }

        StoredGuild guild = guilds.byId(membership.get().guildId()).orElse(null);
        int total = guild == null ? 0 : guild.members().size();
        int online = 0;
        if (guild != null) {
            // По самому составу, а не по memberUuids(): тот собирает новый
            // список на каждый вызов, то есть на каждого игрока каждую
            // секунду, — а состав уже здесь, в руках.
            List<GuildMember> roster = guild.members();
            for (int i = 0; i < roster.size(); i++) {
                Player member = Bukkit.getPlayer(roster.get(i).uuid());
                if (member != null && member.isOnline()) online++;
            }
        }

        // Баланс показываем, только если банк вообще работает: строка
        // «Банк: 0» на сервере без Vault выглядит как пропавшие деньги.
        Double bank = bankAvailable && guild != null ? guild.bank() : null;

        return new HudModel(members, parties.maxMembers(), membership.get().guildName(),
                membership.get().guildTag(), membership.get().rank(), online, total, bank,
                bonusesOf(membership.get().guildId()));
    }

    /**
     * Действующие бонусы гильдии — в вид, пригодный для сайдбара.
     *
     * Остаток времени считается ЗДЕСЬ, от текущего момента, и в core уезжает
     * уже числом секунд: иначе строки сайдбара нельзя было бы проверить
     * тестом, не подменяя часы.
     *
     * Истёкшие сюда не попадают — {@link GuildService#bonuses} отсеивает их
     * при чтении, не дожидаясь уборки по расписанию.
     */
    private List<HudModel.Bonus> bonusesOf(long guildId) {
        List<GuildBonus> active = guilds.bonuses(guildId);
        // Бонусов нет почти у всех: выходим раньше, чем берём время и заводим
        // список.
        if (active.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<HudModel.Bonus> result = new ArrayList<>(active.size());
        for (GuildBonus bonus : active) {
            Long left = bonus.expiresAt() == null
                    ? null
                    : Math.max(0, Duration.between(now, bonus.expiresAt()).toSeconds());
            result.add(new HudModel.Bonus(
                    bonus.type().shortTitle(),
                    bonus.magnitude(),
                    bonus.type().kind() == BonusType.Kind.MULTIPLIER,
                    left));
        }
        return result;
    }

    /**
     * Доля здоровья.
     *
     * Максимум берётся у самого игрока, а не константой в двадцать: зелья и
     * эффекты его меняют, и с фиксированным значением индикатор у игрока с
     * усилением показывал бы больше сотни процентов.
     */
    private static double healthPercent(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? player.getHealth() : attribute.getValue();
        return HealthGlyph.percent(player.getHealth(), max);
    }

    private static String offlineName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "?" : name;
    }
}
