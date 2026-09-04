package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ActionDefinitionTest {
    @Test
    void parsesAndPreservesColonsInValue() {
        ActionDefinition action = ActionDefinition.parse("console:tellraw @a {\"text\":\"Hello\"}");
        assertEquals(ActionDefinition.Type.CONSOLE, action.type());
        assertEquals("tellraw @a {\"text\":\"Hello\"}", action.value());
        assertEquals("console:tellraw @a {\"text\":\"Hello\"}", action.serialize());
    }

    @Test
    void rejectsMalformedActions() {
        assertThrows(IllegalArgumentException.class, () -> ActionDefinition.parse("message"));
        assertThrows(IllegalArgumentException.class, () -> ActionDefinition.parse("message:"));
        assertThrows(IllegalArgumentException.class, () -> ActionDefinition.parse("unknown:value"));
    }

    @Test
    void parsesBuyerAction() {
        ActionDefinition action = ActionDefinition.parse("buyer:farmer");
        assertEquals(ActionDefinition.Type.BUYER, action.type());
        assertEquals("farmer", action.value());
    }

    @Test
    void parsesGuildTraderAliasesToStableSerialization() {
        ActionDefinition action = ActionDefinition.parse("guildtrader:boosts");
        assertEquals(ActionDefinition.Type.GUILD_TRADER, action.type());
        assertEquals("boosts", action.value());
        assertEquals("guildtrader:boosts", action.serialize());
        assertEquals(ActionDefinition.Type.GUILD_TRADER, ActionDefinition.parse("guildshop:boosts").type());
        assertEquals(ActionDefinition.Type.GUILD_TRADER, ActionDefinition.parse("guild_trader:boosts").type());
    }
}
