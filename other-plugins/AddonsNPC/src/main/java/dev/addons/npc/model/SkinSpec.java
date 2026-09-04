package dev.addons.npc.model;

public record SkinSpec(Type type, String value) {
    public enum Type {
        NONE,
        PLAYER,
        URL
    }

    public static SkinSpec none() {
        return new SkinSpec(Type.NONE, "");
    }
}

