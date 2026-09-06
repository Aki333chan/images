package dev.addons.npc.model;

public enum ClickMode {
    RIGHT,
    LEFT,
    BOTH;

    public boolean accepts(boolean rightClick) {
        return this == BOTH || (rightClick && this == RIGHT) || (!rightClick && this == LEFT);
    }

    public static ClickMode parse(String value) {
        return ClickMode.valueOf(value.trim().toUpperCase());
    }
}

