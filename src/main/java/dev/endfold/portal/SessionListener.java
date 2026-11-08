package dev.endfold.portal;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.ReturnLocation;
import dev.endfold.util.Sync;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Player sessions: profile refresh on join, restoring players who logged out inside an EndSpace whose world has since
 * been unloaded, evicting players who lost access while offline, activity tracking for world unloading, and a
 * periodic access sweep.
 */
public final class SessionListener implements Listener {
    private final EndfoldPlugin plugin;

    public SessionListener(EndfoldPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.services().profiles.seen(p.getUniqueId(), p.getName());
        Optional<EndSpace> here = plugin.instances().currentSpace(p);
        if (here.isPresent()) plugin.lifecycle().markOccupied(here.get().id());
        // Check access immediately (cache read), before any asynchronous work, so a removed member cannot act.
        if ((plugin.instances().isManaged(p.getWorld()) || plugin.instances().isVanillaEnd(p.getWorld())) && !plugin.guard().mayBeIn(p, p.getWorld())) {
            plugin.router().evict(p, here.orElse(null),
                    plugin.instances().isVanillaEnd(p.getWorld()) ? "portal.unmanaged-blocked" : "portal.removed-from-space");
        }
        Sync.then(plugin.services().profiles.takeSession(p.getUniqueId()), r -> {
            if (!p.isOnline() || !r.isOk()) return;
            Optional<ReturnLocation> session = r.value();
            if (plugin.instances().isManaged(p.getWorld())) {
                // Logged in inside a loaded EndSpace world: only membership needs checking.
                if (!plugin.guard().mayBeIn(p, p.getWorld())) plugin.router().evict(p, plugin.instances().currentSpace(p).orElse(null), "portal.removed-from-space");
                return;
            }
            if (session.isEmpty()) return;
            Optional<EndSpace> space = plugin.services().dir().space(session.get().spaceId());
            if (space.isEmpty() || !plugin.instances().canEnter(space.get().id(), p)) {
                if (space.isPresent()) plugin.messages().send(p, "portal.removed-from-space");
                return; // vanilla already placed them in the main world
            }
            ReturnLocation s = session.get();
            plugin.instances().ensureLoaded(space.get()).thenAcceptAsync(world -> {
                if (!p.isOnline() || !plugin.instances().canEnter(space.get().id(), p)) return;
                Location loc = new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
                world.getChunkAtAsync(loc).thenAccept(c -> p.teleportAsync(loc));
            }, Sync.main()).exceptionally(t -> {
                plugin.getLogger().warning("Could not restore " + p.getName() + " into " + space.get().worldName() + ": " + t.getMessage());
                return null;
            });
        });
        Sync.later(40L, () -> notifyPending(p));
    }

    private void notifyPending(Player p) {
        if (!p.isOnline()) return;
        Sync.then(plugin.services().members.incoming(p.getUniqueId()), r -> {
            if (r.isOk() && !r.value().isEmpty() && p.isOnline()) {
                plugin.messages().send(p, "invite.pending-on-join", Messages.arg("count", r.value().size()));
            }
        });
        plugin.onPlayerReady(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.router().forget(p.getUniqueId());
        Optional<EndSpace> here = plugin.instances().currentSpace(p);
        if (here.isPresent()) {
            Location l = p.getLocation();
            plugin.services().profiles.saveSession(new ReturnLocation(p.getUniqueId(), here.get().id(), null, null, l.getX(), l.getY(), l.getZ(),
                    l.getYaw(), l.getPitch()));
            if (here.get() != null && l.getWorld().getPlayers().size() <= 1) plugin.lifecycle().markPossiblyEmpty(here.get().id());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        plugin.instances().spaceOf(e.getPlayer().getWorld()).ifPresent(s -> plugin.lifecycle().markOccupied(s.id()));
        plugin.instances().spaceOf(e.getFrom()).ifPresent(s -> {
            if (e.getFrom().getPlayers().isEmpty()) plugin.lifecycle().markPossiblyEmpty(s.id());
        });
    }

    /** Defence in depth: every few seconds, remove anyone inside an EndSpace they may not access. */
    public void sweep() {
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.instances().isManaged(w) && !plugin.instances().isVanillaEnd(w)) continue;
            for (Player p : w.getPlayers()) {
                if (plugin.router().isRouting(p.getUniqueId())) continue;
                if (!plugin.guard().mayBeIn(p, w)) {
                    plugin.router().evict(p, plugin.instances().spaceOf(w).orElse(null),
                            plugin.instances().isVanillaEnd(w) ? "portal.unmanaged-blocked" : "portal.removed-from-space");
                }
            }
        }
    }

}
