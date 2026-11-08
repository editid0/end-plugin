package dev.endfold.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Small fluent builder for menu icons. */
final class Items {
    private Items() {}

    static Builder of(Material material) { return new Builder(material); }

    static final class Builder {
        private final Material material;
        private Component name;
        private final List<Component> lore = new ArrayList<>();
        private int amount = 1;
        private boolean glow;
        private boolean hideTooltip;
        private UUID head;

        private Builder(Material material) { this.material = material; }

        Builder name(Component c) {
            this.name = c;
            return this;
        }

        Builder lore(List<Component> lines) {
            lore.addAll(lines);
            return this;
        }

        Builder line(Component line) {
            lore.add(line);
            return this;
        }

        Builder amount(int n) {
            this.amount = Math.max(1, Math.min(64, n));
            return this;
        }

        Builder glow(boolean g) {
            this.glow = g;
            return this;
        }

        Builder hideTooltip() {
            this.hideTooltip = true;
            return this;
        }

        Builder head(UUID owner) {
            this.head = owner;
            return this;
        }

        ItemStack build() {
            Material m = head != null ? Material.PLAYER_HEAD : material;
            ItemStack stack = new ItemStack(m, amount);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            if (hideTooltip) {
                meta.setHideTooltip(true);
            } else {
                if (name != null) meta.displayName(name);
                if (!lore.isEmpty()) meta.lore(List.copyOf(lore));
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            }
            if (glow) meta.setEnchantmentGlintOverride(true);
            if (head != null && meta instanceof SkullMeta skull) skull.setOwningPlayer(Bukkit.getOfflinePlayer(head));
            stack.setItemMeta(meta);
            return stack;
        }
    }
}
