package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** "My End Worlds": every EndSpace the player belongs to, with select / enter / manage shortcuts. */
final class SpacesMenu extends PaginatedMenu<EndSpace> {
    private final ProgressLoader progress = new ProgressLoader(this);

    SpacesMenu(MenuManager mgr, Player viewer, Runnable back, int page) { super(mgr, viewer, back, "gui.spaces.empty", page); }

    @Override
    protected Component title() { return msg().get("gui.spaces.title"); }

    @Override
    protected void onOpened() { progress.ensure(visible().stream().map(EndSpace::id).toList()); }

    @Override
    protected void pageChanged(List<EndSpace> visible) { progress.ensure(visible.stream().map(EndSpace::id).toList()); }

    @Override
    protected List<EndSpace> entries() {
        UUID selected = plugin.services().dir().selectedId(viewerId).orElse(null);
        return plugin.services().dir().spacesOf(viewerId).stream().filter(s -> s.status().isLive())
                .sorted(Comparator.comparing((EndSpace s) -> !s.id().equals(selected))
                        .thenComparing(s -> s.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Override
    protected ItemStack icon(EndSpace s) {
        var dir = plugin.services().dir();
        boolean sel = s.id().equals(dir.selectedId(viewerId).orElse(null));
        Role role = dir.role(s.id(), viewerId).orElse(Role.MEMBER);
        boolean canEnter = dir.canEnter(s.id(), viewerId);
        String base = sel ? "gui.spaces.entry-selected" : "gui.spaces.entry";
        return ui.builder(s.type() == SpaceType.SOLO ? "space-solo" : "space-group",
                        s.type() == SpaceType.SOLO ? Material.ENDER_EYE : Material.ENDER_CHEST, base,
                        Messages.arg("space", s.name()), Messages.comp("type", ui.type(s)), Messages.comp("role", ui.role(role)),
                        Messages.arg("owner", ui.ownerName(s)), Messages.arg("members", dir.memberCount(s.id())),
                        Messages.arg("max", s.maxMembers()), Messages.comp("state", ui.loaded(s)),
                        Messages.comp("dragon", ui.dragon(progress.get(s.id()))), Messages.comp("status", ui.status(s.status())),
                        Messages.comp("enter", msg().get(canEnter ? "gui.state.can-enter" : "gui.state.cannot-enter")))
                .glow(sel).build();
    }

    @Override
    protected void clicked(EndSpace s, Click c) {
        Player p = c.player();
        if (c.shift()) {
            if (!plugin.services().dir().canEnter(s.id(), viewerId)) {
                msg().send(p, "gui.spaces.cannot-enter");
                return;
            }
            if (plugin.router().directAccess(p) != dev.endfold.portal.PortalRouter.DirectAccess.ALLOWED) {
                plugin.router().enter(p, s, false); // sends the explanation, does not teleport
                return;
            }
            p.closeInventory();
            plugin.actions().enter(p, s);
        } else if (c.right()) {
            if (s.id().equals(plugin.services().dir().selectedId(viewerId).orElse(null))) {
                msg().send(p, "gui.spaces.already-selected");
                return;
            }
            perform(p, () -> plugin.actions().select(p, s), r -> refresh());
        } else {
            int pg = page;
            new SpaceMenu(mgr, p, s.id(), () -> new SpacesMenu(mgr, p, back, pg).open()).open();
        }
    }

    @Override
    protected void toolbar() {
        set(4, ui.item("spaces", Material.END_PORTAL_FRAME, "gui.spaces.header"));
        set(52, ui.item("create", Material.END_CRYSTAL, "gui.spaces.create"), c -> new CreateMenu(mgr, c.player(), () -> mgr.openSpaces(c.player())).open());
    }

    @Override
    protected void renderEmpty() {
        super.renderEmpty();
        set(centerSlot() + 9, ui.item("create", Material.END_CRYSTAL, "gui.spaces.create"), c -> new CreateMenu(mgr, c.player(), () -> mgr.openSpaces(c.player())).open());
    }
}
