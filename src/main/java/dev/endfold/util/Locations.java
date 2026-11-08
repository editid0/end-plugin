package dev.endfold.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Safe-location helpers used for arrivals and returns. Must be called on the server thread. */
public final class Locations {
    private Locations() {}

    public static boolean isPortal(Material m) {
        return m == Material.END_PORTAL || m == Material.END_GATEWAY || m == Material.NETHER_PORTAL;
    }

    private static boolean passable(Block b) {
        return b.isPassable() && !b.isLiquid() && !isPortal(b.getType()) && b.getType() != Material.FIRE && b.getType() != Material.SOUL_FIRE;
    }

    public static boolean standable(Location l) {
        World w = l.getWorld();
        if (w == null) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) return false;
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block ground = w.getBlockAt(x, y - 1, z);
        return passable(feet) && passable(head) && ground.getType().isSolid() && !isPortal(ground.getType()) && ground.getType() != Material.MAGMA_BLOCK
                && ground.getType() != Material.LAVA;
    }

    /**
     * Finds a standable spot near {@code origin} that is not inside a portal, searching rings outwards. Returns null if
     * none is found within {@code radius}.
     */
    public static Location nearbySafe(Location origin, int radius) {
        if (standable(origin)) return origin;
        World w = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        for (int r = 1; r <= radius; r++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        Location c = new Location(w, ox + dx + 0.5, oy + dy, oz + dz + 0.5, origin.getYaw(), origin.getPitch());
                        if (standable(c)) return c;
                    }
                }
            }
        }
        return null;
    }

    /** Highest safe location at the world's spawn, used as the last-resort fallback. */
    public static Location spawnOf(World w) {
        Location s = w.getSpawnLocation();
        Location safe = nearbySafe(s, 6);
        if (safe != null) return safe;
        Block top = w.getHighestBlockAt(s);
        return top.getLocation().add(0.5, 1, 0.5);
    }
}
