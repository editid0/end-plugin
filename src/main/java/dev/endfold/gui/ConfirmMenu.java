package dev.endfold.gui;

import dev.endfold.service.Result;
import dev.endfold.util.Sync;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Reusable confirmation screen for destructive actions. Dangerous actions require shift + left click. Escape or the
 * cancel button runs the cancel handler; the confirm button is locked while the action is running.
 */
final class ConfirmMenu extends Menu {
    private final Component title;
    private final ItemStack summary;
    private final boolean dangerous;
    private final Consumer<ConfirmMenu> onConfirm;
    private final Runnable onCancel;
    private boolean working;

    private ConfirmMenu(MenuManager mgr, Player viewer, Component title, ItemStack summary, boolean dangerous,
                        Consumer<ConfirmMenu> onConfirm, Runnable onCancel) {
        super(mgr, viewer, 3, null);
        this.title = title;
        this.summary = summary;
        this.dangerous = dangerous;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /** Confirmation of a service action; {@code after} runs with the result while this menu is still open. */
    static <T> ConfirmMenu of(MenuManager mgr, Player viewer, Component title, ItemStack summary, boolean dangerous,
                              Supplier<CompletableFuture<Result<T>>> call, Consumer<Result<T>> after, Runnable onCancel) {
        return new ConfirmMenu(mgr, viewer, title, summary, dangerous, m -> {
            if (mgr.isLocked(viewer.getUniqueId())) return; // another action is still running; keep the buttons usable
            m.working = true;
            m.refresh();
            m.perform(viewer, call, r -> {
                m.working = false;
                after.accept(r);
            });
        }, onCancel);
    }

    /** Confirmation of a fire-and-forget operation (admin tools); {@code after} runs right after it was started. */
    static ConfirmMenu ofRun(MenuManager mgr, Player viewer, Component title, ItemStack summary, boolean dangerous,
                             Runnable run, Runnable after, Runnable onCancel) {
        return new ConfirmMenu(mgr, viewer, title, summary, dangerous, m -> {
            m.working = true;
            run.run();
            Sync.run(after);
        }, onCancel);
    }

    @Override
    protected Component title() { return title; }

    @Override
    protected void render() {
        set(13, summary);
        if (working) {
            set(11, ui.item("working", Material.GRAY_CONCRETE, "gui.confirm.working"));
            set(15, ui.item("working", Material.GRAY_CONCRETE, "gui.confirm.working"));
            return;
        }
        set(11, ui.item("cancel", Material.RED_CONCRETE, "gui.confirm.cancel"), c -> onCancel.run());
        set(15, ui.item("confirm", Material.LIME_CONCRETE, dangerous ? "gui.confirm.confirm-danger" : "gui.confirm.confirm"), c -> {
            if (working) return;
            if (dangerous && !(c.shift() && c.left())) {
                plugin.messages().send(c.player(), "gui.confirm.need-shift");
                return;
            }
            onConfirm.accept(this);
        });
        set(22, ui.item("close", Material.BARRIER, "gui.confirm.cancel"), c -> onCancel.run());
    }
}
