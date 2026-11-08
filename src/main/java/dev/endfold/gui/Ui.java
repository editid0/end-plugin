package dev.endfold.gui;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceProgress;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Configuration-backed helpers shared by all menus: icons, texts and formatting. Contains no hardcoded user text. */
final class Ui {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final EndfoldPlugin plugin;

    Ui(EndfoldPlugin plugin) { this.plugin = plugin; }

    Messages m() { return plugin.messages(); }

    Material material(String icon, Material fallback) {
        String key = "gui.icons." + icon;
        if (!m().has(key)) return fallback;
        Material mat = Material.matchMaterial(m().raw(key));
        return mat == null || !mat.isItem() || mat.isAir() ? fallback : mat;
    }

    /** Builder with icon material from {@code gui.icons.<icon>}, name {@code <base>.name} and lore {@code <base>.lore}. */
    Items.Builder builder(String icon, Material fallback, String base, TagResolver... r) {
        Items.Builder b = Items.of(material(icon, fallback)).name(m().item(base + ".name", r));
        if (m().has(base + ".lore")) b.lore(m().lore(base + ".lore", r));
        return b;
    }

    ItemStack item(String icon, Material fallback, String base, TagResolver... r) { return builder(icon, fallback, base, r).build(); }

    ItemStack filler() { return Items.of(material("filler", Material.BLACK_STAINED_GLASS_PANE)).hideTooltip().build(); }

    Component text(String key, TagResolver... r) { return m().get(key, r); }

    // ------------------------------------------------------------------ formatting

    String date(long millis) { return millis <= 0 ? m().raw("gui.fmt.never") : DATE.format(java.time.Instant.ofEpochMilli(millis)); }

    String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long mi = (s % 3600) / 60;
        String ud = m().raw("gui.fmt.unit-day");
        String uh = m().raw("gui.fmt.unit-hour");
        String um = m().raw("gui.fmt.unit-minute");
        if (d > 0) return d + ud + " " + h + uh;
        if (h > 0) return h + uh + " " + mi + um;
        return Math.max(1, mi) + um;
    }

    // ------------------------------------------------------------------ domain texts

    Component type(EndSpace s) { return m().get(s.type() == SpaceType.SOLO ? "common.solo" : "common.group"); }

    Component role(Role r) { return m().get("common.role-" + r.name().toLowerCase(Locale.ROOT)); }

    Component status(SpaceStatus s) { return m().get("common.status-" + s.name().toLowerCase(Locale.ROOT)); }

    Component join(JoinMode j) { return m().get(j == JoinMode.OPEN ? "common.join-open" : "common.join-approval"); }

    Component yesNo(boolean b) { return m().get(b ? "common.yes" : "common.no"); }

    Component loaded(EndSpace s) {
        if (plugin.lifecycle().isLoading(s.id())) return m().get("gui.state.loading-world");
        return m().get(plugin.instances().isLoaded(s) ? "common.loaded" : "common.unloaded");
    }

    /** Dragon progression text; {@code null} progress means "still loading". */
    Component dragon(SpaceProgress p) {
        if (p == null) return m().get("gui.state.progress-loading");
        if (p.dragonDefeated()) return m().get("gui.state.dragon-defeated", Messages.arg("kills", p.dragonKills()));
        return m().get("gui.state.dragon-alive");
    }

    Component selected(boolean sel) { return m().get(sel ? "gui.state.selected" : "gui.state.not-selected"); }

    Component spaceName(EndSpace s) { return Component.text(s.name()); }

    String ownerName(EndSpace s) { return plugin.actions().nameOf(s.ownerUuid()); }
}
