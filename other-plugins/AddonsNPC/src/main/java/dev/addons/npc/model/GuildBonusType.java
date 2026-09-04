package dev.addons.npc.model;

import java.util.Locale;
import org.bukkit.Material;

/** AurumGuilds bonus names mirrored as stable configuration keys. */
public enum GuildBonusType {
    MINING_SPEED("Скорость добычи", "Спешка", Kind.EFFECT_LEVEL, 30.0, Material.DIAMOND_PICKAXE),
    MOVEMENT_SPEED("Скорость передвижения", "Скорость", Kind.EFFECT_LEVEL, 20.0, Material.RABBIT_FOOT),
    BLOCK_DROPS("Добыча из блоков", "Блоки", Kind.MULTIPLIER, 30.0, Material.DIAMOND_ORE),
    MOB_DROPS("Добыча с мобов", "Мобы", Kind.MULTIPLIER, 30.0, Material.ENCHANTED_BOOK),
    EXPERIENCE("Опыт", "Опыт", Kind.MULTIPLIER, 30.0, Material.EXPERIENCE_BOTTLE);

    public enum Kind { EFFECT_LEVEL, MULTIPLIER }

    private final String title;
    private final String shortTitle;
    private final Kind kind;
    private final double maximum;
    private final Material icon;

    GuildBonusType(String title, String shortTitle, Kind kind, double maximum, Material icon) {
        this.title = title;
        this.shortTitle = shortTitle;
        this.kind = kind;
        this.maximum = maximum;
        this.icon = icon;
    }

    public String title() { return title; }
    public String shortTitle() { return shortTitle; }
    public Kind kind() { return kind; }
    public double maximum() { return maximum; }
    public Material defaultIcon() { return icon; }

    public double validateMagnitude(double value) {
        if (!Double.isFinite(value) || value < 1.0 || value > maximum) {
            throw new IllegalArgumentException("Magnitude for " + name().toLowerCase(Locale.ROOT)
                    + " must be between 1 and " + format(maximum) + '.');
        }
        if (kind == Kind.EFFECT_LEVEL && value != Math.rint(value)) {
            throw new IllegalArgumentException("Effect level must be a whole number.");
        }
        return value;
    }

    public String describe(double value) {
        return kind == Kind.EFFECT_LEVEL ? shortTitle + " " + format(value) : "×" + format(value);
    }

    public static GuildBonusType parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Guild bonus type is required.");
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown guild bonus type: " + raw);
        }
    }

    private static String format(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
