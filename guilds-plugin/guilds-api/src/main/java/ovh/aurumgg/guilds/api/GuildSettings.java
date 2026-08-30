package ovh.aurumgg.guilds.api;

/**
 * Настройки гильдии, которые меняет лидер через меню.
 *
 * <h2>Почему это record, а не карта «ключ-значение»</h2>
 *
 * Карта была бы гибче, но за гибкость пришлось бы заплатить тем, что опечатка
 * в имени настройки перестала бы быть ошибкой компиляции. Настроек немного, и
 * добавить сюда поле дешевле, чем однажды искать, почему «frendlyFire» ничего
 * не делает.
 *
 * Добавление новой настройки — это поле здесь, колонка в таблице и иконка в
 * меню. Меню строится по списку пунктов, а не по жёсткой раскладке, поэтому
 * третье место — одна запись в списке.
 *
 * @param friendlyFire  бить ли своих внутри гильдии
 * @param joinPolicy    как в гильдию попадают
 * @param motd          описание гильдии; показывается участникам при входе
 * @param bankAccess    кто может снимать из банка
 */
public record GuildSettings(
        boolean friendlyFire,
        JoinPolicy joinPolicy,
        String motd,
        BankAccess bankAccess) {

    /** Значения для только что созданной гильдии. */
    public static GuildSettings defaults() {
        // friendlyFire выключен: чаще всего гильдию заводят, чтобы вместе
        // воевать против других, и первое же случайное попадание по своему —
        // это ссора на ровном месте.
        return new GuildSettings(false, JoinPolicy.INVITE, "", BankAccess.LEADER_ONLY);
    }

    public GuildSettings withFriendlyFire(boolean value) {
        return new GuildSettings(value, joinPolicy, motd, bankAccess);
    }

    public GuildSettings withJoinPolicy(JoinPolicy value) {
        return new GuildSettings(friendlyFire, value, motd, bankAccess);
    }

    public GuildSettings withMotd(String value) {
        return new GuildSettings(friendlyFire, joinPolicy, value == null ? "" : value, bankAccess);
    }

    public GuildSettings withBankAccess(BankAccess value) {
        return new GuildSettings(friendlyFire, joinPolicy, motd, value);
    }
}
