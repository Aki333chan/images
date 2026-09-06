package dev.addons.npc.listener;

import dev.addons.npc.service.NpcManager;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/** Removes tagged NPC entities whose configuration was deleted while their chunk was unloaded. */
public final class OrphanNpcCleanupListener implements Listener {
    private final NpcManager manager;

    public OrphanNpcCleanupListener(NpcManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.reconcileEntities(List.of(event.getChunk().getEntities()));
    }
}
