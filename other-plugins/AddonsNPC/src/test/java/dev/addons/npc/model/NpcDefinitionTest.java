package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.EntityType;

class NpcDefinitionTest {
    @Test
    void normalizesValidIds() {
        assertEquals("city_guide-1", NpcDefinition.normalizeId("City_Guide-1"));
    }

    @Test
    void rejectsUnsafeIds() {
        assertThrows(IllegalArgumentException.class, () -> NpcDefinition.normalizeId("has spaces"));
        assertThrows(IllegalArgumentException.class, () -> NpcDefinition.normalizeId("../npc"));
        assertThrows(IllegalArgumentException.class, () -> NpcDefinition.normalizeId(""));
    }

    @Test
    void acceptsLivingNpcTypesAndRejectsPlayers() {
        assertEquals(true, NpcDefinition.isSupportedEntityType(EntityType.COW));
        assertEquals(true, NpcDefinition.isSupportedEntityType(EntityType.VILLAGER));
        assertEquals(false, NpcDefinition.isSupportedEntityType(EntityType.PLAYER));
    }

    @Test
    void clampsVisibilityAndParsesLookMode() {
        NpcDefinition npc = new NpcDefinition("guide", null, "Guide");
        npc.visibilityRange(900);
        npc.nameVisibilityRange(-5);
        npc.lookMode(LookMode.parse("head"));

        assertEquals(512.0, npc.visibilityRange());
        assertEquals(0.0, npc.nameVisibilityRange());
        assertEquals(LookMode.HEAD, npc.lookMode());
    }
}
