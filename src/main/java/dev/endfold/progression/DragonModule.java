package dev.endfold.progression;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.service.ProgressService.FightOutcome;
import dev.endfold.util.Sync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Dragon fight tracking, participation and optional scaling for EndSpace worlds only.
 *
 * <p>Fight state lives in memory. If the server restarts mid-fight the surviving dragon is picked up again with fresh
 * tracking (damage and presence start from zero, no second "awakens" announcement); a fight that is abandoned by
 * unloading the world is simply forgotten. Dragon respawns are vanilla (four crystals on the exit portal).</p>
 */
public final class DragonModule implements Listener {
    private static final long CRYSTAL_MEMORY_MS = 120_000L;
    private static final double BED_CREDIT_RADIUS = 24.0;

    private final EndfoldPlugin plugin;
    private final Map<UUID, DragonFightTracker> fights = new HashMap<>();
    private final Map<UUID, UUID> crystalAttacker = new HashMap<>();
    private final Map<UUID, Long> crystalTime = new HashMap<>();
    private BukkitTask task;

    public DragonModule(EndfoldPlugin plugin) { this.plugin = plugin; }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPresence, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        fights.clear();
        crystalAttacker.clear();
        crystalTime.clear();
    }

    EndfoldPlugin plugin() { return plugin; }

    // ------------------------------------------------------------------ fight start

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon) || !plugin.instances().isManaged(dragon.getWorld())) return;
        EndSpace space = plugin.instances().spaceOf(dragon.getWorld()).orElse(null);
        if (space == null) return;
        fights.put(dragon.getWorld().getUID(), new DragonFightTracker(dragon.getUniqueId()));
        Sync.then(plugin.services().progress.fightStarted(space.id()), r -> { });
        plugin.broadcastToSpace(space.id(), plugin.messages().get("dragon.fight-started", Messages.arg("space", space.name())));
        Bukkit.getScheduler().runTask(plugin, () -> scale(dragon, space));
    }

    /** Picks up a dragon that was already alive (world reload, restart) without announcing a new fight. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon) || !plugin.instances().isManaged(dragon.getWorld())) return;
        DragonFightTracker t = fights.get(dragon.getWorld().getUID());
        if (t == null || !t.dragonId.equals(dragon.getUniqueId())) {
            DragonFightTracker nt = new DragonFightTracker(dragon.getUniqueId());
            nt.scaled = true; // never rescale a dragon that may already carry damage
            fights.put(dragon.getWorld().getUID(), nt);
        }
    }

    private void scale(EnderDragon dragon, EndSpace space) {
        var cfg = plugin.config();
        DragonFightTracker t = fights.get(dragon.getWorld().getUID());
        if (!cfg.dragonHealthScaling || t == null || t.scaled || !dragon.isValid()) return;
        t.scaled = true;
        List<Player> active = playersInArena(dragon.getWorld());
        double mult = Math.min(cfg.dragonScaleMax, 1 + cfg.dragonScalePerPlayer * Math.max(0, active.size() - 1));
        if (mult <= 1.0) return;
        AttributeInstance max = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) return;
        max.setBaseValue(max.getBaseValue() * mult);
        dragon.setHealth(max.getValue());
        for (Player p : active) plugin.messages().send(p, "dragon.scaled", Messages.arg("count", active.size()));
    }

    private List<Player> playersInArena(World w) {
        double r = plugin.config().participationRadius;
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() * l.getX() + l.getZ() * l.getZ() <= r * r && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ presence

    private void tickPresence() {
        var it = fights.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            World w = Bukkit.getWorld(en.getKey());
            if (w == null) {
                it.remove();
                continue;
            }
            if (w.getPlayers().isEmpty()) continue;
            if (!(w.getEntity(en.getValue().dragonId) instanceof EnderDragon d) || !d.isValid() || d.isDead()) continue;
            for (Player p : playersInArena(w)) en.getValue().addPresence(p.getUniqueId());
        }
        long now = System.currentTimeMillis();
        if (crystalTime.size() > 64) {
            crystalTime.entrySet().removeIf(en -> {
                boolean old = now - en.getValue() > CRYSTAL_MEMORY_MS;
                if (old) crystalAttacker.remove(en.getKey());
                return old;
            });
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) { fights.remove(e.getWorld().getUID()); }

    // ------------------------------------------------------------------ damage attribution

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof EnderCrystal crystal) {
            if (!plugin.instances().isManaged(crystal.getWorld())) return;
            UUID p = attacker(e);
            if (p != null) {
                crystalAttacker.put(crystal.getUniqueId(), p);
                crystalTime.put(crystal.getUniqueId(), System.currentTimeMillis());
            }
            return;
        }
        EnderDragon dragon = null;
        if (victim instanceof EnderDragon d) dragon = d;
        else if (victim instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon d) dragon = d;
        if (dragon == null || !plugin.instances().isManaged(dragon.getWorld())) return;
        DragonFightTracker t = fights.get(dragon.getWorld().getUID());
        if (t == null || !t.dragonId.equals(dragon.getUniqueId())) return;
        UUID p = attacker(e);
        if (p == null && e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            Player nearest = null;
            double best = BED_CREDIT_RADIUS * BED_CREDIT_RADIUS;
            for (Player pl : dragon.getWorld().getPlayers()) {
                double dist = pl.getLocation().distanceSquared(victim.getLocation());
                if (dist <= best) {
                    best = dist;
                    nearest = pl;
                }
            }
            if (nearest != null) p = nearest.getUniqueId();
        }
        if (p != null) t.addDamage(p, e.getFinalDamage());
    }

    private UUID attacker(EntityDamageEvent e) {
        Entity cause = e.getDamageSource().getCausingEntity();
        Entity direct = e.getDamageSource().getDirectEntity();
        if (e instanceof EntityDamageByEntityEvent be) {
            if (cause == null) cause = be.getDamager();
            if (direct == null) direct = be.getDamager();
        }
        UUID p = resolve(cause);
        return p != null ? p : resolve(direct);
    }

    private UUID resolve(Entity en) {
        if (en == null) return null;
        if (en instanceof Player p) return p.getUniqueId();
        if (en instanceof Projectile pr && pr.getShooter() instanceof Player p) return p.getUniqueId();
        if (en instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p.getUniqueId();
        if (en instanceof EnderCrystal c) return crystalAttacker.get(c.getUniqueId());
        return null;
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon) || !plugin.instances().isManaged(dragon.getWorld())) return;
        EndSpace space = plugin.instances().spaceOf(dragon.getWorld()).orElse(null);
        World world = dragon.getWorld();
        DragonFightTracker t = fights.get(world.getUID());
        if (space == null || t == null || !t.dragonId.equals(dragon.getUniqueId())) return;
        fights.remove(world.getUID());
        var cfg = plugin.config();
        UUID killer = dragon.getKiller() != null ? dragon.getKiller().getUniqueId() : t.lastDamager;
        if (killer == null) killer = t.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (killer == null) killer = playersInArena(world).stream().map(Player::getUniqueId).findFirst().orElse(null);
        if (killer == null) {
            plugin.getLogger().info("Dragon of EndSpace " + space.shortId() + " died with no player on record; no progress recorded.");
            return;
        }
        Set<UUID> participants = t.participants(cfg.participationMinDamage, cfg.participationMinSeconds, killer);
        List<UUID> present = new ArrayList<>();
        for (Player p : world.getPlayers()) present.add(p.getUniqueId());
        DragonBattle battle = world.getEnderDragonBattle();
        int gateways = battle == null ? 0 : battle.getGatewayCount();
        plugin.broadcastToSpace(space.id(), plugin.messages().get("dragon.defeated", Messages.arg("space", space.name()),
                Messages.arg("participants", participants.size())));
        FightOutcome outcome = new FightOutcome(space.id(), killer, new HashMap<>(t.damage), new HashSet<>(participants), gateways);
        Sync.then(plugin.services().progress.recordDragonKill(outcome), r -> {
            if (!r.isOk()) {
                plugin.getLogger().warning("Could not record dragon kill for EndSpace " + space.shortId() + ": " + r.failure());
                return;
            }
            plugin.announceGrants(r);
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.messages().send(p, "dragon.participation");
            }
            for (UUID id : present) {
                if (participants.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.messages().send(p, "dragon.not-participant");
            }
        });
    }

    // ------------------------------------------------------------------ repeatability

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalPlace(PlayerInteractEvent e) {
        if (plugin.config().dragonRepeatable || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var item = e.getItem();
        var block = e.getClickedBlock();
        if (item == null || item.getType() != Material.END_CRYSTAL || block == null || block.getType() != Material.BEDROCK) return;
        World w = block.getWorld();
        if (!plugin.instances().isManaged(w)) return;
        DragonBattle battle = w.getEnderDragonBattle();
        Location portal = battle == null ? null : battle.getEndPortalLocation();
        double px = portal == null ? 0 : portal.getX();
        double pz = portal == null ? 0 : portal.getZ();
        double dx = block.getX() + 0.5 - px, dz = block.getZ() + 0.5 - pz;
        if (Math.abs(dx) <= 3.5 && Math.abs(dz) <= 3.5) e.setCancelled(true);
    }
}
