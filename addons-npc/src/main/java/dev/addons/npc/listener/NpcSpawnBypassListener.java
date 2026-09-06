package dev.addons.npc.listener;

import dev.addons.npc.service.NpcManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Re-allows only AddonsNPC's marked CUSTOM spawns when WorldGuard blocked the region spawn event. */
public final class NpcSpawnBypassListener implements Listener {
    private final JavaPlugin plugin;
    private final NpcManager npcs;

    public NpcSpawnBypassListener(JavaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!event.isCancelled() || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        if (!plugin.getConfig().getBoolean("settings.ignore-worldguard-spawn-flags", true)) return;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard")) return;
        if (npcs.isManaged(event.getEntity())) event.setCancelled(false);
    }
}
