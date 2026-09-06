package dev.addons.npc.model;

public enum DialogueMode {
    ALL,
    RANDOM,
    SEQUENTIAL;

    public static DialogueMode parse(String value) {
        return DialogueMode.valueOf(value.trim().toUpperCase());
    }
}

