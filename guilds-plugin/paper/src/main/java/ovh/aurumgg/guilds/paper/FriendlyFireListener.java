package ovh.aurumgg.guilds.paper;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import java.util.function.BooleanSupplier;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.PartyService;

/**
 * Не бить своих.
 *
 * <h2>Два разных «своих»</h2>
 *
 * <b>Гильдия</b> решает за себя: настройка лежит в самой гильдии, и лидер
 * меняет её в меню. У разных гильдий она разная, и это правильно — одни
 * собираются воевать вместе, другие устраивают спарринги.
 *
 * <b>Пати</b> — наоборот, настройка сервера, одна на всех. Пати живёт час и
 * собирается на ходу; заводить в ней собственную политику значило бы спрашивать
 * об этом при каждом сборе группы. По умолчанию урон по своим в пати выключен:
 * группу собирают, чтобы идти вместе, и первое же случайное попадание — это
 * ссора на ровном месте.
 *
 * Правило простое: своим считается тот, кто с тобой в пати ИЛИ в гильдии, и
 * достаточно одной из двух защит, чтобы удар не прошёл.
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
    private final PartyService parties;
    /**
     * Поставщик, а не флаг: настройку меняют на ходу — командой и
     * перезагрузкой конфига, — и снимок при создании слушателя устарел бы
     * сразу же.
     */
    private final BooleanSupplier partyFriendlyFire;

    FriendlyFireListener(GuildService guilds, PartyService parties, BooleanSupplier partyFriendlyFire) {
        this.guilds = guilds;
        this.parties = parties;
        this.partyFriendlyFire = partyFriendlyFire;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = attacker(event);
        if (attacker == null || attacker.equals(victim)) return;

        if (protectedFromEachOther(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Защищает ли хоть одна из двух связей.
     *
     * Именно «хоть одна»: гильдия, разрешившая себе спарринги, не должна
     * отменять защиту внутри пати — иначе включённый в гильдии свой огонь
     * молча снимал бы её и с временной группы, куда человек зашёл на один бой.
     */
    private boolean protectedFromEachOther(java.util.UUID attacker, java.util.UUID victim) {
        if (!partyFriendlyFire.getAsBoolean() && parties.sameParty(attacker, victim)) return true;
        return guilds.sameGuild(attacker, victim) && !guilds.friendlyFireAllowed(attacker);
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
