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
import ovh.aurumgg.guilds.core.BonusMath;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Множители бонусов: добыча из блоков, добыча с мобов, опыт.
 *
 * <h2>Почему именно эти события: бонус СКЛАДЫВАЕТСЯ с зачарованиями</h2>
 *
 * {@code BlockDropItemEvent} приходит уже после того, как сервер решил, что
 * выпадет: с учётом удачи, шёлкового касания и того, роняет ли блок предметы
 * вообще. У события даже нет ссылки на инструмент — оно несёт готовый список
 * сущностей. Значит, спорить о порядке нечего: зачарование применено раньше,
 * всегда.
 *
 * Ровно поэтому кирка с «Удачей III» и гильдейский ×1.5 дают примерно
 * четыре-пять алмазов, а не полтора: бонус умножает результат зачарования, а
 * не сырую единицу. Гильдейское усиление тем ценнее, чем лучше инструмент.
 *
 * {@code EntityDeathEvent#getDrops()} — то же самое для мобов (там уже учтена
 * «Добыча»), и там же лежит опыт за убийство.
 *
 * Сама арифметика — в {@link BonusMath}: там же объяснено, почему дробная
 * часть разыгрывается, а не округляется, и там это проверяется тестами.
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
            for (ItemStack extra : bonusPortions(item.getItemStack(), multiplier)) {
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
                extra.addAll(bonusPortions(stack, drops));
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
     * Добавка к стопке — СТОПКАМИ, а не одной кучей.
     *
     * <h2>Почему список, а не один ItemStack</h2>
     *
     * Добавка легко перерастает предельный размер стопки: девять алмазов с
     * «Удачей III» и множителем ×30 — это 261 сверху, а в стопку влезает 64.
     * {@code ItemStack} с количеством больше предела — вещь, которой в игре не
     * существует: клиент рисует её неправильно, а часть предметов при первой
     * же попытке положить их в сундук просто исчезает.
     *
     * Поэтому добавка режется по {@code getMaxStackSize()} самого предмета:
     * у ведра он равен единице, у зелий — тройке, и брать константу 64 нельзя.
     *
     * <h2>Про зачарования</h2>
     *
     * Пропускаются зачарования НА САМОМ ВЫПАВШЕМ ПРЕДМЕТЕ, а не на
     * инструменте: зачарованный меч, упавший с зомби, не удваивается — копия
     * чужой вещи с прочностью и именем это дубликат, а не добыча. К
     * зачарованиям кирки, которой копали, это отношения не имеет: их результат
     * уже лежит в {@code getAmount()} и множится как обычно.
     *
     * @return пустой список, если добавлять нечего
     */
    private static List<ItemStack> bonusPortions(ItemStack stack, double multiplier) {
        if (stack == null || stack.getAmount() <= 0) return List.of();
        if (stack.hasItemMeta() && stack.getItemMeta() != null && stack.getItemMeta().hasEnchants()) {
            return List.of();
        }
        int extra = BonusMath.extra(stack.getAmount(), multiplier, ThreadLocalRandom.current());
        if (extra <= 0) return List.of();

        int perStack = Math.max(1, stack.getMaxStackSize());
        List<ItemStack> portions = new ArrayList<>();
        while (extra > 0) {
            ItemStack portion = stack.clone();
            portion.setAmount(Math.min(extra, perStack));
            portions.add(portion);
            extra -= portion.getAmount();
        }
        return portions;
    }

    private static int scaled(int amount, double multiplier) {
        return BonusMath.scaled(amount, multiplier, ThreadLocalRandom.current());
    }
}
