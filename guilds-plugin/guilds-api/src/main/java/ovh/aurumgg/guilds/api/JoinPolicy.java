package ovh.aurumgg.guilds.api;

import java.util.Locale;

/** Как в гильдию попадают. */
public enum JoinPolicy {

    /** Открыта: зайти может кто угодно командой, приглашение не нужно. */
    OPEN("guild.policy.open"),

    /** По приглашению — поведение по умолчанию. */
    INVITE("guild.policy.invite"),

    /** Закрыта: не принимает никого, даже по приглашению. */
    CLOSED("guild.policy.closed");

    private final String titleKey;

    JoinPolicy(String titleKey) {
        this.titleKey = titleKey;
    }

    public String titleKey() {
        return titleKey;
    }

    /** Следующее значение по кругу — для клика по иконке в меню настроек. */
    public JoinPolicy next() {
        return switch (this) {
            case OPEN -> INVITE;
            case INVITE -> CLOSED;
            case CLOSED -> OPEN;
        };
    }

    public static JoinPolicy parse(String raw) {
        if (raw == null) return INVITE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "open" -> OPEN;
            case "closed" -> CLOSED;
            default -> INVITE;
        };
    }

    public String storageName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
