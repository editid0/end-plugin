package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import dev.endfold.util.Sync;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Administrative list of every EndSpace including archived and broken ones (permission endfold.admin.worlds). */
final class AdminMenu extends PaginatedMenu<EndSpace> {
    enum Filter { ALL, ACTIVE, ARCHIVED, BROKEN, LOADED }

    private Filter filter;

    AdminMenu(MenuManager mgr, Player viewer, Runnable back, Filter filter, int page) {
        super(mgr, viewer, back, "gui.admin.empty", page);
        this.filter = filter;
    }

    @Override
    protected Component title() { return msg().get("gui.admin.title"); }

    @Override
    protected boolean allowed(Player p) { return p.hasPermission("endfold.admin.worlds"); }

    @Override
    protected List<EndSpace> entries() {
        return plugin.services().dir().allSpaces().stream()
                .filter(s -> switch (filter) {
                    case ALL -> true;
                    case ACTIVE -> s.status() == SpaceStatus.ACTIVE;
                    case ARCHIVED -> s.status() == SpaceStatus.ARCHIVED;
                    case BROKEN -> s.status() == SpaceStatus.BROKEN;
                    case LOADED -> plugin.instances().isLoaded(s);
                })
                .sorted(Comparator.comparing((EndSpace s) -> s.status().ordinal()).thenComparing(s -> s.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Override
    protected ItemStack icon(EndSpace s) {
        String key = "admin-" + s.status().name().toLowerCase(Locale.ROOT);
        Material fb = switch (s.status()) {
            case ACTIVE -> s.type() == SpaceType.SOLO ? Material.ENDER_EYE : Material.ENDER_CHEST;
            case ARCHIVED -> Material.CHEST;
            case BROKEN -> Material.BARRIER;
            case DELETED -> Material.DIRT;
        };
        return ui.item(key, fb, "gui.admin.entry", Messages.arg("space", s.name()), Messages.arg("id", s.shortId()), Messages.comp("type", ui.type(s)),
                Messages.comp("status", ui.status(s.status())), Messages.arg("owner", ui.ownerName(s)),
                Messages.arg("members", plugin.services().dir().memberCount(s.id())), Messages.arg("max", s.maxMembers()),
                Messages.comp("state", ui.loaded(s)), Messages.arg("online", plugin.instances().playersIn(s).size()),
                Messages.arg("active", ui.date(s.lastActiveAt())));
    }

    @Override
    protected void clicked(EndSpace s, Click c) {
        Player p = c.player();
        Filter f = filter;
        int pg = page;
        new AdminSpaceMenu(mgr, p, s.id(), () -> new AdminMenu(mgr, p, back, f, pg).open()).open();
    }

    @Override
    protected void toolbar() {
        Items.Builder fb = ui.builder("filter", Material.HOPPER, "gui.admin.filter");
        for (Filter f : Filter.values()) {
            fb.line(msg().get(f == filter ? "gui.public.option-active" : "gui.public.option",
                    Messages.comp("name", msg().get("gui.admin.filter-" + f.name().toLowerCase(Locale.ROOT)))));
        }
        set(2, fb.build(), c -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            page = 0;
            refresh();
        });
        set(4, ui.item("admin", Material.COMMAND_BLOCK, "gui.admin.header", Messages.arg("count", entries().size()),
                Messages.arg("total", plugin.services().dir().allSpaces().size())));
        set(52, ui.item("refresh", Material.SUNFLOWER, "gui.admin.refresh"), c -> refresh());
        set(51, ui.item("repair", Material.ANVIL, "gui.admin.repair"), c -> {
            if (!c.player().hasPermission("endfold.admin.worlds")) return;
            plugin.admin().repair(c.player());
            Sync.later(10L, this::refresh);
        });
    }
}
