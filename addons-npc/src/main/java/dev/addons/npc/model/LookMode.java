package dev.addons.npc.model;

import java.util.Locale;

public enum LookMode {
    HEAD,
    BODY;

    public static LookMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Look mode must be head or body.");
        }
    }
}
