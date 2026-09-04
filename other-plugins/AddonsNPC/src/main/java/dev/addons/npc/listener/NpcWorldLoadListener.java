package dev.addons.npc.listener;

import dev.addons.npc.service.NpcManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Synchronizes definitions belonging to worlds loaded after AddonsNPC. */
public final class NpcWorldLoadListener implements Listener {
    private final JavaPlugin plugin;
    private final NpcManager manager;

    public NpcWorldLoadListener(JavaPlugin plugin, NpcManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> manager.syncWorld(event.getWorld()));
    }
}
