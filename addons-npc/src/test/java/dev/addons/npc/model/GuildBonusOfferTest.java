package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class GuildBonusOfferTest {
    @Test
    void bonusTypesHaveThematicDefaultIconsAndDescriptions() {
        assertEquals(Material.DIAMOND_PICKAXE, GuildBonusType.MINING_SPEED.defaultIcon());
        assertEquals(Material.RABBIT_FOOT, GuildBonusType.MOVEMENT_SPEED.defaultIcon());
        assertEquals(Material.DIAMOND_ORE, GuildBonusType.BLOCK_DROPS.defaultIcon());
        assertEquals(Material.ENCHANTED_BOOK, GuildBonusType.MOB_DROPS.defaultIcon());
        assertEquals(Material.EXPERIENCE_BOTTLE, GuildBonusType.EXPERIENCE.defaultIcon());
        assertEquals("Спешка 2", GuildBonusType.MINING_SPEED.describe(2));
        assertEquals("×1.5", GuildBonusType.EXPERIENCE.describe(1.5));
    }

    @Test
    void validatesGuildApiMagnitudeRulesBeforeDisplayingAnOffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new GuildBonusOffer(0, GuildBonusType.MINING_SPEED, 1.5, 3600, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new GuildBonusOffer(0, GuildBonusType.MOVEMENT_SPEED, 21, 3600, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new GuildBonusOffer(0, GuildBonusType.BLOCK_DROPS, 0.9, 3600, 100));
        assertDoesNotThrow(() -> new GuildBonusOffer(0, GuildBonusType.BLOCK_DROPS, 3, 3600, 100));
    }

    @Test
    void zeroDurationMeansPermanentAndRanksUseGuildApiWeights() {
        GuildBonusOffer offer = new GuildBonusOffer(0, GuildBonusType.EXPERIENCE, 2, 0, 500);
        assertTrue(offer.permanent());
        assertEquals(0, GuildRankRequirement.MEMBER.weight());
        assertEquals(1, GuildRankRequirement.OFFICER.weight());
        assertEquals(2, GuildRankRequirement.LEADER.weight());
    }
}
