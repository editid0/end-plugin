package dev.endfold.gui;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Role;
import dev.endfold.service.Result;
import dev.endfold.util.Sync;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Base class of every menu. One instance is one open screen for one player; state (page, tab, loaded data) lives in the
 * instance. Menus never block: asynchronous data is requested via {@link #load}/{@link #async} and rendered when it
 * arrives, but only if the very same menu is still open for that player.
 */
abstract class Menu implements InventoryHolder {
    protected final MenuManager mgr;
    protected final EndfoldPlugin plugin;
    protected final Ui ui;
    protected final UUID viewerId;
    protected final int rows;
    /** Where the back button leads; {@code null} hides the button. */
    protected final Runnable back;
    private final Button[] buttons;
    private Inventory inventory;
    private boolean disposed;
    /** True while the primary data of this menu is being fetched. */
    protected boolean loading;
    /** True if the primary data could not be fetched. */
    protected boolean failed;

    protected Menu(MenuManager mgr, Player viewer, int rows, Runnable back) {
        this.mgr = mgr;
        this.plugin = mgr.plugin();
        this.ui = mgr.ui();
        this.viewerId = viewer.getUniqueId();
        this.rows = rows;
        this.back = back;
        this.buttons = new Button[rows * 9];
    }

    // ------------------------------------------------------------------ to implement

    protected abstract Component title();

    /** Fills the menu. Called on every refresh; must be cheap and non-blocking and must only read in-memory state. */
    protected abstract void render();

    /** Called once after the inventory was shown; start asynchronous loads here. */
    protected void onOpened() {}

    /** Re-checked on every click (permissions can change while a menu is open). */
    protected boolean allowed(Player p) { return true; }

    // ------------------------------------------------------------------ lifecycle

    final void open() {
        if (!Bukkit.isPrimaryThread()) {
            Sync.run(this::open);
            return;
        }
        Player p = viewer();
        if (p == null) return;
        inventory = Bukkit.createInventory(this, rows * 9, title());
        draw();
        p.openInventory(inventory);
        onOpened();
    }

    final void refresh() {
        if (isOpen()) draw();
    }

    final boolean isOpen() {
        Player p = viewer();
        return !disposed && inventory != null && p != null && p.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    final void dispose() { disposed = true; }

    final Player viewer() { return Bukkit.getPlayer(viewerId); }

    @Override
    public Inventory getInventory() { return inventory; }

    private void draw() {
        java.util.Arrays.fill(buttons, null);
        inventory.clear();
        try {
            render();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to render menu " + getClass().getSimpleName(), e);
        }
        ItemStack filler = ui.filler();
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] != null) inventory.setItem(i, buttons[i].item());
            else if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    // ------------------------------------------------------------------ click handling

    final void handleClick(Player p, int slot, ClickType type) {
        if (disposed || slot < 0 || slot >= buttons.length) return;
        Button b = buttons[slot];
        if (b == null || b.action() == null) return;
        if (!allowed(p)) {
            p.closeInventory();
            plugin.messages().fail(p, dev.endfold.service.Failure.NO_PERMISSION);
            return;
        }
        if (!mgr.allowClick(p.getUniqueId()) || mgr.isLocked(p.getUniqueId())) return;
        mgr.clickSound(p);
        try {
            b.action().accept(new Click(p, type, slot));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Menu click failed in " + getClass().getSimpleName(), e);
            p.closeInventory();
        }
    }

    // ------------------------------------------------------------------ building

    protected final void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < buttons.length) {
            buttons[slot] = null;
            inventory.setItem(slot, item);
        }
    }

    protected final void set(int slot, ItemStack item, Consumer<Click> action) {
        if (slot >= 0 && slot < buttons.length) buttons[slot] = new Button(item, action);
    }

    protected final int centerSlot() { return ((rows - 1) / 2) * 9 + 4; }

    protected final int backSlot() { return rows * 9 - 9; }

    protected final int closeSlot() { return rows * 9 - 5; }

    protected final int prevSlot() { return rows * 9 - 6; }

    protected final int nextSlot() { return rows * 9 - 4; }

    /** Back (if any) and close buttons in the standard positions. */
    protected final void navigation() {
        if (back != null) set(backSlot(), ui.item("back", Material.ARROW, "gui.common.back"), c -> back.run());
        set(closeSlot(), ui.item("close", Material.BARRIER, "gui.common.close"), c -> c.player().closeInventory());
    }

    protected final void showLoading() { set(centerSlot(), ui.item("loading", Material.CLOCK, "gui.common.loading")); }

    protected final void showFailed() {
        set(centerSlot(), ui.item("error", Material.BARRIER, "gui.common.load-failed"), c -> reload());
    }

    /** Shown when the space this menu is about is gone or the viewer lost access. */
    protected final void showGone() {
        navigation();
        set(centerSlot(), ui.item("error", Material.BARRIER, "gui.common.gone"), c -> {
            if (back != null) back.run();
            else c.player().closeInventory();
        });
    }

    /** Re-fetches data. Menus with async data override this. */
    protected void reload() { refresh(); }

    // ------------------------------------------------------------------ async helpers

    /** Runs {@code onOk} on the server thread if the future succeeded and this menu is still open, then re-renders. */
    protected final <T> void async(CompletableFuture<Result<T>> f, Consumer<T> onOk) {
        f.whenCompleteAsync((r, t) -> {
            if (!isOpen()) return;
            if (t == null && r != null && r.isOk()) {
                onOk.accept(r.value());
                refresh();
            }
        }, Sync.main());
    }

    /** Like {@link #async} for the menu's primary data: toggles {@link #loading} / {@link #failed}. */
    protected final <T> void load(CompletableFuture<Result<T>> f, Consumer<T> onOk) {
        loading = true;
        failed = false;
        refresh();
        f.whenCompleteAsync((r, t) -> {
            if (!isOpen()) return;
            loading = false;
            if (t == null && r != null && r.isOk()) onOk.accept(r.value());
            else failed = true;
            refresh();
        }, Sync.main());
    }

    /** Runs a service action under the per-player pending lock; {@code then} runs only if this menu is still open. */
    protected final <T> void perform(Player p, Supplier<CompletableFuture<Result<T>>> call, Consumer<Result<T>> then) {
        mgr.exec(p, call, r -> {
            if (isOpen()) then.accept(r);
        });
    }

    // ------------------------------------------------------------------ domain helpers

    protected final Messages msg() { return plugin.messages(); }

    protected final Optional<EndSpace> space(UUID id) { return plugin.services().dir().space(id); }

    protected final Optional<Role> roleOf(UUID spaceId) { return plugin.services().dir().role(spaceId, viewerId); }

    protected final boolean canInvite(EndSpace s, Role r) {
        return s.type() == dev.endfold.model.SpaceType.GROUP && s.status() == dev.endfold.model.SpaceStatus.ACTIVE
                && (r.atLeast(Role.ADMIN) || plugin.config().membersCanInvite);
    }
}
