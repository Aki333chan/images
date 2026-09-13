package dev.addons.npc.model;

import java.util.Locale;

/** Source that finances an NPC buyer. DEFAULT inherits the plugin-wide choice. */
public enum BuyerBudgetMode {
    DEFAULT,
    BUYER,
    TREASURY,
    LEGACY;

    public static BuyerBudgetMode parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("OWN") || normalized.equals("NPC_BUYER")) normalized = "BUYER";
        return valueOf(normalized);
    }
}
