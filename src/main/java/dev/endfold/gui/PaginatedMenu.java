package dev.endfold.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import dev.endfold.config.Messages;

/** Six-row list menu: entries in the middle, back/prev/close/next in the bottom row, optional toolbar in the top row. */
abstract class PaginatedMenu<T> extends Menu {
    static final int[] CONTENT = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    protected int page;
    private final String emptyBase;

    protected PaginatedMenu(MenuManager mgr, Player viewer, Runnable back, String emptyBase, int page) {
        super(mgr, viewer, 6, back);
        this.emptyBase = emptyBase;
        this.page = page;
    }

    protected abstract List<T> entries();

    protected abstract ItemStack icon(T entry);

    protected abstract void clicked(T entry, Click c);

    /** Top-row controls (filter, sort, header). */
    protected void toolbar() {}

    /** Called after the page changed so subclasses can load data for it. */
    protected void pageChanged(List<T> visible) {}

    /** Empty-state; the default shows an explanatory item, subclasses may add a call-to-action button. */
    protected void renderEmpty() { set(centerSlot(), ui.item("empty", Material.PAPER, emptyBase)); }

    protected final int pageSize() { return Math.min(CONTENT.length, Math.max(1, plugin.config().paginationSize)); }

    protected final List<T> visible() {
        List<T> all = entries();
        int per = pageSize();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int from = Math.max(0, Math.min(page, pages - 1)) * per;
        return all.subList(Math.min(from, all.size()), Math.min(all.size(), from + per));
    }

    @Override
    protected final void render() {
        List<T> all = loading || failed ? List.of() : entries();
        int per = pageSize();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        toolbar();
        navigation();
        set(closeSlot(), ui.builder("close", Material.BARRIER, "gui.common.close")
                .line(msg().get("gui.common.page", Messages.arg("page", page + 1), Messages.arg("pages", pages))).build(),
                c -> c.player().closeInventory());
        if (loading) {
            showLoading();
            return;
        }
        if (failed) {
            showFailed();
            return;
        }
        if (all.isEmpty()) {
            renderEmpty();
            return;
        }
        int start = page * per;
        for (int i = 0; i < per && start + i < all.size(); i++) {
            T entry = all.get(start + i);
            set(CONTENT[i], icon(entry), c -> clicked(entry, c));
        }
        if (page > 0) {
            set(prevSlot(), ui.item("prev", Material.ARROW, "gui.common.prev"), c -> {
                page--;
                mgr.pageSound(c.player());
                refresh();
                pageChanged(visible());
            });
        }
        if (page < pages - 1) {
            set(nextSlot(), ui.item("next", Material.ARROW, "gui.common.next"), c -> {
                page++;
                mgr.pageSound(c.player());
                refresh();
                pageChanged(visible());
            });
        }
    }
}
