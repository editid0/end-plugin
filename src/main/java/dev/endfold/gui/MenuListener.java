package dev.endfold.gui;

import dev.endfold.util.Sync;
import java.util.Iterator;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The single listener for all menus: cancels every interaction with a menu inventory and dispatches button clicks. */
public final class MenuListener implements Listener {
    private final MenuManager mgr;

    MenuListener(MenuManager mgr) { this.mgr = mgr; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            guardAccessItem(e, top);
            return;
        }
        // Always cancel first: covers shift-click from the bottom inventory, number keys, offhand swap, double click,
        // drops, creative clones and bundle actions.
        e.setCancelled(true);
        e.setResult(Event.Result.DENY);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() == ClickType.CREATIVE || e.getAction() == InventoryAction.CLONE_STACK) Sync.run(p::updateInventory);
        if (!p.getItemOnCursor().isEmpty()) Sync.run(() -> returnCursor(p));
        if (e.getClickedInventory() != top) return;
        ClickType t = e.getClick();
        if (t != ClickType.LEFT && t != ClickType.RIGHT && t != ClickType.SHIFT_LEFT && t != ClickType.SHIFT_RIGHT) return;
        menu.handleClick(p, e.getSlot(), t);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder(false) instanceof Menu) {
            e.setCancelled(true);
            return;
        }
        // Keep the access item out of containers when it is spread by dragging.
        if (top.getType() != InventoryType.CRAFTING && mgr.isAccessItem(e.getOldCursor())
                && e.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            e.setCancelled(true);
        }
    }

    /**
     * Never delete an item a player holds on the cursor while a menu is open (menu items can never get there because
     * every click is cancelled): put it back into the inventory and drop what does not fit.
     */
    private static void returnCursor(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.isEmpty()) return;
        p.setItemOnCursor(null);
        p.getInventory().addItem(cursor).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder(false) instanceof Menu || e.getDestination().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu m) {
            m.dispose();
            if (e.getPlayer() instanceof Player p && !p.getItemOnCursor().isEmpty()) returnCursor(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { mgr.purge(e.getPlayer()); }

    // ------------------------------------------------------------------ access item

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!mgr.isAccessItem(e.getItem())) return;
        e.setCancelled(true);
        if (mgr.allowClick(e.getPlayer().getUniqueId())) mgr.openMain(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        if (mgr.isAccessItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Iterator<ItemStack> it = e.getDrops().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            if (mgr.isAccessItem(it.next())) {
                it.remove();
                removed = true;
            }
        }
        if (removed) e.getItemsToKeep().removeIf(mgr::isAccessItem);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Sync.later(5L, () -> {
            if (p.isOnline()) mgr.giveAccessItem(p);
        });
    }

    /** Keeps the access item out of chests and other containers. */
    private void guardAccessItem(InventoryClickEvent e, Inventory top) {
        if (top.getType() == InventoryType.CRAFTING) return;
        Inventory clicked = e.getClickedInventory();
        boolean bad = false;
        if (clicked != null && clicked != top && e.getClick().isShiftClick() && mgr.isAccessItem(e.getCurrentItem())) bad = true;
        if (clicked == top && mgr.isAccessItem(e.getCursor())) bad = true;
        if (clicked == top && e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player p) {
            int b = e.getHotbarButton();
            if (b >= 0 && mgr.isAccessItem(p.getInventory().getItem(b))) bad = true;
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND && clicked == top && e.getWhoClicked() instanceof Player p
                && mgr.isAccessItem(p.getInventory().getItemInOffHand())) bad = true;
        if (bad) e.setCancelled(true);
    }
}
