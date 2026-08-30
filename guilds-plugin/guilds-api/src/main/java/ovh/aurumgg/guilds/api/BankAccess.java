package ovh.aurumgg.guilds.api;

import java.util.Locale;

/**
 * Кто может тратить из банка гильдии.
 *
 * Вкладывать может любой участник всегда — это его собственные деньги, и
 * запрещать здесь нечего. Настраивается только расход.
 */
public enum BankAccess {

    /** Только лидер — значение по умолчанию. */
    LEADER_ONLY("только лидер"),

    /** Лидер и офицеры. */
    LEADER_AND_OFFICERS("лидер и офицеры");

    private final String title;

    BankAccess(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public BankAccess next() {
        return this == LEADER_ONLY ? LEADER_AND_OFFICERS : LEADER_ONLY;
    }

    /** Хватает ли ранга, чтобы снимать. */
    public boolean allows(GuildRank rank) {
        return this == LEADER_ONLY ? rank == GuildRank.LEADER : rank.canManageMembers();
    }

    public static BankAccess parse(String raw) {
        if (raw == null) return LEADER_ONLY;
        return "leader_and_officers".equalsIgnoreCase(raw.trim()) ? LEADER_AND_OFFICERS : LEADER_ONLY;
    }

    public String storageName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
