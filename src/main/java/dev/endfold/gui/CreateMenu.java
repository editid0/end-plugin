package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Create wizard, step 1: choose SOLO (immediate) or GROUP (name, review, confirm). */
final class CreateMenu extends Menu {
    CreateMenu(MenuManager mgr, Player viewer, Runnable back) { super(mgr, viewer, 3, back); }

    @Override
    protected Component title() { return msg().get("gui.create.title"); }

    private int ownedGroups() {
        return (int) plugin.services().dir().spacesOf(viewerId).stream()
                .filter(s -> s.type() == SpaceType.GROUP && s.ownerUuid().equals(viewerId) && s.status().isLive()).count();
    }

    private boolean hasSolo() {
        return plugin.services().dir().spacesOf(viewerId).stream()
                .anyMatch(s -> s.type() == SpaceType.SOLO && s.ownerUuid().equals(viewerId) && s.status().isLive());
    }

    @Override
    protected void render() {
        Player p = viewer();
        if (p == null) return;
        navigation();
        var cfg = plugin.config();
        boolean perm = p.hasPermission("endfold.create");

        if (!perm) {
            set(11, ui.item("disabled", Material.BARRIER, "gui.create.no-permission"));
            set(15, ui.item("disabled", Material.BARRIER, "gui.create.no-permission"));
            return;
        }
        if (!cfg.soloEnabled) {
            set(11, ui.item("disabled", Material.BARRIER, "gui.create.solo-disabled"));
        } else if (hasSolo()) {
            set(11, ui.item("space-solo", Material.ENDER_EYE, "gui.create.solo-exists"), c -> solo(c.player()));
        } else {
            set(11, ui.item("space-solo", Material.ENDER_EYE, "gui.create.solo"), c -> solo(c.player()));
        }

        int owned = ownedGroups();
        if (!cfg.groupEnabled) {
            set(15, ui.item("disabled", Material.BARRIER, "gui.create.group-disabled"));
        } else if (owned >= cfg.maxOwnedGroups) {
            set(15, ui.item("disabled", Material.BARRIER, "gui.create.group-limit", Messages.arg("owned", owned), Messages.arg("max", cfg.maxOwnedGroups)));
        } else {
            set(15, ui.item("space-group", Material.ENDER_CHEST, "gui.create.group", Messages.arg("owned", owned), Messages.arg("max", cfg.maxOwnedGroups)),
                    c -> askName(c.player(), ""));
        }
    }

    private void solo(Player p) {
        perform(p, () -> plugin.actions().solo(p), r -> {
            if (r.isOk()) {
                EndSpace s = r.value();
                new SpaceMenu(mgr, p, s.id(), () -> mgr.openSpaces(p)).open();
            } else {
                refresh();
            }
        });
    }

    void askName(Player p, String initial) {
        mgr.prompts().ask(p, new TextPrompt.Request(msg().get("gui.create.name-title"),
                msg().get("gui.create.name-hint", Messages.arg("min", plugin.config().nameMin), Messages.arg("max", plugin.config().nameMax)),
                initial, plugin.config().nameMax, mgr::validateName,
                text -> new CreateReviewMenu(mgr, p, text, back).open(),
                () -> new CreateMenu(mgr, p, back).open()));
    }
}
