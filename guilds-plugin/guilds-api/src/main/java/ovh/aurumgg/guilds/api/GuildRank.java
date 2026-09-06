package ovh.aurumgg.guilds.api;

/**
 * Ранг участника гильдии.
 *
 * Рангов ровно три, и это сознательный предел. Каждый следующий ранг — это не
 * одна строчка в перечислении, а перекрёстные вопросы вида «может ли офицер
 * второго уровня кикнуть офицера первого» в каждой команде. Три ранга
 * покрывают то, ради чего гильдии заводят: один отвечает за всё, несколько
 * помогают с набором, остальные просто состоят.
 */
public enum GuildRank {

    /**
     * Лидер. Один на гильдию.
     *
     * Только он распускает гильдию, передаёт лидерство и меняет настройки.
     * Эти три действия необратимы или меняют саму гильдию, поэтому делить их
     * с кем-то ещё нельзя.
     */
    LEADER("mc.rank.leader", 2),

    /**
     * Офицер. Набирает и выгоняет, но не решает судьбу гильдии.
     */
    OFFICER("mc.rank.officer", 1),

    /** Участник. */
    MEMBER("mc.rank.member", 0);

    private final String titleKey;
    private final int weight;

    GuildRank(String titleKey, int weight) {
        this.titleKey = titleKey;
        this.weight = weight;
    }

    /** Ключ названия — тот же, что знает панель. См. BonusType.titleKey. */
    public String titleKey() {
        return titleKey;
    }

    /** Старшинство: больше — старше. Сравнивать ранги напрямую по ordinal нельзя. */
    public int weight() {
        return weight;
    }

    public boolean atLeast(GuildRank other) {
        return weight >= other.weight;
    }

    /** Может приглашать и выгонять. */
    public boolean canManageMembers() {
        return atLeast(OFFICER);
    }

    /**
     * Может распустить гильдию или передать лидерство.
     *
     * Отдельно от {@link #canManageMembers()} намеренно: офицер набирает людей,
     * но не решает, существовать ли гильдии.
     */
    public boolean canDisband() {
        return this == LEADER;
    }

    /** Разбор из строки в БД. Неизвестное — участник: понижение безопаснее повышения. */
    public static GuildRank parse(String raw) {
        if (raw == null) return MEMBER;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "leader" -> LEADER;
            case "officer" -> OFFICER;
            default -> MEMBER;
        };
    }

    /** Как ранг пишется в БД. */
    public String storageName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
