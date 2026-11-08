package dev.endfold.portal;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.EndfoldConfig;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.ReturnLocation;
import dev.endfold.model.SpaceStatus;
import dev.endfold.service.Result;
import dev.endfold.util.Locations;
import dev.endfold.util.Sync;
import dev.endfold.world.InstanceManager;
import dev.endfold.world.WorldGenerator;
import dev.endfold.world.WorldLifecycleManager;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Routes players into and out of EndSpaces. This is the only code path that moves players between instances, and it
 * is shared by End portals, the GUI ("Enter now") and commands ({@code /end enter}, {@code /end exit}).
 *
 * <p>Vanilla always routes End portals to the shared {@code minecraft:the_end}. Endfold cancels the portal touch
 * ({@code EntityPortalEnterEvent}) and teleports the player itself - see {@link PortalListener}. The obsidian
 * arrival platform is rebuilt here because vanilla only builds it in the shared End.</p>
 */
public final class PortalRouter {
    public enum Outcome { STARTED, BUSY, COOLDOWN, NO_DESTINATION, DENIED, FAILED }

    private final EndfoldPlugin plugin;
    private final Set<UUID> routing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRoute = new ConcurrentHashMap<>();
    /** Players currently being moved by Endfold; the access guard lets these teleports through. */
    private final Map<UUID, UUID> authorised = new ConcurrentHashMap<>(); // player -> world they are being sent to

    public PortalRouter(EndfoldPlugin plugin) { this.plugin = plugin; }

    private EndfoldConfig cfg() { return plugin.config(); }

    private Messages msg() { return plugin.messages(); }

    private InstanceManager instances() { return plugin.instances(); }

    /** True while Endfold itself is moving {@code player} into {@code world}. */
    public boolean isAuthorised(UUID player, World world) { return world != null && world.getUID().equals(authorised.get(player)); }

    public boolean isRouting(UUID player) { return routing.contains(player); }

    private boolean onCooldown(Player p) {
        Long last = lastRoute.get(p.getUniqueId());
        return last != null && System.currentTimeMillis() - last < cfg().portalCooldownSeconds * 1000L;
    }

    private boolean begin(Player p, boolean fromPortal) {
        if (fromPortal && onCooldown(p)) return false;
        if (!routing.add(p.getUniqueId())) return false;
        lastRoute.put(p.getUniqueId(), System.currentTimeMillis());
        return true;
    }

    private void end(Player p) {
        routing.remove(p.getUniqueId());
        lastRoute.put(p.getUniqueId(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ entering

    /** Portal entry or {@code /end enter}: go to the selected EndSpace, creating a personal one if configured. */
    public Outcome enterSelected(Player p, boolean fromPortal) {
        Optional<EndSpace> selected = plugin.services().profiles.selectedSpace(p.getUniqueId());
        if (selected.isPresent()) return enter(p, selected.get(), fromPortal);
        EndfoldConfig c = cfg();
        if (!(c.soloEnabled && c.soloCreateOnFirstEntry && p.hasPermission("endfold.create"))) {
            if (!fromPortal || !onCooldown(p)) msg().send(p, "portal.no-destination");
            lastRoute.put(p.getUniqueId(), System.currentTimeMillis());
            return Outcome.NO_DESTINATION;
        }
        if (!begin(p, fromPortal)) return Outcome.COOLDOWN;
        msg().send(p, "portal.creating-solo");
        String name = c.soloDefaultName.replace("{player}", p.getName());
        if (name.length() > c.nameMax) name = name.substring(0, c.nameMax);
        plugin.services().spaces.ensureSolo(p.getUniqueId(), p.getName(), name)
                .thenCompose(r -> r.isOk() ? plugin.services().profiles.select(p.getUniqueId(), r.value().id()) : CompletableFuture.completedFuture(r))
                .thenAcceptAsync(r -> {
                    end(p);
                    if (!p.isOnline()) return;
                    if (!r.isOk()) {
                        msg().fail(p, r.failure());
                        return;
                    }
                    enter(p, r.value(), false);
                }, Sync.main());
        return Outcome.STARTED;
    }

    /**
     * Sends {@code p} into {@code space}. Membership is checked against the live directory; the world is loaded or
     * generated on demand. The player's current overworld location becomes the return point for this space.
     */
    public Outcome enter(Player p, EndSpace space, boolean fromPortal) {
        if (!instances().canEnter(space.id(), p)) {
            msg().send(p, "portal.access-denied");
            return Outcome.DENIED;
        }
        Optional<EndSpace> current = instances().currentSpace(p);
        if (current.isPresent() && current.get().id().equals(space.id())) return Outcome.STARTED;
        if (!begin(p, fromPortal)) return fromPortal ? Outcome.COOLDOWN : Outcome.BUSY;

        // Remember where to come back to. Inside another EndSpace, inherit that space's return point.
        CompletableFuture<Result<Void>> saved;
        if (current.isPresent()) {
            saved = plugin.services().profiles.findReturn(p.getUniqueId(), current.get().id()).thenCompose(r -> {
                if (r.isOk() && r.value().isPresent()) {
                    ReturnLocation old = r.value().get();
                    return plugin.services().profiles.saveReturn(new ReturnLocation(p.getUniqueId(), space.id(), old.worldUuid(), old.worldName(),
                            old.x(), old.y(), old.z(), old.yaw(), old.pitch()));
                }
                return CompletableFuture.completedFuture(Result.ok(null));
            });
        } else if (!instances().isManaged(p.getWorld()) && !instances().isVanillaEnd(p.getWorld())) {
            Location l = p.getLocation();
            saved = plugin.services().profiles.saveReturn(new ReturnLocation(p.getUniqueId(), space.id(), l.getWorld().getUID(), l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        } else {
            saved = CompletableFuture.completedFuture(Result.ok(null));
        }

        boolean loaded = instances().isLoaded(space);
        if (!loaded) msg().send(p, "portal.loading", Messages.arg("space", space.name()));

        saved.thenComposeAsync(ignored -> instances().ensureLoaded(space), Sync.main())
                .thenComposeAsync(world -> arrive(p, space, world), Sync.main())
                .whenCompleteAsync((ok, err) -> {
                    end(p);
                    if (err != null) handleLoadError(p, space, err);
                }, Sync.main());
        return Outcome.STARTED;
    }

    private CompletableFuture<Boolean> arrive(Player p, EndSpace space, World world) {
        if (!p.isOnline()) return CompletableFuture.completedFuture(false);
        // Re-check: membership may have changed while the world loaded.
        if (!instances().canEnter(space.id(), p)) {
            msg().send(p, "portal.access-denied");
            return CompletableFuture.completedFuture(false);
        }
        Location target = WorldGenerator.arrival(world);
        return world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4, true, true).thenComposeAsync(chunk -> {
            WorldGenerator.buildPlatform(world);
            if (p.isInsideVehicle()) p.leaveVehicle();
            authorised.put(p.getUniqueId(), world.getUID());
            return p.teleportAsync(target, TeleportCause.END_PORTAL).whenComplete((ok, t) -> authorised.remove(p.getUniqueId()));
        }, Sync.main()).thenApplyAsync(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                msg().send(p, "portal.entering", Messages.arg("space", space.name()));
                plugin.lifecycle().markOccupied(space.id());
                plugin.services().spaces.touch(space.id());
                int memberships = plugin.services().dir().spacesOf(p.getUniqueId()).size();
                Sync.then(plugin.services().progress.arrived(space.id(), p.getUniqueId(), memberships), r -> plugin.announceGrants(r));
            }
            return ok;
        }, Sync.main());
    }

    private void handleLoadError(Player p, EndSpace space, Throwable err) {
        Throwable c = err;
        while (c.getCause() != null && !(c instanceof WorldLifecycleManager.LoadException)) c = c.getCause();
        if (!p.isOnline()) return;
        if (c instanceof WorldLifecycleManager.LoadException le) {
            switch (le.error) {
                case BUSY -> msg().send(p, "errors.world-busy");
                case NOT_ACTIVE -> msg().send(p, "errors.space_not_active");
                default -> msg().send(p, "errors.world-load-failed", Messages.arg("space", space.name()));
            }
        } else {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error routing " + p.getName() + " to " + space.worldName(), err);
            msg().send(p, "errors.world-load-failed", Messages.arg("space", space.name()));
        }
    }

    // ------------------------------------------------------------------ leaving

    /** Exit portal or {@code /end exit}: return to the stored entry point for this space, else a safe fallback. */
    public Outcome exit(Player p, boolean fromPortal) {
        Optional<EndSpace> current = instances().currentSpace(p);
        if (current.isEmpty() && !instances().isManaged(p.getWorld())) return Outcome.NO_DESTINATION;
        if (!begin(p, fromPortal)) return Outcome.COOLDOWN;
        UUID spaceId = current.map(EndSpace::id).orElse(null);
        CompletableFuture<Optional<ReturnLocation>> ret = spaceId == null || !cfg().returnToEntry
                ? CompletableFuture.completedFuture(Optional.empty())
                : plugin.services().profiles.findReturn(p.getUniqueId(), spaceId).thenApply(r -> r.isOk() ? r.value() : Optional.empty());
        ret.thenComposeAsync(loc -> sendHome(p, loc.orElse(null), current.map(EndSpace::name).orElse(p.getWorld().getName())), Sync.main())
                .whenCompleteAsync((ok, err) -> {
                    end(p);
                    if (err != null) plugin.getLogger().log(Level.SEVERE, "Error returning " + p.getName(), err);
                }, Sync.main());
        return Outcome.STARTED;
    }

    /** Teleports to the return location if valid, else bed/anchor respawn point, else the fallback world spawn. */
    public CompletableFuture<Boolean> sendHome(Player p, ReturnLocation ret, String fromName) {
        if (!p.isOnline()) return CompletableFuture.completedFuture(false);
        Location target = null;
        if (ret != null) {
            World w = Bukkit.getWorld(ret.worldUuid());
            if (w == null) w = Bukkit.getWorld(ret.worldName());
            if (w != null && !instances().isManaged(w) && !instances().isVanillaEnd(w)) {
                target = new Location(w, ret.x(), ret.y(), ret.z(), ret.yaw(), ret.pitch());
            }
        }
        boolean fallback = false;
        if (target == null) {
            Location respawn = p.getRespawnLocation();
            if (respawn != null && !instances().isManaged(respawn.getWorld()) && !instances().isVanillaEnd(respawn.getWorld())) target = respawn;
        }
        if (target == null) {
            target = fallbackLocation();
            fallback = ret != null;
        }
        final Location dest = target;
        final boolean usedFallback = fallback;
        return dest.getWorld().getChunkAtAsync(dest.getBlockX() >> 4, dest.getBlockZ() >> 4, true, true).thenComposeAsync(chunk -> {
            Location safe = Locations.nearbySafe(dest, 8);
            if (safe == null) safe = Locations.spawnOf(dest.getWorld());
            if (p.isInsideVehicle()) p.leaveVehicle();
            authorised.put(p.getUniqueId(), safe.getWorld().getUID());
            return p.teleportAsync(safe, TeleportCause.END_PORTAL).whenComplete((ok, t) -> authorised.remove(p.getUniqueId()));
        }, Sync.main()).thenApplyAsync(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                msg().send(p, "portal.returning", Messages.arg("space", fromName));
                if (usedFallback) msg().send(p, "portal.fallback-used");
            }
            return ok;
        }, Sync.main());
    }

    public Location fallbackLocation() {
        World w = cfg().fallbackWorld.isBlank() ? null : Bukkit.getWorld(cfg().fallbackWorld);
        if (w == null || instances().isManaged(w)) w = InstanceManager.mainWorld();
        return Locations.spawnOf(w);
    }

    /**
     * Moves a player out of an EndSpace they may no longer access (removed, disbanded, deleted). Uses their stored
     * return point when available.
     */
    public void evict(Player p, EndSpace space, String messageKey) {
        if (messageKey != null) msg().send(p, messageKey, Messages.arg("space", space == null ? "" : space.name()));
        if (space == null) {
            sendHome(p, null, p.getWorld().getName());
            return;
        }
        plugin.services().profiles.findReturn(p.getUniqueId(), space.id())
                .thenAcceptAsync(r -> sendHome(p, r.isOk() ? r.value().orElse(null) : null, space.name()), Sync.main());
    }

    /** Evicts every online player in {@code space} who can no longer enter it. Call after membership changes. */
    public void evictUnauthorised(EndSpace space) {
        for (Player p : instances().playersIn(space)) {
            if (!instances().canEnter(space.id(), p) && !p.hasPermission("endfold.admin.teleport")) {
                evict(p, space, space.status() == SpaceStatus.ACTIVE ? "portal.removed-from-space" : "portal.removed-from-space");
            }
        }
    }

    /** Admin teleport into any space, bypassing membership (logged). */
    public CompletableFuture<Boolean> adminTeleport(Player p, EndSpace space) {
        return instances().ensureLoaded(space).thenComposeAsync(world -> {
            Location target = WorldGenerator.arrival(world);
            return world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4, true, true).thenComposeAsync(c -> {
                WorldGenerator.buildPlatform(world);
                authorised.put(p.getUniqueId(), world.getUID());
                return p.teleportAsync(target, TeleportCause.COMMAND).whenComplete((ok, t) -> authorised.remove(p.getUniqueId()));
            }, Sync.main());
        }, Sync.main());
    }

    public void forget(UUID player) {
        routing.remove(player);
        lastRoute.remove(player);
        authorised.remove(player);
    }
}
