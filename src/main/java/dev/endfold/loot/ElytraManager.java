package dev.endfold.loot;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.PendingDelivery;
import dev.endfold.service.Failure;
import dev.endfold.util.Sync;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

/**
 * Personal End Ship Elytra. The item frame stays in the world (fixed, invulnerable, undroppable); every player claims
 * their own Elytra from it. A claim and its outbox row are committed first, the item is then given and the player's
 * data saved, and only then is the delivery marked complete, so a crash can duplicate nothing: redelivery detects the
 * item through its delivery-id tag.
 */
final class ElytraManager implements Listener {
    private final EndfoldPlugin plugin;
    private final NamespacedKey shipKey;
    private final NamespacedKey placedKey;
    private final NamespacedKey deliveryKey;
    private final Set<UUID> claiming = new HashSet<>();
    private final Set<UUID> delivering = new HashSet<>();

    ElytraManager(EndfoldPlugin plugin) {
        this.plugin = plugin;
        this.shipKey = new NamespacedKey(plugin, "ship_id");
        this.placedKey = new NamespacedKey(plugin, "player_placed");
        this.deliveryKey = new NamespacedKey(plugin, "delivery_id");
    }

    void start() {
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.instances().isManaged(w)) continue;
            for (Chunk c : w.getLoadedChunks()) if (c.isEntitiesLoaded()) scan(c.getEntities());
        }
    }

    // ------------------------------------------------------------------ detection

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (plugin.instances().isManaged(e.getWorld())) scan(e.getEntities().toArray(new Entity[0]));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        if (plugin.instances().isManaged(c.getWorld()) && c.isEntitiesLoaded()) scan(c.getEntities());
    }

    private void scan(Entity[] entities) {
        for (Entity en : entities) if (en instanceof ItemFrame f) shipId(f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getEntity() instanceof ItemFrame f && plugin.instances().isManaged(f.getWorld())) {
            f.getPersistentDataContainer().set(placedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** @return the ship id of an eligible End Ship frame (tagging and securing it on first sight), or null. */
    private String shipId(ItemFrame f) {
        if (!plugin.instances().isManaged(f.getWorld())) return null;
        PersistentDataContainer pdc = f.getPersistentDataContainer();
        String id = pdc.get(shipKey, PersistentDataType.STRING);
        if (id != null) return id;
        if (pdc.has(placedKey, PersistentDataType.BYTE)) return null;
        if (f.getItem().getType() != Material.ELYTRA) return null;
        id = locateShip(f.getLocation());
        if (id == null) return null;
        pdc.set(shipKey, PersistentDataType.STRING, id);
        secure(f);
        return id;
    }

    private static void secure(ItemFrame f) {
        f.setFixed(true);
        f.setInvulnerable(true);
        f.setItemDropChance(0f);
    }

    private boolean isShip(Entity e) {
        return e instanceof ItemFrame f && shipId(f) != null;
    }

    /** Id ("x,y,z" of the ship piece's minimum corner) of the End Ship piece containing the location, else null. */
    private static String locateShip(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        var v = loc.toVector();
        BoundingBox other = null;
        for (GeneratedStructure gs : w.getStructures(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, Structure.END_CITY)) {
            for (StructurePiece p : gs.getPieces()) {
                BoundingBox bb = p.getBoundingBox();
                if (!bb.contains(v)) continue;
                if (isShipBox(bb)) return key(bb);
                if (other == null || volume(bb) < volume(other)) other = bb;
            }
        }
        // Size heuristic failed (unexpected rotation/format): any End City piece still identifies the frame as generated.
        return other == null ? null : key(other);
    }

    private static boolean isShipBox(BoundingBox bb) {
        double dx = bb.getWidthX(), dy = bb.getHeight(), dz = bb.getWidthZ();
        boolean y = Math.abs(dy - 24) <= 1;
        boolean a = Math.abs(dx - 13) <= 1 && Math.abs(dz - 29) <= 1;
        boolean b = Math.abs(dx - 29) <= 1 && Math.abs(dz - 13) <= 1;
        return y && (a || b);
    }

    private static double volume(BoundingBox bb) { return bb.getVolume(); }

    private static String key(BoundingBox bb) { return (int) Math.floor(bb.getMinX()) + "," + (int) Math.floor(bb.getMinY()) + "," + (int) Math.floor(bb.getMinZ()); }

    // ------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) { interact(e.getPlayer(), e.getRightClicked(), e.getHand(), e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) { interact(e.getPlayer(), e.getRightClicked(), e.getHand(), e); }

    private void interact(Player p, Entity clicked, EquipmentSlot hand, org.bukkit.event.Cancellable ev) {
        if (!(clicked instanceof ItemFrame f)) return;
        String ship = shipId(f);
        if (ship == null) return;
        ev.setCancelled(true);
        if (hand != EquipmentSlot.HAND) return;
        if (!plugin.config().elytraEnabled) return;
        EndSpace space = plugin.instances().spaceOf(f.getWorld()).orElse(null);
        // Only members earn rewards (admins inspecting with the teleport bypass do not).
        if (space == null || !plugin.instances().canEnter(space.id(), p) || !claiming.add(p.getUniqueId())) return;
        UUID deliveryId = UUID.randomUUID();
        ItemStack item = new ItemStack(Material.ELYTRA);
        tag(item, deliveryId);
        Sync.then(plugin.services().elytra.claim(space.id(), p.getUniqueId(), ship, deliveryId, item.serializeAsBytes()), r -> {
            claiming.remove(p.getUniqueId());
            if (!r.isOk()) {
                if (r.failure() == Failure.ALREADY_CLAIMED) plugin.messages().send(p, "elytra.already");
                else plugin.messages().fail(p, r.failure());
                return;
            }
            Sync.then(plugin.services().progress.elytraClaimed(space.id(), p.getUniqueId()), plugin::announceGrants);
            if (!p.isOnline()) return; // stays in the outbox, delivered on next join
            if (give(p, r.value())) plugin.messages().send(p, "elytra.claimed");
            else plugin.messages().send(p, "elytra.inventory-full");
        });
    }

    /** Marks an outbox entry delivered, retrying a few times so a transient DB error cannot cause a redelivery. */
    private void markDelivered(UUID deliveryId, int attempt) {
        plugin.services().elytra.markDelivered(deliveryId).whenComplete((r, t) -> {
            if ((t != null || r == null || !r.isOk()) && attempt < 5) {
                Sync.later(20L * (attempt + 1), () -> markDelivered(deliveryId, attempt + 1));
            } else if (t != null || r == null || !r.isOk()) {
                plugin.getLogger().severe("Could not mark Elytra delivery " + deliveryId + " as delivered; it may be redelivered on next join.");
            }
        });
    }

    private void tag(ItemStack item, UUID deliveryId) {
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(deliveryKey, PersistentDataType.STRING, deliveryId.toString());
        item.setItemMeta(m);
    }

    /** Adds the delivery to the inventory, saves the player and marks the delivery done. @return false if it did not fit. */
    private boolean give(Player p, PendingDelivery d) {
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(d.itemBytes());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Unreadable pending delivery " + d.deliveryId() + " for " + p.getName(), ex);
            return false;
        }
        if (!item.getItemMeta().getPersistentDataContainer().has(deliveryKey, PersistentDataType.STRING)) tag(item, d.deliveryId());
        if (!p.getInventory().addItem(item).isEmpty()) return false;
        p.saveData();
        markDelivered(d.deliveryId(), 0);
        return true;
    }

    private boolean holds(Player p, UUID deliveryId) {
        String id = deliveryId.toString();
        for (ItemStack[] arr : new ItemStack[][] {p.getInventory().getContents(), p.getEnderChest().getContents()}) {
            for (ItemStack it : arr) {
                if (it == null || it.isEmpty() || !it.hasItemMeta()) continue;
                if (id.equals(it.getItemMeta().getPersistentDataContainer().get(deliveryKey, PersistentDataType.STRING))) return true;
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        return !cursor.isEmpty() && cursor.hasItemMeta()
                && id.equals(cursor.getItemMeta().getPersistentDataContainer().get(deliveryKey, PersistentDataType.STRING));
    }

    /** Delivers outbox items that are owed to the player. */
    void deliverPending(Player p, boolean notifyIfNone) {
        if (!delivering.add(p.getUniqueId())) return;
        Sync.then(plugin.services().elytra.pending(p.getUniqueId()), r -> {
            delivering.remove(p.getUniqueId());
            if (!r.isOk() || !p.isOnline()) return;
            int count = 0;
            boolean full = false;
            List<PendingDelivery> list = new ArrayList<>(r.value());
            for (PendingDelivery d : list) {
                if (holds(p, d.deliveryId())) {
                    plugin.services().elytra.markDelivered(d.deliveryId()); // crash redelivery: the item is already there
                    continue;
                }
                if (give(p, d)) count++;
                else full = true;
            }
            if (count > 0 || notifyIfNone && !full) plugin.messages().send(p, "elytra.delivered-pending", Messages.arg("count", count));
            if (full) plugin.messages().send(p, "elytra.inventory-full");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (!isShip(e.getEntity())) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof Player p) plugin.messages().send(p, "elytra.frame-protected");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(HangingBreakEvent e) {
        if (isShip(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameChange(PlayerItemFrameChangeEvent e) {
        if (isShip(e.getItemFrame())) e.setCancelled(true);
    }
}
