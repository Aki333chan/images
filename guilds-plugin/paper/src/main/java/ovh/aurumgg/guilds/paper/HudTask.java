package ovh.aurumgg.guilds.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            HudModel model = modelFor(player);
            if (model.isEmpty()) {
                // Ни пати, ни гильдии — показывать нечего, и пустая рамка на
                // экране раздражала бы всех, кто ими не пользуется.
                sidebar.hide(player);
                continue;
            }
            sidebar.show(player, HudLines.build(model));
        }
    }

    private HudModel modelFor(Player player) {
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
            return new HudModel(members, parties.maxMembers(), null, null, null, 0, 0, null);
        }

        StoredGuild guild = guilds.byId(membership.get().guildId()).orElse(null);
        int total = guild == null ? 0 : guild.members().size();
        int online = 0;
        for (UUID uuid : guilds.memberUuids(membership.get().guildId())) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null && member.isOnline()) online++;
        }

        // Баланс показываем, только если банк вообще работает: строка
        // «Банк: 0» на сервере без Vault выглядит как пропавшие деньги.
        Double bank = guilds.bankAvailable() && guild != null ? guild.bank() : null;

        return new HudModel(members, parties.maxMembers(), membership.get().guildName(),
                membership.get().guildTag(), membership.get().rank(), online, total, bank);
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
