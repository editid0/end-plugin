package dev.endfold.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Explains the difference between Select, Enter, Return and Leave. */
final class HelpMenu extends Menu {
    HelpMenu(MenuManager mgr, Player viewer, Runnable back) { super(mgr, viewer, 3, back); }

    @Override
    protected Component title() { return msg().get("gui.help.title"); }

    @Override
    protected void render() {
        navigation();
        set(10, ui.item("select", Material.ENDER_EYE, "gui.help.select"));
        set(12, ui.item("enter", Material.ENDER_PEARL, "gui.help.enter"));
        set(14, ui.item("exit", Material.OAK_DOOR, "gui.help.return"));
        set(16, ui.item("leave", Material.RED_DYE, "gui.help.leave"));
    }
}
