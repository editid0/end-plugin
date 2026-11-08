package dev.endfold.admin;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceStatus;
import dev.endfold.util.Sync;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Administrative operations shared by {@code /endfold} and the admin GUI. Every operation is logged to the audit
 * table and the console. Destructive operations take a backup first (when enabled) and never delete world files:
 * deletion moves the folder to {@code plugins/Endfold/trash}. Callers must obtain confirmation before calling
 * {@link #archive}, {@link #delete} or {@link #restore}.
 */
public final class AdminOps {
    private final EndfoldPlugin plugin;

    public AdminOps(EndfoldPlugin plugin) { this.plugin = plugin; }

    private Messages msg() { return plugin.messages(); }

    private static UUID actor(CommandSender s) { return s instanceof Player p ? p.getUniqueId() : null; }

    private void audit(CommandSender s, EndSpace space, String action, String detail) {
        plugin.services().audit.log(space == null ? null : space.id(), actor(s), action, "by " + s.getName() + (detail == null ? "" : " " + detail));
    }

    /** Re-checks the permission at execution time (menus may stay open after permissions change). */
    private boolean allowed(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        msg().fail(s, dev.endfold.service.Failure.NO_PERMISSION);
        return false;
    }

    private void error(CommandSender s, String key, Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String reason = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
        msg().send(s, key, Messages.arg("reason", reason));
    }

    public CompletableFuture<World> load(CommandSender s, EndSpace space) {
        if (!allowed(s, "endfold.admin.worlds")) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));
        audit(s, space, "ADMIN_LOAD", null);
        return plugin.instances().ensureLoaded(space).whenCompleteAsync((w, t) -> {
            if (t == null) msg().send(s, "admin.loaded", Messages.arg("space", space.name()));
            else error(s, "admin.unload-failed", t);
        }, Sync.main());
    }

    public boolean unload(CommandSender s, EndSpace space) {
        if (!allowed(s, "endfold.admin.worlds")) return false;
        Optional<World> w = plugin.instances().worldOf(space);
        if (w.isEmpty()) {
            msg().send(s, "admin.unloaded", Messages.arg("space", space.name()));
            return true;
        }
        for (Player p : List.copyOf(w.get().getPlayers())) plugin.router().evict(p, space, null);
        // Players are moved asynchronously; retry shortly if any are still present.
        Sync.later(40L, () -> {
            boolean ok = plugin.lifecycle().unload(w.get(), "admin " + s.getName());
            audit(s, space, "ADMIN_UNLOAD", ok ? "ok" : "refused");
            if (ok) msg().send(s, "admin.unloaded", Messages.arg("space", space.name()));
            else msg().send(s, "admin.unload-failed", Messages.arg("space", space.name()), Messages.arg("reason", "players still inside or server refused"));
        });
        return true;
    }

    public void teleport(Player p, EndSpace space) {
        if (!allowed(p, "endfold.admin.teleport")) return;
        audit(p, space, "ADMIN_TELEPORT", null);
        plugin.router().adminTeleport(p, space).whenCompleteAsync((ok, t) -> {
            if (t == null && Boolean.TRUE.equals(ok)) msg().send(p, "admin.teleported", Messages.arg("space", space.name()));
            else if (t != null) error(p, "admin.unload-failed", t);
        }, Sync.main());
    }

    public CompletableFuture<Path> backup(CommandSender s, EndSpace space, String tag) {
        if (!allowed(s, "endfold.admin.backup")) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));
        audit(s, space, "ADMIN_BACKUP", tag);
        return plugin.lifecycle().backup(space, tag, plugin.config().backupsToKeep).whenCompleteAsync((p, t) -> {
            if (t == null) msg().send(s, "admin.backup-done", Messages.arg("file", p.getFileName().toString()));
            else error(s, "admin.backup-failed", t);
        }, Sync.main());
    }

    /** Archives (disbands) a space: members lose access, world and records are kept. */
    public void archive(CommandSender s, EndSpace space) {
        if (!allowed(s, "endfold.admin.delete")) return;
        Sync.then(plugin.services().spaces.adminSetStatus(actor(s), space.id(), SpaceStatus.ARCHIVED, "by " + s.getName()), r -> {
            if (!r.isOk()) {
                msg().fail(s, r.failure());
                return;
            }
            plugin.router().evictUnauthorised(r.value());
            msg().send(s, "admin.disbanded", Messages.arg("space", space.name()));
        });
    }

    /** Re-activates an archived or broken space (after a repair or restore). */
    public void activate(CommandSender s, EndSpace space) {
        if (!allowed(s, "endfold.admin.worlds")) return;
        if (space.worldUuid() != null && !plugin.lifecycle().files().exists(space)) {
            msg().send(s, "admin.restore-failed", Messages.arg("reason", "world folder is missing; restore a backup first"));
            return;
        }
        Sync.then(plugin.services().spaces.adminSetStatus(actor(s), space.id(), SpaceStatus.ACTIVE, "by " + s.getName()), r -> {
            if (r.isOk()) msg().send(s, "admin.restored-status", Messages.arg("space", space.name()));
            else msg().fail(s, r.failure());
        });
    }

    /**
     * Deletes a space: evicts players, unloads the world, backs it up, moves the folder to trash and marks the record
     * DELETED. Each step must succeed before the next starts.
     */
    public void delete(CommandSender s, EndSpace space) {
        if (!allowed(s, "endfold.admin.delete")) return;
        // Lock first so no member can re-enter (and reload the world) while it is being moved to trash.
        plugin.lifecycle().lockForMaintenance(space.id());
        Optional<World> w = plugin.instances().worldOf(space);
        w.ifPresent(world -> List.copyOf(world.getPlayers()).forEach(p -> plugin.router().evict(p, space, "portal.removed-from-space")));
        Sync.later(40L, () -> {
            Optional<World> still = plugin.instances().worldOf(space);
            if (still.isPresent() && !plugin.lifecycle().unload(still.get(), "delete")) {
                plugin.lifecycle().releaseMaintenance(space.id());
                msg().send(s, "admin.unload-failed", Messages.arg("space", space.name()), Messages.arg("reason", "world could not be unloaded"));
                return;
            }
            CompletableFuture<Path> backup = plugin.config().autoBackup && plugin.lifecycle().files().exists(space)
                    ? plugin.lifecycle().backup(space, "predelete", Integer.MAX_VALUE)
                    : CompletableFuture.completedFuture(null);
            backup.thenApplyAsync(b -> {
                try {
                    return plugin.lifecycle().files().moveToTrash(space, "deleted");
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r))
                    .thenCompose(trash -> plugin.services().spaces.adminMarkDeleted(actor(s), space.id()).thenApply(r -> trash))
                    .whenCompleteAsync((trash, t) -> {
                        plugin.lifecycle().releaseMaintenance(space.id());
                        if (t != null) {
                            plugin.getLogger().log(Level.SEVERE, "Deleting EndSpace " + space.shortId() + " failed", t);
                            error(s, "admin.restore-failed", t);
                            return;
                        }
                        audit(s, space, "ADMIN_DELETE_DONE", trash == null ? "no folder" : trash.toString());
                        msg().send(s, "admin.deleted", Messages.arg("space", space.name()), Messages.arg("path", trash == null ? "-" : trash.toString()));
                    }, Sync.main());
        });
    }

    /** Restores a backup into an (unloaded) space world, taking a safety backup first. */
    public void restore(CommandSender s, EndSpace space, String fileName) {
        if (!allowed(s, "endfold.admin.backup")) return;
        Path file;
        try {
            file = plugin.lifecycle().files().resolveBackup(space, fileName);
        } catch (Exception e) {
            error(s, "admin.restore-failed", e);
            return;
        }
        plugin.lifecycle().lockForMaintenance(space.id());
        Optional<World> w = plugin.instances().worldOf(space);
        w.ifPresent(world -> List.copyOf(world.getPlayers()).forEach(p -> plugin.router().evict(p, space, "portal.removed-from-space")));
        Sync.later(40L, () -> {
            Optional<World> still = plugin.instances().worldOf(space);
            if (still.isPresent() && !plugin.lifecycle().unload(still.get(), "restore")) {
                plugin.lifecycle().releaseMaintenance(space.id());
                msg().send(s, "admin.restore-failed", Messages.arg("reason", "world could not be unloaded"));
                return;
            }
            audit(s, space, "ADMIN_RESTORE", fileName);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (plugin.lifecycle().files().exists(space)) plugin.lifecycle().files().backup(space, "prerestore");
                    plugin.lifecycle().files().restore(space, file);
                    Sync.run(() -> {
                        plugin.lifecycle().releaseMaintenance(space.id());
                        msg().send(s, "admin.restore-done", Messages.arg("space", space.name()), Messages.arg("file", fileName));
                        if (space.status() == SpaceStatus.BROKEN) activate(s, space);
                    });
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Restore of " + space.shortId() + " failed", e);
                    Sync.run(() -> {
                        plugin.lifecycle().releaseMaintenance(space.id());
                        error(s, "admin.restore-failed", e);
                    });
                }
            });
        });
    }

    /**
     * Repairs metadata: marks spaces BROKEN whose generated world folder is missing, re-activates BROKEN spaces whose
     * folder is present again, and reports orphan folders. Never touches world files.
     */
    public void repair(CommandSender s) {
        if (!allowed(s, "endfold.admin.worlds")) return;
        int broken = 0, fixed = 0;
        for (EndSpace sp : plugin.services().dir().allSpaces()) {
            boolean exists = plugin.lifecycle().files().exists(sp);
            if (sp.status() == SpaceStatus.ACTIVE && sp.worldUuid() != null && !exists && !plugin.instances().isLoaded(sp)) {
                plugin.services().spaces.adminSetStatus(actor(s), sp.id(), SpaceStatus.BROKEN, "repair: folder missing");
                broken++;
            } else if (sp.status() == SpaceStatus.BROKEN && (exists || sp.worldUuid() == null)) {
                plugin.services().spaces.adminSetStatus(actor(s), sp.id(), SpaceStatus.ACTIVE, "repair: folder present");
                fixed++;
            }
        }
        audit(s, null, "ADMIN_REPAIR", "broken=" + broken + " fixed=" + fixed);
        msg().send(s, "admin.repaired", Messages.arg("summary", broken + " marked broken, " + fixed + " re-activated"));
    }
}
