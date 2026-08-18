package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ovh.aurumgg.companion.core.model.ItemInfo;

/**
 * Превращение стака Bukkit в модель, которую понимает панель.
 *
 * Вынесено из BukkitGameBridge, потому что тем же кодом читаются инвентари
 * офлайн-игроков через InvSee++: одинаковое описание предмета в обоих случаях
 * важнее, чем экономия на одном классе.
 */
final class ItemMapper {

    private ItemMapper() {}

    /** null для пустого слота — пустые слоты в JSON не передаются. */
    static ItemInfo describe(int slot, ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
            return null;
        }
        String displayName = null;
        List<String> lore = List.of();
        Map<String, Integer> enchantments = new LinkedHashMap<>();

        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    displayName = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
                }
                if (meta.hasLore() && meta.getLore() != null) {
                    List<String> plain = new ArrayList<>();
                    for (String line : meta.getLore()) {
                        plain.add(org.bukkit.ChatColor.stripColor(line));
                    }
                    lore = plain;
                }
            }
        }
        stack.getEnchantments()
                .forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));

        return new ItemInfo(
                slot,
                stack.getType().getKey().toString(),
                stack.getAmount(),
                displayName,
                enchantments,
                lore);
    }
}
