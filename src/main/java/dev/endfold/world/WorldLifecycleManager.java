package dev.endfold.world;

import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceStatus;
import dev.endfold.service.Services;
import dev.endfold.util.Sync;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Loads, tracks and unloads EndSpace worlds. Worlds are loaded lazily when an authorised player needs them and
 * unloaded (saved, files kept) after a configurable period without players. Unloading never deletes anything.
 */
public final class WorldLifecycleManager {
    public enum LoadError { BUSY, BROKEN, FAILED, NOT_ACTIVE }

    public static final class LoadException extends RuntimeException {
        public final LoadError error;

        public LoadException(LoadError error, String msg, Throwable cause) {
            super(msg, cause, false, false);
            this.error = error;
        }
    }

    private final Services services;
    private final WorldGenerator generator;
    private final WorldRepository files;
    private final Logger log;
    private final Executor io;
    private final IntSupplier unloadAfterMinutes;
    private final IntSupplier maxLoaded;
    private final Map<UUID, CompletableFuture<World>> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> emptySince = new ConcurrentHashMap<>();
    private final Set<UUID> pinned = ConcurrentHashMap.newKeySet();
    /** Spaces under admin maintenance (delete/restore): no loads and no entry until released. */
    private final Set<UUID> maintenance = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();
    private static final long FORCED_UNLOAD_GRACE_MS = 60_000;

    public WorldLifecycleManager(Services services, WorldGenerator generator, WorldRepository files, Logger log, Executor io,
                                 IntSupplier unloadAfterMinutes, IntSupplier maxLoaded) {
        this.services = services;
        this.generator = generator;
        this.files = files;
        this.log = log;
        this.io = io;
        this.unloadAfterMinutes = unloadAfterMinutes;
        this.maxLoaded = maxLoaded;
    }

    public WorldRepository files() { return files; }

    public Optional<World> loadedWorld(EndSpace space) {
        return Optional.ofNullable(Bukkit.getWorld(WorldGenerator.keyOf(space)));
    }

    public boolean isLoaded(EndSpace space) { return loadedWorld(space).isPresent(); }

    public boolean isLoading(UUID spaceId) { return inFlight.containsKey(spaceId); }

    /** Number of currently loaded EndSpace worlds. */
    public int loadedCount() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) if (WorldRepository.NAMESPACE.equals(w.getKey().getNamespace())) n++;
        return n;
    }

    public void lockForMaintenance(UUID spaceId) { maintenance.add(spaceId); }

    public void releaseMaintenance(UUID spaceId) { maintenance.remove(spaceId); }

    public boolean inMaintenance(UUID spaceId) { return maintenance.contains(spaceId); }

    /** Keeps a world loaded regardless of inactivity (admin inspection, self-tests). */
    public void pin(UUID spaceId, boolean pin) {
        if (pin) pinned.add(spaceId);
        else pinned.remove(spaceId);
    }

    /**
     * Ensures the world for {@code space} is loaded. Completes on the server thread. Concurrent calls for the same
     * space share one load, which prevents duplicate world creation.
     */
    public CompletableFuture<World> ensureLoaded(EndSpace space) {
        Optional<World> w = loadedWorld(space);
        if (w.isPresent()) return CompletableFuture.completedFuture(w.get());
        return inFlight.computeIfAbsent(space.id(), id -> {
            CompletableFuture<World> f = new CompletableFuture<>();
            // Never create worlds inside an event handler: defer to the next scheduler tick.
            Sync.later(1L, () -> doLoad(space, f));
            f.whenComplete((r, t) -> inFlight.remove(id));
            return f;
        });
    }

    private void doLoad(EndSpace snapshot, CompletableFuture<World> f) {
        try {
            // The directory is authoritative: a space removed meanwhile (deleted) must never be loaded or regenerated.
            EndSpace space = services.dir().space(snapshot.id())
                    .orElseThrow(() -> new LoadException(LoadError.NOT_ACTIVE, "Space no longer exists", null));
            if (maintenance.contains(space.id())) throw new LoadException(LoadError.NOT_ACTIVE, "Space is under maintenance", null);
            Optional<World> already = loadedWorld(space);
            if (already.isPresent()) {
                f.complete(already.get());
                return;
            }
            if (space.status() == SpaceStatus.BROKEN) throw new LoadException(LoadError.BROKEN, "Space is marked BROKEN", null);
            if (space.status() != SpaceStatus.ACTIVE) throw new LoadException(LoadError.NOT_ACTIVE, "Space is " + space.status(), null);
            int max = maxLoaded.getAsInt();
            if (max > 0 && loadedCount() >= max) {
                unloadIdle(true);
                if (loadedCount() >= max) throw new LoadException(LoadError.BUSY, "Loaded world limit reached (" + max + ")", null);
            }
            boolean generated = space.worldUuid() != null;
            if (generated && !files.exists(space)) {
                // The record says this world existed. Never silently recreate it: that would wipe progress.
                log.severe("World folder for EndSpace " + space.shortId() + " (" + space.name() + ") is missing at " + files.folderOf(space)
                        + ". Marking it BROKEN; restore a backup with /endfold admin restore.");
                services.spaces.adminSetStatus(null, space.id(), SpaceStatus.BROKEN, "world folder missing");
                throw new LoadException(LoadError.BROKEN, "World folder missing", null);
            }
            long t = System.nanoTime();
            World world = generator.load(space);
            long ms = (System.nanoTime() - t) / 1_000_000;
            log.info((generated ? "Loaded" : "Generated") + " EndSpace world " + world.getName() + " for '" + space.name() + "' in " + ms + "ms");
            if (!files.matches(space, world.getWorldPath())) {
                log.severe("EndSpace world " + world.getName() + " is stored at " + world.getWorldPath() + " but Endfold expected "
                        + files.folderOf(space) + ". Backups and recovery checks would use the wrong folder; please report this.");
            }
            emptySince.put(space.id(), System.currentTimeMillis());
            loadedAt.put(space.id(), System.currentTimeMillis());
            if (!world.getUID().equals(space.worldUuid())) services.spaces.markWorldGenerated(space.id(), world.getUID());
            f.complete(world);
        } catch (LoadException e) {
            f.completeExceptionally(e);
        } catch (Throwable e) {
            log.log(Level.SEVERE, "Failed to load EndSpace world " + snapshot.worldName(), e);
            f.completeExceptionally(new LoadException(LoadError.FAILED, e.getMessage(), e));
        }
    }

    /** Periodic task: unloads worlds that have had no players for the configured time. */
    public void tick() {
        unloadIdle(false);
    }

    private void unloadIdle(boolean force) {
        long now = System.currentTimeMillis();
        long limit = unloadAfterMinutes.getAsInt() * 60_000L;
        for (World w : List.copyOf(Bukkit.getWorlds())) {
            if (!WorldRepository.NAMESPACE.equals(w.getKey().getNamespace())) continue;
            Optional<EndSpace> sp = services.dir().spaceByWorldName(w.getName());
            UUID id = sp.map(EndSpace::id).orElse(null);
            if (!w.getPlayers().isEmpty()) {
                if (id != null) emptySince.remove(id);
                continue;
            }
            if (id != null && pinned.contains(id)) continue;
            // Freshly loaded worlds are usually about to receive an arriving player; never force them out.
            if (force && id != null && now - loadedAt.getOrDefault(id, 0L) < FORCED_UNLOAD_GRACE_MS) continue;
            long since = id == null ? 0 : emptySince.computeIfAbsent(id, k -> now);
            if (force || now - since >= limit || sp.isEmpty() || sp.get().status() != SpaceStatus.ACTIVE) {
                unload(w, "idle");
            }
        }
    }

    /** Saves and unloads a world. Returns false if players are present or the server refused. Never deletes files. */
    public boolean unload(World w, String reason) {
        if (!w.getPlayers().isEmpty()) return false;
        String name = w.getName();
        boolean ok = Bukkit.unloadWorld(w, true);
        if (ok) {
            services.dir().spaceByWorldName(name).ifPresent(s -> emptySince.remove(s.id()));
            log.info("Unloaded EndSpace world " + name + " (" + reason + "); files kept");
        } else {
            log.warning("Server refused to unload EndSpace world " + name + " (" + reason + ")");
        }
        return ok;
    }

    public void markOccupied(UUID spaceId) { emptySince.remove(spaceId); }

    public void markPossiblyEmpty(UUID spaceId) { emptySince.putIfAbsent(spaceId, System.currentTimeMillis()); }

    /**
     * Backs up a space's world. If loaded, the world is saved and auto-save is paused while zipping.
     * Completes on the server thread.
     */
    public CompletableFuture<Path> backup(EndSpace space, String tag, int keep) {
        Optional<World> w = loadedWorld(space);
        boolean autosave = w.map(World::isAutoSave).orElse(false);
        w.ifPresent(world -> {
            world.save(true);
            world.setAutoSave(false);
        });
        CompletableFuture<Path> f = CompletableFuture.supplyAsync(() -> {
            try {
                Path p = files.backup(space, tag);
                files.pruneBackups(space, keep);
                return p;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, io);
        return f.whenCompleteAsync((p, t) -> {
            w.ifPresent(world -> world.setAutoSave(autosave));
            if (t == null) log.info("Backed up EndSpace " + space.shortId() + " to " + p.getFileName());
            else log.log(Level.WARNING, "Backup of EndSpace " + space.shortId() + " failed", t);
        }, Sync.main());
    }

    /**
     * Startup consistency check: spaces whose generated world folder is missing are marked BROKEN; folders without a
     * record are reported as orphans (never deleted).
     */
    public void recoverOnStartup() {
        Set<String> known = new HashSet<>();
        int broken = 0;
        for (EndSpace s : services.dir().allSpaces()) {
            known.add(s.shortId());
            if (s.status() == SpaceStatus.ACTIVE && s.worldUuid() != null && !files.exists(s)) {
                log.severe("Recovery: world folder for EndSpace " + s.shortId() + " ('" + s.name() + "') is missing; marking BROKEN.");
                services.spaces.adminSetStatus(null, s.id(), SpaceStatus.BROKEN, "startup: folder missing").join();
                broken++;
            }
        }
        try {
            List<String> orphans = files.orphanFolders(known);
            if (!orphans.isEmpty()) {
                log.warning("Recovery: " + orphans.size() + " EndSpace world folder(s) have no database record: " + orphans
                        + ". They are left untouched; see /endfold admin orphans.");
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not scan for orphaned world folders", e);
        }
        if (broken > 0) log.warning("Recovery: " + broken + " EndSpace(s) need administrator attention (/endfold admin list).");
    }
}
