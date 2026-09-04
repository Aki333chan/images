package org.ChisaO_o.gladiatorArena;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scoreboard.Objective;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DisplayRegressionTest {
    @Test void defaultConfigHidesNumbersAndLimitsOccludedHolograms() {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertTrue(config.getBoolean("settings.sidebar.hide_scores"));
            assertFalse(config.getBoolean("settings.holograms.see_through_walls"));
            assertEquals(32.0, config.getDouble("settings.holograms.view_distance_blocks"));
            assertEquals(0.65, config.getDouble("settings.holograms.betting_scale"));
            assertEquals(0, config.getInt("settings.rewards.winner_experience"));
            assertEquals(0, config.getInt("settings.rewards.final_winner_experience"));
            assertEquals("points", config.getString("settings.rewards.experience_mode"));
        } catch (Exception exception) {
            fail(exception);
        }
    }

    @Test void paperBlankNumberFormatIsAppliedToSidebar() {
        AtomicReference<Object> applied = new AtomicReference<>();
        Objective objective = (Objective) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {Objective.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("numberFormat") && arguments != null) applied.set(arguments[0]);
                return null;
            });
        assertTrue(ScoreboardNumberFormatter.hideScores(objective));
        assertInstanceOf(NumberFormat.class, applied.get());
    }

    @Test void viewDistanceInBlocksUsesMinecraftDisplayRangeUnits() {
        assertEquals(0.5f, GladiatorArena.hologramViewRange(32.0f));
        assertEquals(1.0f, GladiatorArena.hologramViewRange(64.0f));
    }

    @Test void oddsAndTeamSidesRemainCorrect() {
        assertEquals("3.0", GladiatorArena.formatOdds(150.0, 50.0, 0.0));
        assertEquals("1.5", GladiatorArena.formatOdds(150.0, 100.0, 0.0));
        assertEquals("2.7", GladiatorArena.formatOdds(150.0, 50.0, 10.0));
        assertEquals("—", GladiatorArena.formatOdds(150.0, 0.0, 0.0));
    }

    @Test void regularAndFinalExperienceRewardsAreIndependent() {
        assertEquals(25, GladiatorArena.experienceReward(false, 25, 100));
        assertEquals(100, GladiatorArena.experienceReward(true, 25, 100));
        assertEquals(GladiatorArena.ExperienceMode.POINTS, GladiatorArena.ExperienceMode.parse("points"));
        assertEquals(GladiatorArena.ExperienceMode.LEVELS, GladiatorArena.ExperienceMode.parse("LEVELS"));
        assertEquals(GladiatorArena.ExperienceMode.POINTS, GladiatorArena.ExperienceMode.parse("invalid"));
    }

    @Test void onlySameBlockFinalHologramsAreDeduplicated() {
        List<String> holograms = new ArrayList<>(List.of("west:old", "east:only", "west:newest"));
        assertEquals(1, GladiatorArena.keepNewestByKey(holograms, value -> value.substring(0, value.indexOf(':'))));
        assertEquals(List.of("east:only", "west:newest"), holograms);
        assertEquals(0, GladiatorArena.keepNewestByKey(holograms, value -> value.substring(0, value.indexOf(':'))));
    }

    @Test void finalStatsRemovalNeverTargetsBettingHolograms() {
        assertTrue(GladiatorArena.matchesHologramType("main:final:2", "main", "final:"));
        assertFalse(GladiatorArena.matchesHologramType("main:bet:red", "main", "final:"));
        assertFalse(GladiatorArena.matchesHologramType("other:final:2", "main", "final:"));
        assertTrue(GladiatorArena.matchesHologramType("main:bet:red", "main", null));
    }
}
