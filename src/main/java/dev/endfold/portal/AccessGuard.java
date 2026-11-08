package dev.endfold.portal;

import dev.endfold.EndfoldPlugin;
import dev.endfold.model.EndSpace;
import dev.endfold.world.InstanceManager;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Enforces membership for every way into an EndSpace that the Bukkit event API exposes: commands, other plugins,
 * ender pearls, chorus fruit, spectator teleports and respawns. A periodic sweep in {@link SessionListener} is the
 * second line of defence.
 */
public final class AccessGuard implements Listener {
    public static final String BYPASS = "endfold.admin.teleport";
    private final EndfoldPlugin plugin;

    public AccessGuard(EndfoldPlugin plugin) { this.plugin = plugin; }

    private InstanceManager instances() { return plugin.instances(); }

    /** Whether {@code p} may be in world {@code w} (managed worlds require membership of an ACTIVE space). */
    public boolean mayBeIn(Player p, World w) {
        if (instances().isVanillaEnd(w)) return !plugin.config().blockUnmanagedEnd || p.hasPermission(BYPASS);
        if (!instances().isManaged(w)) return true;
        if (p.hasPermission(BYPASS)) return true;
        Optional<EndSpace> s = instances().spaceOf(w);
        return s.isPresent() && instances().canEnter(s.get().id(), p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        World target = to.getWorld();
        World from = e.getFrom().getWorld();
        Player p = e.getPlayer();
        if (target == null) return;
        if (target == from) {
            // Same-world moves (gateways, pearls, chorus fruit) are fine for authorised occupants.
            return;
        }
        if (plugin.router().isAuthorised(p.getUniqueId(), target) && !instances().isVanillaEnd(target)) return;
        if (!mayBeIn(p, target)) {
            e.setCancelled(true);
            plugin.messages().send(p, instances().isVanillaEnd(target) ? "portal.unmanaged-blocked" : "portal.access-denied");
        }
    }

    /** Non-player entities may not be teleported into or out of EndSpaces across worlds (prevents item/mob leaks). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof Player || e.getTo() == null) return;
        World to = e.getTo().getWorld();
        World from = e.getFrom().getWorld();
        if (to == null || to == from) return;
        if (instances().isManaged(to) || instances().isManaged(from)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        World w = e.getRespawnLocation().getWorld();
        if (!mayBeIn(p, w)) e.setRespawnLocation(plugin.router().fallbackLocation());
    }
}
