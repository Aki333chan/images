package ovh.aurumgg.companion.core.model;

import java.util.List;
import java.util.Map;

/**
 * Предмет в слоте.
 *
 * @param slot        номер слота в своём разделе инвентаря
 * @param id          идентификатор материала, напр. "minecraft:diamond_sword"
 * @param count       размер стака
 * @param displayName пользовательское имя предмета или null
 * @param enchantments зачарования: ключ -> уровень
 * @param lore        строки описания
 */
public record ItemInfo(
        int slot,
        String id,
        int count,
        String displayName,
        Map<String, Integer> enchantments,
        List<String> lore) {}
