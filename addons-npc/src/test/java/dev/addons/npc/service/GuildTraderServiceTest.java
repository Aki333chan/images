package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GuildTraderServiceTest {
    @Test
    void formatsPermanentAndConfiguredDurationsForTheGui() {
        assertEquals("навсегда", GuildTraderService.humanDuration(null));
        assertEquals("30м", GuildTraderService.humanDuration(Duration.ofMinutes(30)));
        assertEquals("6ч", GuildTraderService.humanDuration(Duration.ofHours(6)));
        assertEquals("7д", GuildTraderService.humanDuration(Duration.ofDays(7)));
        assertEquals("2н", GuildTraderService.humanDuration(Duration.ofDays(14)));
    }
}
