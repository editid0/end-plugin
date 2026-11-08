package dev.endfold.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** A validated click on a menu button. */
record Click(Player player, ClickType type, int slot) {
    boolean left() { return type == ClickType.LEFT || type == ClickType.SHIFT_LEFT; }

    boolean right() { return type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT; }

    boolean shift() { return type.isShiftClick(); }
}
