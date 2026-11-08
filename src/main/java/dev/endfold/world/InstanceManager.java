package dev.endfold.world;

import dev.endfold.model.EndSpace;
import dev.endfold.service.SpaceDirectory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Maps between EndSpaces and Bukkit worlds and answers "which instance is this?" questions for listeners.
 * World loading itself is delegated to {@link WorldLifecycleManager}.
 */
public final class InstanceManager {
    public static final NamespacedKey VANILLA_END = NamespacedKey.minecraft("the_end");

    private final SpaceDirectory dir;
    private final WorldLifecycleManager lifecycle;

    public InstanceManager(SpaceDirectory dir, WorldLifecycleManager lifecycle) {
        this.dir = dir;
        this.lifecycle = lifecycle;
    }

    public WorldLifecycleManager lifecycle() { return lifecycle; }

    /** True for worlds created by Endfold (namespace {@code endspace}), whether or not a record exists. */
    public boolean isManaged(World w) { return w != null && WorldRepository.NAMESPACE.equals(w.getKey().getNamespace()); }

    /** The shared vanilla End, which Endfold does not use as a progression destination. */
    public boolean isVanillaEnd(World w) { return w != null && VANILLA_END.equals(w.getKey()); }

    public Optional<EndSpace> spaceOf(World w) {
        if (!isManaged(w)) return Optional.empty();
        return dir.spaceByWorldName(w.getName());
    }

    public Optional<World> worldOf(EndSpace s) { return lifecycle.loadedWorld(s); }

    public boolean isLoaded(EndSpace s) { return lifecycle.isLoaded(s); }

    public CompletableFuture<World> ensureLoaded(EndSpace s) { return lifecycle.ensureLoaded(s); }

    public List<Player> playersIn(EndSpace s) { return worldOf(s).map(World::getPlayers).orElse(List.of()); }

    public Optional<EndSpace> currentSpace(Player p) { return spaceOf(p.getWorld()); }

    /** Main overworld, used as the fallback destination. */
    public static World mainWorld() { return Bukkit.getWorlds().getFirst(); }

    public boolean canEnter(UUID spaceId, Player p) { return !lifecycle.inMaintenance(spaceId) && dir.canEnter(spaceId, p.getUniqueId()); }
}
