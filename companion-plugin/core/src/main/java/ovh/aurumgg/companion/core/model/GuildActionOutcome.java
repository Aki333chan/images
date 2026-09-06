package ovh.aurumgg.companion.core.model;

import java.util.Map;

/**
 * Чем закончилось вмешательство администрации в гильдию.
 *
 * ЗДЕСЬ КЛЮЧ, А НЕ ГОТОВАЯ ФРАЗА, и это изменение против прежней версии.
 * Раньше плагин гильдий отдавал сюда собранное по-русски предложение, панель
 * показывала его как есть — и сотрудник, переключивший панель на английский,
 * всё равно видел русский ответ. Язык читателя знает панель, а не игровой
 * сервер, поэтому наружу уезжает ключ.
 *
 * Подстановок две карты по той же причине, что и в самом плагине гильдий: в
 * {@code values} лежит то, что переводить нельзя (ник, имя гильдии), в
 * {@code keys} — то, что само является ключом словаря (вид бонуса). Сложи их
 * вместе — и панель покажет «mc.bonus.blockDrops» вместо названия.
 *
 * @param ok         получилось ли
 * @param messageKey ключ сообщения; словарь у панели свой
 * @param values     подстановки как есть
 * @param keys       подстановки, значения которых — ключи словаря панели
 */
public record GuildActionOutcome(
        boolean ok, String messageKey, Map<String, String> values, Map<String, String> keys) {

    public GuildActionOutcome(boolean ok, String messageKey) {
        this(ok, messageKey, Map.of(), Map.of());
    }

    public GuildActionOutcome(boolean ok, String messageKey, Map<String, String> values) {
        this(ok, messageKey, values, Map.of());
    }
}
