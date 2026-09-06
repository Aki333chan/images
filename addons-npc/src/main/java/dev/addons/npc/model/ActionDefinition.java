package dev.addons.npc.model;

import java.util.Locale;

public record ActionDefinition(Type type, String value) {
    public enum Type {
        MESSAGE,
        CONSOLE,
        PLAYER,
        SHOP,
        BUYER,
        GUILD_TRADER,
        SOUND,
        TITLE
    }

    public static ActionDefinition parse(String serialized) {
        int separator = serialized.indexOf(':');
        if (separator < 1) {
            throw new IllegalArgumentException("Action must use type:value format");
        }
        String rawType = serialized.substring(0, separator).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (rawType.equals("GUILDTRADER") || rawType.equals("GUILDSHOP")) rawType = "GUILD_TRADER";
        String value = serialized.substring(separator + 1).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Action value cannot be empty");
        }
        return new ActionDefinition(Type.valueOf(rawType), value);
    }

    public String serialize() {
        return (type == Type.GUILD_TRADER ? "guildtrader" : type.name().toLowerCase(Locale.ROOT)) + ':' + value;
    }
}
