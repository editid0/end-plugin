package dev.endfold.loot;

import dev.endfold.EndfoldPlugin;
import dev.endfold.model.BlockPos;
import dev.endfold.model.EndSpace;
import dev.endfold.repo.LootRepository.PersonalInventory;
import dev.endfold.service.LootService;
import dev.endfold.util.Sync;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;

/**
 * Personal End City chests. Every End City treasure chest in an EndSpace is registered (its position is stored and its
 * loot table removed from the real block); right-clicking it opens a plugin-owned 27-slot inventory that belongs to
 * the clicking player, whose loot is rolled once and persisted.
 *
 * <p>Crash safety: the inventory row and the once-only claim are inserted in one transaction before the inventory is
 * shown. Edits are saved (optimistic revision) one tick after every click/drag, on close, on quit, when the world
 * unloads and synchronously on plugin shutdown; after each successful chest save the player's own data is saved
 * (throttled to about once per second) so the player file catches up with the chest quickly. Residual window: if the
 * server crashes after the player's data file was written (vanilla autosave) but before the chest save of the same
 * click reached the database, an item that was moved from the chest to the player can exist in both places after the
 * restart (a duplicate of at most the items moved within roughly one tick plus database latency). The opposite
 * ordering (chest saved, player not yet saved) can lose the moved items instead, which is the intentional trade-off.
 * </p>
 */
final class PersonalChestManager implements Listener {
    static final NamespacedKey END_CITY_TABLE = NamespacedKey.minecraft("chests/end_city_treasure");
    private static final int SIZE = 27;
    private static final int MAX_RETRIES = 8;

    private final EndfoldPlugin plugin;
    /** Registered container positions per space; presence of the key means the registry has been loaded from the database. */
    private final Map<UUID, Set<BlockPos>> registered = new HashMap<>();
    private final Map<UUID, List<Chunk>> waiting = new HashMap<>();
    private final Map<String, PersonalInventoryHolder> views = new HashMap<>();
    private final Set<String> opening = new HashSet<>();
    private final Set<PersonalInventoryHolder> live = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<UUID, Long> lastPlayerSave = new HashMap<>();
    private final Set<UUID> pendingPlayerSave = new HashSet<>();

    PersonalChestManager(EndfoldPlugin plugin) { this.plugin = plugin; }

    void start() {
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.instances().isManaged(w)) continue;
            for (Chunk c : w.getLoadedChunks()) onChunk(c);
        }
    }

    // ------------------------------------------------------------------ registration

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) { onChunk(e.getChunk()); }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        if (!plugin.instances().isManaged(e.getWorld())) return;
        Sync.later(1L, () -> {
            if (Bukkit.getWorld(e.getWorld().getUID()) == null) return;
            for (Chunk c : e.getWorld().getLoadedChunks()) onChunk(c);
        });
    }

    private void onChunk(Chunk chunk) {
        if (!plugin.config().personalChests) return;
        World world = chunk.getWorld();
        EndSpace space = plugin.instances().spaceOf(world).orElse(null);
        if (space == null) return;
        if (registered.containsKey(space.id())) {
            scan(chunk, space);
            return;
        }
        List<Chunk> list = waiting.get(space.id());
        if (list != null) {
            list.add(chunk);
            return;
        }
        List<Chunk> fresh = new ArrayList<>();
        fresh.add(chunk);
        waiting.put(space.id(), fresh);
        Sync.then(plugin.services().loot.containers(space.id()), r -> {
            List<Chunk> chunks = waiting.remove(space.id());
            if (!r.isOk()) {
                plugin.getLogger().warning("Could not load the container registry of EndSpace " + space.shortId() + ": " + r.failure());
                return;
            }
            Set<BlockPos> set = new HashSet<>();
            r.value().forEach(c -> set.add(c.pos()));
            registered.put(space.id(), set);
            if (chunks != null) for (Chunk c : chunks) if (c.isLoaded()) scan(c, space);
        });
    }

    private void scan(Chunk chunk, EndSpace space) {
        Set<BlockPos> set = registered.get(space.id());
        for (BlockState st : chunk.getTileEntities(b -> b.getType() == Material.CHEST, false)) {
            if (!(st instanceof Lootable l)) continue;
            LootTable t = l.getLootTable();
            if (t == null || !END_CITY_TABLE.equals(t.getKey())) continue;
            BlockPos pos = new BlockPos(st.getX(), st.getY(), st.getZ());
            register(chunk.getWorld(), space, set, pos);
        }
    }

    private void register(World world, EndSpace space, Set<BlockPos> set, BlockPos pos) {
        set.add(pos);
        plugin.services().loot.registerContainer(space.id(), pos, END_CITY_TABLE.toString()).thenAcceptAsync(r -> {
            if (r.isOk()) clearTable(world, pos);
            else plugin.getLogger().warning("Could not register End City chest " + pos + ": " + r.failure());
        }, Sync.main());
    }

    private void clearTable(World world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (b.getType() != Material.CHEST) return;
        if (!(b.getState(false) instanceof Lootable l) || l.getLootTable() == null) return;
        l.clearLootTable();
        if (b.getState(false) instanceof Lootable again && again.getLootTable() != null && again instanceof BlockState bs) {
            again.clearLootTable();
            bs.update(true, false);
        }
    }

    boolean isRegistered(Block b) {
        if (b == null) return false;
        return isRegistered(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private boolean isRegistered(World w, int x, int y, int z) {
        if (!plugin.instances().isManaged(w)) return false;
        EndSpace s = plugin.instances().spaceOf(w).orElse(null);
        if (s == null) return false;
        Set<BlockPos> set = registered.get(s.id());
        return set != null && set.contains(new BlockPos(x, y, z));
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !plugin.config().personalChests) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST || !plugin.instances().isManaged(block.getWorld())) return;
        EndSpace space = plugin.instances().spaceOf(block.getWorld()).orElse(null);
        if (space == null) return;
        Set<BlockPos> set = registered.get(space.id());
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        if (set == null) {
            // Registry still loading: never let the vanilla chest roll its loot.
            deny(e);
            if (e.getHand() == EquipmentSlot.HAND) {
                plugin.messages().send(e.getPlayer(), "loot.busy");
                onChunk(block.getChunk());
            }
            return;
        }
        if (!set.contains(pos)) {
            if (!(block.getState(false) instanceof Lootable l) || l.getLootTable() == null || !END_CITY_TABLE.equals(l.getLootTable().getKey())) return;
            register(block.getWorld(), space, set, pos);
        }
        deny(e);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!plugin.instances().canEnter(space.id(), e.getPlayer())) return; // non-members (e.g. admins) get no loot
        open(e.getPlayer(), space, pos, block);
    }

    private static void deny(PlayerInteractEvent e) {
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
    }

    private static String key(UUID space, UUID player, BlockPos pos) { return space + "/" + player + "/" + pos.key(); }

    void open(Player player, EndSpace space, BlockPos pos, Block block) {
        String key = key(space.id(), player.getUniqueId(), pos);
        // A previous view of the same chest that is still saving must finish first, otherwise the reopen could read
        // contents from before the last edit (duplicating items that were just taken out).
        boolean saving = live.stream().anyMatch(h -> h.key().equals(key));
        if (views.containsKey(key) || saving || !opening.add(key)) {
            plugin.messages().send(player, "loot.busy");
            return;
        }
        LootService loot = plugin.services().loot;
        Sync.then(loot.load(space.id(), player.getUniqueId(), pos), r -> {
            if (!r.isOk()) {
                opening.remove(key);
                plugin.messages().fail(player, r.failure());
                return;
            }
            if (!player.isOnline()) {
                opening.remove(key);
                return;
            }
            if (r.value().isPresent()) {
                show(player, space, r.value().get(), false);
                return;
            }
            byte[] bytes;
            try {
                bytes = roll(player, block, space, pos);
            } catch (RuntimeException ex) {
                opening.remove(key);
                plugin.getLogger().log(Level.SEVERE, "Could not roll End City loot at " + pos, ex);
                plugin.messages().send(player, "loot.saving-failed");
                return;
            }
            Sync.then(loot.createOnce(space.id(), player.getUniqueId(), pos, END_CITY_TABLE.toString(), SIZE, bytes), c -> {
                if (!c.isOk()) {
                    opening.remove(key);
                    plugin.messages().fail(player, c.failure());
                    return;
                }
                if (!player.isOnline()) {
                    opening.remove(key);
                    return;
                }
                show(player, space, c.value().inventory(), c.value().fresh());
            });
        });
    }

    private byte[] roll(Player player, Block block, EndSpace space, BlockPos pos) {
        LootTable table = Bukkit.getLootTable(END_CITY_TABLE);
        ItemStack[] slots = new ItemStack[SIZE];
        if (table != null) {
            long seed = space.seed() * 31 + player.getUniqueId().getMostSignificantBits() * 17 + player.getUniqueId().getLeastSignificantBits() * 13
                    + pos.x() * 3129871L + pos.z() * 116129781L + pos.y();
            Random random = new Random(seed);
            AttributeInstance luckAttr = player.getAttribute(Attribute.LUCK);
            float luck = luckAttr == null ? 0f : (float) luckAttr.getValue();
            LootContext ctx = new LootContext.Builder(block.getLocation()).lootedEntity(player).luck(luck).build();
            Collection<ItemStack> items = table.populateLoot(random, ctx);
            distribute(items, slots, random);
        }
        return ItemStack.serializeItemsAsBytes(slots);
    }

    /** Spreads loot over random empty slots like a vanilla chest, splitting stacks while room remains. */
    static void distribute(Collection<ItemStack> items, ItemStack[] slots, Random random) {
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) empty.add(i);
        List<ItemStack> output = new ArrayList<>();
        List<ItemStack> splittable = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.isEmpty()) continue;
            if (it.getAmount() > 1) splittable.add(it);
            else output.add(it);
        }
        int free = empty.size() - output.size();
        while (free - output.size() - splittable.size() > 0 && !splittable.isEmpty()) {
            ItemStack stack = splittable.remove(random.nextInt(splittable.size()));
            int take = 1 + random.nextInt(Math.max(1, stack.getAmount() / 2));
            ItemStack part = stack.clone();
            part.setAmount(take);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() > 1 && random.nextBoolean()) splittable.add(stack);
            else output.add(stack);
            if (part.getAmount() > 1 && random.nextBoolean()) splittable.add(part);
            else output.add(part);
        }
        output.addAll(splittable);
        java.util.Collections.shuffle(empty, random);
        java.util.Collections.shuffle(output, random);
        for (ItemStack it : output) {
            if (empty.isEmpty()) break;
            slots[empty.remove(empty.size() - 1)] = it;
        }
    }

    private void show(Player player, EndSpace space, PersonalInventory stored, boolean fresh) {
        String key = key(space.id(), player.getUniqueId(), stored.pos());
        opening.remove(key);
        if (views.containsKey(key)) return;
        PersonalInventoryHolder h = new PersonalInventoryHolder(space.id(), player.getUniqueId(), stored.pos(), stored.rev());
        Inventory inv = Bukkit.createInventory(h, SIZE, plugin.messages().get("loot.personal-chest-title"));
        try {
            ItemStack[] contents = ItemStack.deserializeItemsFromBytes(stored.contents());
            for (int i = 0; i < Math.min(contents.length, SIZE); i++) {
                if (!contents[i].isEmpty()) inv.setItem(i, contents[i]);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Stored chest contents at " + stored.pos() + " for " + player.getName() + " are unreadable; not opening to avoid overwriting them", ex);
            plugin.messages().send(player, "loot.saving-failed");
            return;
        }
        h.inventory = inv;
        views.put(key, h);
        live.add(h);
        if (player.openInventory(inv) == null) {
            views.remove(key);
            live.remove(h);
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1f);
        if (fresh) {
            plugin.messages().send(player, "loot.first-open");
            Sync.then(plugin.services().progress.chestLooted(space.id(), player.getUniqueId()), plugin::announceGrants);
        }
    }

    // ------------------------------------------------------------------ persistence

    private static PersonalInventoryHolder holderOf(Inventory inv) {
        return inv != null && inv.getHolder(false) instanceof PersonalInventoryHolder h ? h : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        PersonalInventoryHolder h = holderOf(e.getView().getTopInventory());
        if (h != null) schedule(h);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        PersonalInventoryHolder h = holderOf(e.getView().getTopInventory());
        if (h != null) schedule(h);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        PersonalInventoryHolder h = holderOf(e.getInventory());
        if (h == null) return;
        views.remove(h.key(), h);
        h.closed = true;
        if (e.getPlayer() instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1f);
        if (h.stale) {
            live.remove(h);
            return;
        }
        h.dirty = true;
        flush(h);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (PersonalInventoryHolder h : new ArrayList<>(live)) {
            if (!h.player.equals(id) || h.stale) continue;
            h.dirty = true;
            flush(h);
        }
        lastPlayerSave.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent e) {
        EndSpace space = plugin.instances().spaceOf(e.getWorld()).orElse(null);
        if (space == null) return;
        for (PersonalInventoryHolder h : new ArrayList<>(live)) {
            if (!h.spaceId.equals(space.id())) continue;
            if (h.inventory != null) {
                for (HumanEntity v : new ArrayList<>(h.inventory.getViewers())) v.closeInventory();
            }
            if (!h.stale) {
                h.dirty = true;
                flush(h);
            }
        }
        registered.remove(space.id());
        waiting.remove(space.id());
    }

    private void schedule(PersonalInventoryHolder h) {
        if (h.scheduled || h.stale) return;
        h.scheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            h.scheduled = false;
            h.dirty = true;
            flush(h);
        });
    }

    private void flush(PersonalInventoryHolder h) {
        if (h.stale || h.inventory == null) return;
        if (h.inflight != null) {
            h.dirty = true;
            return;
        }
        byte[] bytes = ItemStack.serializeItemsAsBytes(h.inventory.getContents());
        h.dirty = false;
        CompletableFuture<dev.endfold.service.Result<Long>> f = plugin.services().loot.save(h.spaceId, h.player, h.pos, bytes, h.rev);
        h.inflight = f;
        f.whenCompleteAsync((r, t) -> onSaved(h, r, t), Sync.main());
    }

    private void onSaved(PersonalInventoryHolder h, dev.endfold.service.Result<Long> r, Throwable t) {
        h.inflight = null;
        if (t == null && r != null && r.isOk()) {
            h.rev = r.value();
            h.retries = 0;
            h.failureNotified = false;
            playerSave(h.player);
            if (h.dirty) flush(h);
            else if (h.closed) live.remove(h);
            return;
        }
        h.dirty = true;
        recover(h);
    }

    /** A save failed: either the revision moved on (reload and reopen) or the database hiccuped (retry with back-off). */
    private void recover(PersonalInventoryHolder h) {
        Sync.then(plugin.services().loot.load(h.spaceId, h.player, h.pos), r -> {
            if (r.isOk() && r.value().isPresent() && r.value().get().rev() != h.rev) {
                boolean wasOpen = !h.closed;
                h.stale = true;
                h.dirty = false;
                live.remove(h);
                views.remove(h.key(), h);
                Player p = Bukkit.getPlayer(h.player);
                if (h.inventory != null) for (HumanEntity v : new ArrayList<>(h.inventory.getViewers())) v.closeInventory();
                EndSpace space = plugin.services().dir().space(h.spaceId).orElse(null);
                if (p != null && space != null && wasOpen) {
                    Sync.later(2L, () -> {
                        World w = Bukkit.getWorld(space.worldName());
                        if (w != null && p.isOnline()) open(p, space, h.pos, w.getBlockAt(h.pos.x(), h.pos.y(), h.pos.z()));
                    });
                }
                return;
            }
            if (++h.retries > MAX_RETRIES) {
                plugin.getLogger().severe("Giving up saving personal chest " + h.pos + " of " + h.player + " after repeated failures; contents stay in memory until shutdown.");
                return;
            }
            Player p = Bukkit.getPlayer(h.player);
            if (p != null && !h.failureNotified) {
                h.failureNotified = true;
                plugin.messages().send(p, "loot.saving-failed");
            }
            Sync.later(40L, () -> flush(h));
        });
    }

    private void playerSave(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = lastPlayerSave.get(id);
        if (last == null || now - last >= 1000L) {
            lastPlayerSave.put(id, now);
            p.saveData();
        } else if (pendingPlayerSave.add(id)) {
            Sync.later(20L, () -> {
                pendingPlayerSave.remove(id);
                Player q = Bukkit.getPlayer(id);
                if (q == null) return;
                lastPlayerSave.put(id, System.currentTimeMillis());
                q.saveData();
            });
        }
    }

    /** Saves every open or unsaved personal inventory synchronously. Called while the database thread is still alive. */
    void shutdown() {
        for (PersonalInventoryHolder h : new ArrayList<>(live)) {
            if (h.stale || h.inventory == null) continue;
            try {
                if (h.inflight != null) {
                    var r = h.inflight.join();
                    if (r != null && r.isOk()) h.rev = r.value();
                }
                var r = plugin.services().loot.save(h.spaceId, h.player, h.pos, ItemStack.serializeItemsAsBytes(h.inventory.getContents()), h.rev).join();
                if (r.isOk()) h.rev = r.value();
                else plugin.getLogger().severe("Could not save personal chest " + h.pos + " of " + h.player + " on shutdown: " + r.failure());
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Could not save personal chest " + h.pos + " on shutdown", ex);
            }
            Player p = Bukkit.getPlayer(h.player);
            if (p != null) p.saveData();
        }
        live.clear();
        views.clear();
        opening.clear();
    }

    // ------------------------------------------------------------------ protection

    private boolean protect() { return plugin.config().protectContainers; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!protect() || !isRegistered(e.getBlock())) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && p.hasPermission("endfold.admin.worlds")) return;
        e.setCancelled(true);
        plugin.messages().send(p, "loot.protected");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!protect()) return;
        if (registeredInventory(e.getSource()) || registeredInventory(e.getDestination())) e.setCancelled(true);
    }

    private boolean registeredInventory(Inventory inv) {
        var loc = inv.getLocation();
        return loc != null && loc.getWorld() != null && isRegistered(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!personalOn() || e.getInventory().getHolder(false) instanceof PersonalInventoryHolder) return;
        if (registeredInventory(e.getInventory())) e.setCancelled(true);
    }

    private boolean personalOn() { return plugin.config().personalChests; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (protect() && e.getBlocks().stream().anyMatch(this::isRegistered)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (protect() && e.getBlocks().stream().anyMatch(this::isRegistered)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (protect()) e.blockList().removeIf(this::isRegistered);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (protect()) e.blockList().removeIf(this::isRegistered);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (protect() && isRegistered(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!protect()) return;
        Material m = e.getBlockPlaced().getType();
        if (m != Material.CHEST && m != Material.TRAPPED_CHEST) return;
        Block b = e.getBlockPlaced();
        for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (isRegistered(b.getRelative(f))) {
                e.setCancelled(true);
                plugin.messages().send(e.getPlayer(), "loot.protected");
                return;
            }
        }
    }
}
