package ovh.aurumgg.guilds.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import ovh.aurumgg.guilds.api.*;
import ovh.aurumgg.guilds.core.*;

class SocialUiProviderTest {
    private MockedStatic<Bukkit> bukkit;
    private GuildService guilds;
    private PartyService parties;
    private SocialUiProvider ui;
    private Player player;
    private final UUID actor = UUID.randomUUID(), leader = UUID.randomUUID(), other = UUID.randomUUID();
    private StoredGuild guild;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        var manager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(actor);
        when(player.getName()).thenReturn("Member");
        when(player.isOnline()).thenReturn(true);
        guilds = mock(GuildService.class);
        parties = mock(PartyService.class);
        guild = new StoredGuild(1, "Guild", "GLD", leader, 100, Instant.EPOCH, GuildSettings.defaults(), List.of(
                new GuildMember(leader, "Leader", GuildRank.LEADER, Instant.EPOCH),
                new GuildMember(actor, "Member", GuildRank.MEMBER, Instant.EPOCH),
                new GuildMember(other, "Other", GuildRank.MEMBER, Instant.EPOCH)));
        when(guilds.byId(1)).thenReturn(Optional.of(guild));
        when(guilds.guildOf(actor)).thenReturn(Optional.of(guild));
        when(guilds.membership(actor)).thenReturn(Optional.of(new GuildMembership(1, "Guild", "GLD", GuildRank.MEMBER, Instant.EPOCH)));
        when(guilds.allGuilds()).thenReturn(List.of(guild));
        when(guilds.config()).thenReturn(GuildsConfig.fromMap(Map.of()));
        ui = new SocialUiProvider(null, guilds, parties);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void ordinaryPlayerSeesRosterButCannotForgeAdminOrKickAction() {
        var roster = ui.snapshot(player, "guild-members:1");
        assertEquals(3, roster.size());
        assertTrue(roster.stream().allMatch(c -> c.get("actions").isEmpty()));
        assertEquals("error.permission", ui.action(player, "guild-member:1:" + other, "guild_kick", Map.of()).join());
        assertEquals("error.permission", ui.action(player, "guild:1", "admin_guild_disband", Map.of()).join());
        verify(guilds, never()).kick(any(), any());
        verify(guilds, never()).adminDisband(anyLong(), anyString());
    }

    @Test void officerCanKickOnlyLowerRankAndCannotEditSettings() {
        when(guilds.membership(actor)).thenReturn(Optional.of(new GuildMembership(1, "Guild", "GLD", GuildRank.OFFICER, Instant.EPOCH)));
        var roster = ui.snapshot(player, "guild-members:1");
        assertFalse(roster.getFirst().get("actions").contains("guild_kick"));
        assertTrue(roster.getLast().get("actions").contains("guild_kick"));
        assertEquals("error.permission", ui.action(player, "guild:1", "guild_settings", Map.of()).join());
        verify(guilds, never()).updateSettings(any(), any());
    }

    @Test void adminRequiresBothPermissionsAndGetsBonusManagement() {
        when(player.hasPermission("aurumguilds.admin")).thenReturn(true);
        assertFalse(ui.snapshot(player, "guild").getFirst().get("actions").contains("admin_guild_disband"));
        when(player.hasPermission("aurumui.admin")).thenReturn(true);
        assertTrue(ui.snapshot(player, "guild").getFirst().get("actions").contains("admin_guild_disband"));
        assertEquals(BonusType.values().length, ui.snapshot(player, "guild-bonuses:1").size());
    }

    @Test void unauthenticatedPlayerCannotViewOrAct() {
        var manager = Bukkit.getPluginManager();
        var auth = mock(org.bukkit.plugin.Plugin.class);
        when(manager.getPlugin("AurumAuth")).thenReturn(auth);
        when(auth.isEnabled()).thenReturn(false);
        assertTrue(ui.snapshot(player, "guild").isEmpty());
        assertEquals("error.permission", ui.action(player, "guild:1", "guild_leave", Map.of()).join());
        verify(guilds, never()).leave(any());
    }

    @Test void rankRevokedAfterSnapshotIsCheckedAgainOnAction() {
        when(player.hasPermission("aurumui.admin")).thenReturn(true);
        when(player.hasPermission("aurumguilds.admin")).thenReturn(true);
        assertTrue(ui.snapshot(player, "guild").getFirst().get("actions").contains("admin_guild_disband"));
        when(player.hasPermission("aurumguilds.admin")).thenReturn(false);
        assertEquals("error.permission", ui.action(player, "guild:1", "admin_guild_disband", Map.of()).join());
    }

    @Test void nonMembersCannotReadOtherParties() {
        when(parties.allParties()).thenReturn(List.of(new PartyView(2, leader, List.of(leader, other))));
        assertTrue(ui.snapshot(player, "party-members:2").isEmpty());
        assertEquals("error.permission", ui.action(player, "party-member:2:" + other, "party_kick", Map.of()).join());
    }

    @Test void bankMenuRejectsInvalidAmountsAndMemberWithdrawal() {
        when(guilds.bankAvailable()).thenReturn(true);
        var economy = mock(EconomyBridge.class);
        when(guilds.economy()).thenReturn(economy);
        when(economy.format(anyDouble())).thenReturn("100");
        for (String amount : List.of("NaN", "Infinity", "-1", "0", "not-money")) {
            assertEquals("error.value", ui.action(player, "guild:1", "guild_deposit", Map.of("amount", amount)).join());
        }
        assertEquals("error.permission", ui.action(player, "guild:1", "guild_withdraw", Map.of("amount", "10")).join());
        verify(guilds, never()).deposit(any(), anyDouble());
        verify(guilds, never()).withdraw(any(), anyDouble());
    }
}
