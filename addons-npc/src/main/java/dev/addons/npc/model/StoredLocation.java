package dev.addons.npc.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public static StoredLocation from(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location must have a world");
        }
        return new StoredLocation(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location resolve() {
        World resolvedWorld = Bukkit.getWorld(world);
        return resolvedWorld == null ? null : new Location(resolvedWorld, x, y, z, yaw, pitch);
    }
}

