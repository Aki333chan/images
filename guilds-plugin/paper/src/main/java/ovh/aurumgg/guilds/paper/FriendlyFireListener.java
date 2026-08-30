package ovh.aurumgg.guilds.paper;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Не бить своих, если гильдия так решила.
 *
 * <h2>Про приоритет</h2>
 *
 * HIGH и {@code ignoreCancelled = true}: отменять урон, который и без нас уже
 * отменён (защищённой территорией, например), незачем, а вмешиваться раньше
 * плагинов регионов не стоит — они лучше знают, что происходит на их земле.
 *
 * <h2>Про снаряды</h2>
 *
 * Стрела и зелье — это отдельная сущность, и {@code getDamager()} возвращает
 * саму стрелу, а не лучника. Без разбора снаряда настройка выключала бы только
 * удар в упор, а стрелять по своим было бы можно — то есть работала бы ровно
 * наполовину и незаметно.
 */
final class FriendlyFireListener implements Listener {

    private final GuildService guilds;

    FriendlyFireListener(GuildService guilds) {
        this.guilds = guilds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = attacker(event);
        if (attacker == null || attacker.equals(victim)) return;

        if (!guilds.sameGuild(attacker.getUniqueId(), victim.getUniqueId())) return;
        if (guilds.friendlyFireAllowed(attacker.getUniqueId())) return;

        event.setCancelled(true);
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
