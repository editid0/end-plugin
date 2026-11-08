package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Invitation;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Hub menu: every feature is reachable from here. */
final class MainMenu extends Menu {
    private List<Invitation> incoming;
    private int unlocked = -1;

    MainMenu(MenuManager mgr, Player viewer) { super(mgr, viewer, 5, null); }

    @Override
    protected Component title() { return msg().get("gui.main.title"); }

    @Override
    protected void onOpened() {
        async(plugin.services().members.incoming(viewerId), list -> incoming = list);
        async(plugin.services().progress.achievements(viewerId), list -> unlocked = list.size());
    }

    @Override
    protected void render() {
        Player p = viewer();
        if (p == null) return;
        var dir = plugin.services().dir();
        List<EndSpace> mine = dir.spacesOf(viewerId).stream().filter(s -> s.status().isLive()).toList();
        Optional<EndSpace> sel = plugin.actions().selected(p).filter(s -> s.status() == SpaceStatus.ACTIVE);
        Component selText = sel.map(s -> msg().get("gui.main.selected-name", Messages.arg("space", s.name()))).orElse(msg().get("gui.main.selected-none"));
        boolean noSpaces = mine.isEmpty();

        set(4, ui.builder("profile", Material.PLAYER_HEAD, "gui.main.profile", Messages.arg("player", p.getName()),
                Messages.comp("selected", selText), Messages.arg("count", mine.size())).head(viewerId).build());

        set(10, ui.builder("spaces", Material.END_PORTAL_FRAME, "gui.main.spaces", Messages.arg("count", mine.size()), Messages.comp("selected", selText))
                .glow(false).build(), c -> mgr.openSpaces(c.player()));

        set(12, ui.builder("create", Material.END_CRYSTAL, noSpaces ? "gui.main.create-first" : "gui.main.create").glow(noSpaces).build(),
                c -> mgr.openCreate(c.player()));

        long publicCount = dir.allSpaces().stream().filter(s -> s.status() == SpaceStatus.ACTIVE && s.isPublic() && s.type() == SpaceType.GROUP).count();
        set(14, ui.item("public", Material.COMPASS, "gui.main.public", Messages.arg("count", publicCount)), c -> mgr.openPublic(c.player()));

        int in = incoming == null ? 0 : incoming.size();
        set(16, ui.builder("invitations", Material.WRITABLE_BOOK, "gui.main.invitations",
                        Messages.comp("count", incoming == null ? msg().get("gui.state.progress-loading") : Component.text(in)))
                .amount(Math.max(1, in)).glow(in > 0).build(), c -> mgr.openInvitations(c.player()));

        var direct = plugin.router().directAccess(viewer());
        if (sel.isPresent() && dir.canEnter(sel.get().id(), viewerId) && direct != dev.endfold.portal.PortalRouter.DirectAccess.ALLOWED) {
            set(20, ui.item("disabled", Material.BARRIER, direct == dev.endfold.portal.PortalRouter.DirectAccess.DISABLED
                    ? "gui.main.enter-disabled" : "gui.main.enter-locked"));
        } else if (sel.isPresent() && dir.canEnter(sel.get().id(), viewerId)) {
            EndSpace s = sel.get();
            set(20, ui.item("enter", Material.ENDER_PEARL, "gui.main.enter", Messages.arg("space", s.name())), c -> {
                c.player().closeInventory();
                plugin.actions().enterSelected(c.player());
            });
        } else {
            set(20, ui.item("disabled", Material.BARRIER, "gui.main.enter-none"), c -> {
                if (noSpaces) mgr.openCreate(c.player());
                else mgr.openSpaces(c.player());
            });
        }

        set(22, ui.item("achievements", Material.DRAGON_HEAD, "gui.main.achievements",
                Messages.comp("unlocked", unlocked < 0 ? msg().get("gui.state.progress-loading") : Component.text(unlocked)),
                Messages.arg("total", plugin.config().enabledAchievements.size())), c -> mgr.openAchievements(c.player()));

        if (plugin.instances().currentSpace(p).isPresent()) {
            set(24, ui.item("exit", Material.OAK_DOOR, "gui.main.exit"), c -> {
                c.player().closeInventory();
                plugin.actions().exit(c.player());
            });
        }

        set(36, ui.item("help", Material.BOOK, "gui.main.help"), c -> new HelpMenu(mgr, c.player(), () -> mgr.openMain(c.player())).open());
        set(closeSlot(), ui.item("close", Material.BARRIER, "gui.common.close"), c -> c.player().closeInventory());
        if (p.hasPermission("endfold.admin.worlds")) {
            set(44, ui.item("admin", Material.COMMAND_BLOCK, "gui.main.admin"), c -> mgr.openAdmin(c.player()));
        }
    }
}
