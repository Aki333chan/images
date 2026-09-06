package org.ChisaO_o.simpleSlots;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

record BlockKey(UUID worldId, int x, int y, int z) {
    static BlockKey from(Location location) {
        if (location == null) {
            return null;
        }
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return new BlockKey(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
