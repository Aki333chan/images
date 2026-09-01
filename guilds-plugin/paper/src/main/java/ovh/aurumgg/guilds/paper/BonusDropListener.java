package ovh.aurumgg.guilds.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Множители бонусов: добыча из блоков, добыча с мобов, опыт.
 *
 * <h2>Дробный множитель — это шанс, а не округление</h2>
 *
 * Множитель 1.5 на одном алмазе не может дать полтора алмаза. Округлять вниз
 * значило бы, что купленный бонус ×1.5 не делает ничего для всего, что падает
 * поштучно, — а это почти вся ценная добыча. Округлять вверх — что ×1.1
 * работает как ×2.
 *
 * Поэтому целая часть выдаётся всегда, а дробная разыгрывается: при ×1.5 к
 * каждой единице добавляется ещё одна с вероятностью 50%. На длинной дистанции
 * это ровно обещанные полтора раза, а на одном блоке — честная монетка.
 *
 * <h2>Почему именно эти события</h2>
 *
 * {@code BlockDropItemEvent} — уже после того, как сервер решил, что выпадет:
 * с учётом удачи, шёлкового касания и того, что блок вообще роняет предметы.
 * Считать от него безопаснее, чем от типа блока: правила выпадения знает игра,
 * а не мы.
 *
 * {@code EntityDeathEvent#getDrops()} — то же самое для мобов, и там же лежит
 * опыт за убийство.
 *
 * <h2>Чего множитель НЕ трогает</h2>
 *
 * Предметы с прочностью, зачарованиями и именами не удваиваются: клонировать
 * чужой именной меч с зачарованиями — не «повышенная добыча», а дублирование
 * вещей. Множится только то, что складывается в стопку и одинаково.
 */
final class BonusDropListener implements Listener {

    private final GuildService guilds;

    BonusDropListener(GuildService guilds) {
        this.guilds = guilds;
    }

    /**
     * MONITOR и ignoreCancelled: мы не решаем, выпадет ли что-то, — только
     * сколько. Вмешиваться раньше плагинов защиты территорий незачем, а
     * отменённое ими выпадение множить тем более.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        double multiplier = guilds.multiplier(event.getPlayer().getUniqueId(), BonusType.BLOCK_DROPS);
        if (multiplier <= 1.0) return;

        // Список события менять нельзя — он про уже созданные сущности. Лишнее
        // выкидываем в мир сами, рядом с блоком.
        for (Item item : new ArrayList<>(event.getItems())) {
            ItemStack extra = bonusPortion(item.getItemStack(), multiplier);
            if (extra != null) {
                item.getWorld().dropItemNaturally(item.getLocation(), extra);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        double drops = guilds.multiplier(killer.getUniqueId(), BonusType.MOB_DROPS);
        if (drops > 1.0) {
            List<ItemStack> extra = new ArrayList<>();
            for (ItemStack stack : event.getDrops()) {
                ItemStack portion = bonusPortion(stack, drops);
                if (portion != null) extra.add(portion);
            }
            event.getDrops().addAll(extra);
        }

        double experience = guilds.multiplier(killer.getUniqueId(), BonusType.EXPERIENCE);
        if (experience > 1.0) {
            event.setDroppedExp(scaled(event.getDroppedExp(), experience));
        }
    }

    /**
     * Опыт из всего остального: печи, руда, рыбалка, бутылочки.
     *
     * Отдельным событием, потому что опыт за убийство приходит в
     * {@code EntityDeathEvent} и сюда не попадает.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExperience(PlayerExpChangeEvent event) {
        double multiplier = guilds.multiplier(event.getPlayer().getUniqueId(), BonusType.EXPERIENCE);
        if (multiplier <= 1.0 || event.getAmount() <= 0) return;
        event.setAmount(scaled(event.getAmount(), multiplier));
    }

    /**
     * Добавка к стопке или null, если добавлять нечего.
     *
     * Предметы с метаданными не множатся: у них прочность, зачарования и имя,
     * и копия — это дубликат чужой вещи, а не добыча.
     */
    private static ItemStack bonusPortion(ItemStack stack, double multiplier) {
        if (stack == null || stack.getAmount() <= 0) return null;
        if (stack.hasItemMeta() && stack.getItemMeta() != null && stack.getItemMeta().hasEnchants()) {
            return null;
        }
        int extra = scaled(stack.getAmount(), multiplier) - stack.getAmount();
        if (extra <= 0) return null;

        ItemStack portion = stack.clone();
        portion.setAmount(extra);
        return portion;
    }

    /**
     * Умножение с розыгрышем дробной части.
     *
     * 3 × 1.5 = 4.5 → всегда 4, и ещё один с вероятностью 50%.
     */
    private static int scaled(int amount, double multiplier) {
        double exact = amount * multiplier;
        int whole = (int) Math.floor(exact);
        double fraction = exact - whole;
        if (fraction > 0 && ThreadLocalRandom.current().nextDouble() < fraction) whole++;
        return whole;
    }
}
