package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Что именно очищать в инвентаре.
 *
 * Полная очистка — отдельный флаг, а не «пустой список значит всё». Разница
 * между «стереть выбранное» и «стереть всё» здесь необратимая, и молчаливое
 * умолчание в такой операции рано или поздно сотрёт человеку инвентарь из-за
 * потерянного по дороге поля.
 *
 * Броня адресуется своим индексом, а не сырым номером слота Bukkit (36-39):
 * панель получает броню отдельным массивом и нумерует его с нуля, и пересчёт
 * на её стороне был бы лишним местом для ошибки.
 *
 * @param all     всё целиком: основной инвентарь, броня и вторая рука
 * @param slots   слоты основного инвентаря, 0-35 (0-8 — хотбар)
 * @param armor   индексы брони 0-3, порядок Bukkit: ботинки, поножи, нагрудник, шлем
 * @param offhand очистить вторую руку
 */
public record InventorySelection(boolean all, List<Integer> slots, List<Integer> armor, boolean offhand) {

    public InventorySelection {
        slots = List.copyOf(slots);
        armor = List.copyOf(armor);
    }

    public static InventorySelection everything() {
        return new InventorySelection(true, List.of(), List.of(), false);
    }

    /** Ничего не выбрано: очищать нечего, и это ошибка запроса, а не пустая операция. */
    public boolean isEmpty() {
        return !all && slots.isEmpty() && armor.isEmpty() && !offhand;
    }
}
