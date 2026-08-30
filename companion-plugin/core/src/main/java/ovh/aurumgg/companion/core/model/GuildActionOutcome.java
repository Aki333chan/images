package ovh.aurumgg.companion.core.model;

/**
 * Чем закончилось вмешательство администрации в гильдию.
 *
 * Текст сообщения приходит из самого плагина гильдий: там он написан рядом с
 * условием, при котором возникает, и панели остаётся только показать его.
 *
 * @param ok      получилось ли
 * @param message что показать человеку
 */
public record GuildActionOutcome(boolean ok, String message) {}
