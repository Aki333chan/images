package ovh.aurumgg.guilds.core;

import java.util.UUID;

/**
 * Что должно произойти снаружи, когда гильдия меняется.
 *
 * <h2>Зачем эта прослойка</h2>
 *
 * Единственный сегодняшний потребитель — LuckPerms: у каждой гильдии своя
 * группа, на группе висит суффикс с тегом, участники наследуются от группы.
 * Но LuckPerms на сервере может не быть, и логика гильдий не должна об этом
 * даже знать. Поэтому она зовёт этот интерфейс, а кто за ним стоит — настоящий
 * мост или {@link #noop()} — решается один раз при старте.
 *
 * Побочный выигрыш: логика гильдий тестируется без LuckPerms, а тест может
 * проверить, что при смене тега суффикс обновляется РОВНО ОДИН РАЗ, на группе,
 * а не по разу на каждого участника.
 *
 * <h2>Поток и ошибки</h2>
 *
 * Зовётся из рабочего потока сервиса. Реализация обязана быть терпимой к
 * ошибкам: LuckPerms может не ответить, и это не повод отменять уже
 * состоявшееся вступление в гильдию. Гильдия важнее суффикса.
 */
public interface GuildHooks {

    /** Гильдия создана: завести группу и повесить на неё суффикс с тегом. */
    void guildCreated(long guildId, String tag);

    /** Гильдия распущена: удалить группу целиком. */
    void guildDeleted(long guildId);

    /**
     * Тег изменился: поправить суффикс НА ГРУППЕ.
     *
     * Именно на группе, а не у каждого участника: суффикс приходит игрокам
     * через наследование, и трогать их по отдельности не нужно ни при смене
     * тега, ни когда бы то ни было ещё.
     */
    void tagChanged(long guildId, String tag);

    /** Игрок вступил: добавить ему наследование от группы гильдии. */
    void memberJoined(long guildId, UUID player);

    /** Игрок вышел или исключён: убрать наследование. */
    void memberLeft(long guildId, UUID player);

    /**
     * Несколько получателей одного и того же события.
     *
     * Понадобилось, когда о вступлении и выходе стало нужно знать не только
     * LuckPerms (группа с суффиксом), но и WorldGuard (состав региона-дома).
     * Оба узнают об этом одним вызовом, и логика гильдий по-прежнему не знает
     * ни про того, ни про другого.
     *
     * Ошибка одного получателя не должна лишать остальных события: гильдия
     * важнее и суффикса, и региона.
     */
    static GuildHooks composite(GuildHooks... targets) {
        return new GuildHooks() {
            @Override
            public void guildCreated(long guildId, String tag) {
                for (GuildHooks target : targets) target.guildCreated(guildId, tag);
            }

            @Override
            public void guildDeleted(long guildId) {
                for (GuildHooks target : targets) target.guildDeleted(guildId);
            }

            @Override
            public void tagChanged(long guildId, String tag) {
                for (GuildHooks target : targets) target.tagChanged(guildId, tag);
            }

            @Override
            public void memberJoined(long guildId, UUID player) {
                for (GuildHooks target : targets) target.memberJoined(guildId, player);
            }

            @Override
            public void memberLeft(long guildId, UUID player) {
                for (GuildHooks target : targets) target.memberLeft(guildId, player);
            }
        };
    }

    /**
     * Заглушка — когда LuckPerms на сервере нет.
     *
     * Гильдии при этом работают полностью: команды, состав, банк, HUD, чат.
     * Не появляется только тег рядом с ником, потому что показывать его нечем.
     */
    static GuildHooks noop() {
        return new GuildHooks() {
            @Override
            public void guildCreated(long guildId, String tag) {}

            @Override
            public void guildDeleted(long guildId) {}

            @Override
            public void tagChanged(long guildId, String tag) {}

            @Override
            public void memberJoined(long guildId, UUID player) {}

            @Override
            public void memberLeft(long guildId, UUID player) {}
        };
    }
}
