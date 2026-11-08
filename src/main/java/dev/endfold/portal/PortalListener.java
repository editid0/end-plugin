package dev.endfold.portal;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.EndfoldConfig;
import dev.endfold.world.InstanceManager;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Intercepts End portal and gateway use. Thin: all decisions are delegated to {@link PortalRouter} and the
 * {@link dev.endfold.world.InstanceManager}.
 *
 * <ul>
 *   <li>Player touches an End portal outside an EndSpace: cancelled, routed to the selected EndSpace.</li>
 *   <li>Player touches the exit portal inside an EndSpace: cancelled, routed to the stored return point.</li>
 *   <li>End gateways inside an EndSpace are left to vanilla (they always stay in the same world - verified).</li>
 *   <li>Non-player entities never cross between instances (policy BLOCK by default).</li>
 * </ul>
 */
public final class PortalListener implements Listener {
    private final EndfoldPlugin plugin;

    public PortalListener(EndfoldPlugin plugin) { this.plugin = plugin; }

    private InstanceManager instances() { return plugin.instances(); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortalTouch(EntityPortalEnterEvent e) {
        Entity entity = e.getEntity();
        World from = entity.getWorld();
        boolean managed = instances().isManaged(from);
        EndfoldConfig cfg = plugin.config();

        if (e.getPortalType() == PortalType.END_GATEWAY) {
            // Gateways teleport within the same world. Only restrict non-player entities by policy.
            if (managed && !(entity instanceof Player) && cfg.entityPolicy == EndfoldConfig.EntityPolicy.BLOCK) e.setCancelled(true);
            return;
        }
        if (e.getPortalType() != PortalType.ENDER) return;

        if (!(entity instanceof Player p)) {
            // Entities would otherwise travel to the shared End (or out of an instance). Keep them where they are.
            if (managed || cfg.blockUnmanagedEnd) e.setCancelled(true);
            return;
        }
        if (instances().isVanillaEnd(from)) return; // leaving the shared End: vanilla behaviour
        if (managed) {
            e.setCancelled(true);
            plugin.router().exit(p, true);
            return;
        }
        if (!cfg.routeToSelected) return;
        e.setCancelled(true);
        if (!p.hasPermission("endfold.use")) {
            if (cfg.blockUnmanagedEnd) plugin.messages().send(p, "errors.no_permission");
            return;
        }
        plugin.router().enterSelected(p, true);
    }

    /** Backstop for portal travel that bypassed the touch handler (e.g. other plugins creating portal events). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (e.getCause() != TeleportCause.END_PORTAL && e.getCause() != TeleportCause.END_GATEWAY) return;
        World to = e.getTo().getWorld();
        World from = e.getFrom().getWorld();
        Player p = e.getPlayer();
        if (instances().isVanillaEnd(to) && plugin.config().blockUnmanagedEnd && !p.hasPermission("endfold.admin.teleport")) {
            e.setCancelled(true);
            return;
        }
        if (instances().isManaged(to) && to != from) {
            boolean ok = instances().spaceOf(to).map(s -> instances().canEnter(s.id(), p)).orElse(false);
            if (!ok && !p.hasPermission("endfold.admin.teleport")) e.setCancelled(true);
        }
        if (instances().isManaged(from) && e.getCause() == TeleportCause.END_GATEWAY && to != from) {
            // A gateway must never leave its instance.
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (e.getTo() == null) return;
        World to = e.getTo().getWorld();
        World from = e.getFrom().getWorld();
        if (to == from) return;
        if (instances().isManaged(to) || instances().isManaged(from)) {
            e.setCancelled(true);
            return;
        }
        if (instances().isVanillaEnd(to) && plugin.config().blockUnmanagedEnd) e.setCancelled(true);
    }
}
