package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Create wizard, step 2: review the group name and confirm. */
final class CreateReviewMenu extends Menu {
    private final String name;
    private final Runnable root;

    CreateReviewMenu(MenuManager mgr, Player viewer, String name, Runnable root) {
        super(mgr, viewer, 3, () -> new CreateMenu(mgr, viewer, root).open());
        this.name = name;
        this.root = root;
    }

    @Override
    protected Component title() { return msg().get("gui.create.review-title"); }

    @Override
    protected void render() {
        navigation();
        set(4, ui.item("space-group", Material.ENDER_CHEST, "gui.create.summary", Messages.arg("space", name)));
        set(11, ui.item("confirm", Material.LIME_CONCRETE, "gui.create.confirm"), c -> create(c.player()));
        set(13, ui.item("rename", Material.NAME_TAG, "gui.create.change-name"), c -> new CreateMenu(mgr, c.player(), root).askName(c.player(), name));
        set(15, ui.item("cancel", Material.RED_CONCRETE, "gui.create.cancel"), c -> back.run());
    }

    private void create(Player p) {
        perform(p, () -> plugin.actions().createGroup(p, name), r -> {
            if (r.isOk()) {
                EndSpace s = r.value();
                new SpaceMenu(mgr, p, s.id(), () -> mgr.openSpaces(p)).open();
            } else {
                refresh();
            }
        });
    }
}
