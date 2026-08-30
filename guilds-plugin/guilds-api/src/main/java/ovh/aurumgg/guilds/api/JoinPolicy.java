package ovh.aurumgg.guilds.api;

import java.util.Locale;

/** Как в гильдию попадают. */
public enum JoinPolicy {

    /** Открыта: зайти может кто угодно командой, приглашение не нужно. */
    OPEN("открыта для всех"),

    /** По приглашению — поведение по умолчанию. */
    INVITE("по приглашению"),

    /** Закрыта: не принимает никого, даже по приглашению. */
    CLOSED("закрыта");

    private final String title;

    JoinPolicy(String title) {
        this.title = title;
    }

    public String title() {
        return title;
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
