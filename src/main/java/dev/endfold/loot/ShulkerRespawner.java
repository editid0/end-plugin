package dev.endfold.loot;

import dev.endfold.EndfoldPlugin;
import dev.endfold.model.BlockPos;
import dev.endfold.model.EndSpace;
import dev.endfold.model.ShulkerSpawn;
import dev.endfold.util.Sync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

/**
 * Renewable End City shulkers. The shulkers that generate with a city are recorded once per chunk (a chunk
 * persistent-data flag prevents later bullet-duplicated shulkers from becoming spawn points), tagged with their spawn
 * point, and respawned after a cooldown while a player is nearby. Never spawns in unloaded chunks and never catches up.
 */
final class ShulkerRespawner implements Listener {
    private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final EndfoldPlugin plugin;
    private final NamespacedKey pointKey;
    private final NamespacedKey scannedKey;
    private final Map<UUID, Map<BlockPos, ShulkerSpawn>> cache = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private BukkitTask task;

    ShulkerRespawner(EndfoldPlugin plugin) {
        this.plugin = plugin;
        this.pointKey = new NamespacedKey(plugin, "shulker_point");
        this.scannedKey = new NamespacedKey(plugin, "shulkers_scanned");
    }

    void start() {
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.instances().isManaged(w)) continue;
            for (Chunk c : w.getLoadedChunks()) if (c.isEntitiesLoaded()) scan(c, List.of(c.getEntities()));
        }
        schedule();
    }

    /** (Re)starts the periodic task using the current configuration interval. */
    void schedule() {
        if (task != null) task.cancel();
        long period = Math.max(5, plugin.config().shulkerCheckIntervalSeconds) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        cache.clear();
    }

    // ------------------------------------------------------------------ registration

    @EventHandler
    public void onEntities(EntitiesLoadEvent e) {
        if (plugin.instances().isManaged(e.getWorld())) scan(e.getChunk(), e.getEntities());
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) {
        plugin.instances().spaceOf(e.getWorld()).ifPresent(s -> cache.remove(s.id()));
    }

    private void scan(Chunk chunk, List<Entity> entities) {
        if (!plugin.config().shulkerRespawn) return;
        var pdc = chunk.getPersistentDataContainer();
        if (pdc.has(scannedKey, PersistentDataType.BYTE)) return;
        EndSpace space = plugin.instances().spaceOf(chunk.getWorld()).orElse(null);
        if (space == null) return;
        pdc.set(scannedKey, PersistentDataType.BYTE, (byte) 1);
        for (Entity en : entities) {
            if (!(en instanceof Shulker s) || s.getPersistentDataContainer().has(pointKey, PersistentDataType.STRING)) continue;
            String city = cityOf(s.getLocation());
            if (city == null) continue;
            Location l = s.getLocation();
            BlockPos pos = new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            s.getPersistentDataContainer().set(pointKey, PersistentDataType.STRING, pos.key());
            ShulkerSpawn sp = new ShulkerSpawn(space.id(), pos, city, s.getAttachedFace().name(), 0L, s.getUniqueId());
            Map<BlockPos, ShulkerSpawn> points = cache.get(space.id());
            if (points != null) points.putIfAbsent(pos, sp);
            plugin.services().shulkers.register(sp);
        }
    }

    /** City id (minimum corner of the End City structure) when the location lies inside an End City piece, else null. */
    private static String cityOf(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        var v = loc.toVector();
        for (GeneratedStructure gs : w.getStructures(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, Structure.END_CITY)) {
            for (StructurePiece p : gs.getPieces()) {
                if (p.getBoundingBox().contains(v)) {
                    BoundingBox bb = gs.getBoundingBox();
                    return (int) Math.floor(bb.getMinX()) + "," + (int) Math.floor(bb.getMinY()) + "," + (int) Math.floor(bb.getMinZ());
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Shulker s) || !plugin.config().shulkerRespawn) return;
        String raw = s.getPersistentDataContainer().get(pointKey, PersistentDataType.STRING);
        EndSpace space = plugin.instances().spaceOf(s.getWorld()).orElse(null);
        if (raw == null || space == null) return;
        BlockPos pos;
        try {
            pos = BlockPos.parse(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        long next = System.currentTimeMillis() + plugin.config().shulkerCooldownMillis;
        update(space.id(), pos, next, null);
        Player killer = s.getKiller();
        if (killer != null) Sync.then(plugin.services().progress.shulkerKilled(space.id(), killer.getUniqueId()), plugin::announceGrants);
    }

    private void update(UUID space, BlockPos pos, long next, UUID entity) {
        Map<BlockPos, ShulkerSpawn> points = cache.get(space);
        if (points != null) {
            ShulkerSpawn old = points.get(pos);
            if (old != null) points.put(pos, new ShulkerSpawn(space, pos, old.cityId(), old.attachFace(), next, entity));
        }
        plugin.services().shulkers.schedule(space, pos, next, entity);
    }

    // ------------------------------------------------------------------ respawning

    private void tick() {
        if (!plugin.config().shulkerRespawn) return;
        for (World w : Bukkit.getWorlds()) {
            if (w.getPlayers().isEmpty()) continue;
            EndSpace space = plugin.instances().spaceOf(w).orElse(null);
            if (space == null) continue;
            Map<BlockPos, ShulkerSpawn> points = cache.get(space.id());
            if (points == null) {
                load(space);
                continue;
            }
            process(w, space, points);
        }
    }

    private void load(EndSpace space) {
        if (!loading.add(space.id())) return;
        Sync.then(plugin.services().shulkers.list(space.id()), r -> {
            loading.remove(space.id());
            if (!r.isOk()) return;
            Map<BlockPos, ShulkerSpawn> m = new HashMap<>();
            for (ShulkerSpawn sp : r.value()) m.put(sp.pos(), sp);
            cache.put(space.id(), m);
        });
    }

    private boolean alive(World w, ShulkerSpawn sp) {
        return sp.entityUuid() != null && w.getEntity(sp.entityUuid()) instanceof Shulker s && s.isValid() && !s.isDead();
    }

    private void process(World w, EndSpace space, Map<BlockPos, ShulkerSpawn> points) {
        var cfg = plugin.config();
        long now = System.currentTimeMillis();
        int population = 0;
        Map<String, Integer> perCity = new HashMap<>();
        List<ShulkerSpawn> all = new ArrayList<>(points.values());
        for (ShulkerSpawn sp : all) {
            if (alive(w, sp)) {
                population++;
                perCity.merge(sp.cityId(), 1, Integer::sum);
            }
        }
        double radius = cfg.shulkerActivationRadius;
        boolean any = false;
        for (ShulkerSpawn sp : all) {
            BlockPos p = sp.pos();
            if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) continue;
            Chunk chunk = w.getChunkAt(p.x() >> 4, p.z() >> 4, false);
            if (!chunk.isEntitiesLoaded()) continue;
            if (sp.entityUuid() != null) {
                if (alive(w, sp)) continue;
                if (sp.nextSpawnAt() == 0L) {
                    // The tracked shulker vanished without a death event: start a normal cooldown instead of spawning at once.
                    update(space.id(), p, now + cfg.shulkerCooldownMillis, null);
                    continue;
                }
            }
            if (sp.nextSpawnAt() > now) continue;
            if (cfg.shulkerMaxPopulation > 0 && population >= cfg.shulkerMaxPopulation) continue;
            if (cfg.shulkerMaxPerCity > 0 && perCity.getOrDefault(sp.cityId(), 0) >= cfg.shulkerMaxPerCity) continue;
            Location at = new Location(w, p.x() + 0.5, p.y(), p.z() + 0.5);
            boolean near = false;
            for (Player pl : w.getPlayers()) {
                if (pl.getLocation().distanceSquared(at) <= radius * radius) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            Shulker spawned = spawn(w, sp, at);
            if (spawned == null) continue;
            update(space.id(), p, 0L, spawned.getUniqueId());
            population++;
            perCity.merge(sp.cityId(), 1, Integer::sum);
            any = true;
        }
        if (any) {
            for (Player pl : w.getPlayers()) plugin.messages().send(pl, "shulker.respawned");
        }
    }

    private Shulker spawn(World w, ShulkerSpawn sp, Location at) {
        Block block = at.getBlock();
        if (!block.isEmpty()) return null;
        BlockFace face = null;
        BlockFace recorded = parse(sp.attachFace());
        if (recorded != null && block.getRelative(recorded).getType().isSolid()) face = recorded;
        if (face == null) {
            for (BlockFace f : FACES) {
                if (block.getRelative(f).getType().isSolid()) {
                    face = f;
                    break;
                }
            }
        }
        if (face == null) return null;
        BlockFace attach = face;
        return w.spawn(at, Shulker.class, CreatureSpawnEvent.SpawnReason.CUSTOM, s -> {
            s.setAttachedFace(attach);
            s.getPersistentDataContainer().set(pointKey, PersistentDataType.STRING, sp.pos().key());
        });
    }

    private static BlockFace parse(String s) {
        try {
            return s == null ? null : BlockFace.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
