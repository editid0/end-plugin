package dev.endfold;

import dev.endfold.model.EndSpace;
import dev.endfold.portal.PortalRouter;
import dev.endfold.service.Services;
import dev.endfold.world.InstanceManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;

/**
 * Public API for other plugins (registered in Bukkit's ServicesManager). Read operations are safe on the server
 * thread; mutations return futures from the service layer.
 */
public final class EndfoldApi {
    private final EndfoldPlugin plugin;

    EndfoldApi(EndfoldPlugin plugin) { this.plugin = plugin; }

    public Services services() { return plugin.services(); }

    public InstanceManager instances() { return plugin.instances(); }

    public PortalRouter router() { return plugin.router(); }

    public Optional<EndSpace> spaceOf(World world) { return plugin.instances().spaceOf(world); }

    public List<EndSpace> spacesOf(UUID player) { return plugin.services().dir().spacesOf(player); }

    public boolean isManagedWorld(World world) { return plugin.instances().isManaged(world); }

    public EndfoldPlugin plugin() { return plugin; }
}
