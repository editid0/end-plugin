package dev.endfold.world;

import dev.endfold.model.EndSpace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;

/**
 * Builds and loads EndSpace worlds with the vanilla End generator. Each world gets the key
 * {@code endspace:<shortId>} (world name {@code endspace_<shortId>}, folder {@code <level>/dimensions/endspace/<shortId>}).
 * Because the dimension type is the vanilla End type, every world has its own vanilla dragon fight, gateways and
 * End Cities (verified on Purpur 26.3, see docs/TESTING.md).
 */
public final class WorldGenerator {

    public static NamespacedKey keyOf(EndSpace space) { return new NamespacedKey(WorldRepository.NAMESPACE, space.shortId()); }

    /** Loads (or generates on first use) the space's world. Must run on the server thread, outside of any tick event. */
    public World load(EndSpace space) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Worlds can only be loaded on the server thread");
        World existing = Bukkit.getWorld(keyOf(space));
        if (existing != null) return existing;
        WorldCreator wc = WorldCreator.ofKey(keyOf(space)).environment(World.Environment.THE_END).seed(space.seed());
        World w = wc.createWorld();
        if (w == null) throw new IllegalStateException("Server refused to create world " + space.worldName());
        return w;
    }

    /** The vanilla arrival point: obsidian platform at (100, 48, 0), player at (100.5, 49, 0.5) facing west. */
    public static Location arrival(World w) { return new Location(w, 100.5, 49, 0.5, 90f, 0f); }

    /** Builds (or repairs) the vanilla-style 5x5 obsidian arrival platform with three blocks of air above it. */
    public static void buildPlatform(World w) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                Block floor = w.getBlockAt(x, 48, z);
                if (floor.getType() != Material.OBSIDIAN) floor.setType(Material.OBSIDIAN, false);
                for (int y = 49; y <= 51; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }
}
