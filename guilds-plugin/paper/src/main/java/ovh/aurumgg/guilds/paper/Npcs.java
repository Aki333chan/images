package ovh.aurumgg.guilds.paper;

import org.bukkit.entity.Player;

/**
 * NPC — не игрок, хотя событие о нём приходит такое же.
 *
 * Citizens и подобные плагины спавнят своих NPC настоящими сущностями игрока
 * и выбрасывают на каждого PlayerJoinEvent. Для гильдий это означало бы, что
 * ник и UUID торговца попадают в таблицу известных ников: дальше он всплывает
 * в автодополнении админ-команд и в поиске игрока, и однажды кого-то
 * приглашают в гильдию вместо человека.
 *
 * Проверяются метаданные «NPC» — так помечает своих Citizens. Зависимость на
 * него не нужна: на сервере без Citizens проверка просто всегда ложна.
 */
final class Npcs {

    private Npcs() {}

    static boolean isNpc(Player player) {
        return player.hasMetadata("NPC");
    }
}
