package dev.endfold.gui;

import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/** An icon plus an optional click handler. Decorative icons have a {@code null} action. */
record Button(ItemStack item, Consumer<Click> action) {}
