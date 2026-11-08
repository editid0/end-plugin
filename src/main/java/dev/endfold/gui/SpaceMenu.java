package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Detail / management menu of one EndSpace the viewer belongs to. */
final class SpaceMenu extends Menu {
    private final UUID spaceId;
    private int requests = -1;

    SpaceMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, 5, back);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.space.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected void onOpened() { reload(); }

    @Override
    protected void reload() {
        Optional<EndSpace> s = space(spaceId);
        Optional<Role> r = roleOf(spaceId);
        if (s.isPresent() && r.isPresent() && r.get().atLeast(Role.ADMIN) && s.get().isPublic() && s.get().joinMode() == JoinMode.APPROVAL) {
            async(plugin.services().members.requestsForSpace(spaceId), list -> requests = list.size());
        }
        refresh();
    }

    @Override
    protected void render() {
        Player p = viewer();
        Optional<EndSpace> opt = space(spaceId);
        Optional<Role> ro = roleOf(spaceId);
        if (p == null || opt.isEmpty() || ro.isEmpty()) {
            showGone();
            return;
        }
        EndSpace s = opt.get();
        Role role = ro.get();
        var dir = plugin.services().dir();
        boolean selected = spaceId.equals(dir.selectedId(viewerId).orElse(null));
        boolean canEnter = dir.canEnter(spaceId, viewerId);
        List<dev.endfold.model.Membership> members = dir.members(spaceId);
        long online = members.stream().filter(m -> Bukkit.getPlayer(m.playerUuid()) != null).count();
        boolean inside = plugin.instances().currentSpace(p).map(x -> x.id().equals(spaceId)).orElse(false);
        boolean admin = role.atLeast(Role.ADMIN);

        set(4, ui.builder(s.type() == SpaceType.SOLO ? "space-solo" : "space-group",
                s.type() == SpaceType.SOLO ? Material.ENDER_EYE : Material.ENDER_CHEST, "gui.space.info",
                Messages.arg("space", s.name()), Messages.comp("type", ui.type(s)), Messages.comp("state", ui.loaded(s)),
                Messages.arg("owner", ui.ownerName(s)), Messages.arg("members", members.size()), Messages.arg("max", s.maxMembers()),
                Messages.comp("role", ui.role(role)), Messages.comp("status", ui.status(s.status())),
                Messages.comp("visibility", msg().get(s.isPublic() ? "gui.state.public" : "gui.state.private")),
                Messages.comp("selected", ui.selected(selected)), Messages.arg("created", ui.date(s.createdAt()))).glow(selected).build());

        if (selected) {
            set(10, ui.builder("selected", Material.END_PORTAL_FRAME, "gui.space.select-done").glow(true).build(),
                    c -> msg().send(c.player(), "gui.spaces.already-selected"));
        } else {
            set(10, ui.item("select", Material.ENDER_EYE, "gui.space.select"), c -> {
                if (!canSelect()) return;
                perform(c.player(), () -> plugin.actions().select(c.player(), s), r -> refresh());
            });
        }

        if (canEnter) {
            set(12, ui.item("enter", Material.ENDER_PEARL, "gui.space.enter", Messages.arg("space", s.name())), c -> {
                if (!plugin.services().dir().canEnter(spaceId, viewerId)) {
                    refresh();
                    return;
                }
                c.player().closeInventory();
                plugin.actions().enter(c.player(), s);
            });
        } else {
            set(12, ui.item("disabled", Material.BARRIER, "gui.space.enter-no"));
        }

        if (inside) {
            set(14, ui.item("exit", Material.OAK_DOOR, "gui.space.return"), c -> {
                c.player().closeInventory();
                plugin.actions().exit(c.player());
            });
        }

        set(16, ui.item("members", Material.PLAYER_HEAD, "gui.space.members", Messages.arg("count", members.size()), Messages.arg("online", online)),
                c -> new MembersMenu(mgr, c.player(), spaceId, () -> new SpaceMenu(mgr, c.player(), spaceId, back).open(), 0).open());

        if (canInvite(s, role)) {
            set(20, ui.item("invite", Material.WRITABLE_BOOK, "gui.space.invite"),
                    c -> mgr.invitePicker(c.player(), spaceId, () -> new SpaceMenu(mgr, c.player(), spaceId, back).open()).open());
        }

        set(22, ui.item("progress", Material.DRAGON_HEAD, "gui.space.progress"),
                c -> new ProgressMenu(mgr, c.player(), spaceId, () -> new SpaceMenu(mgr, c.player(), spaceId, back).open()).open());

        if (admin) {
            set(24, ui.item("settings", Material.COMPARATOR, "gui.space.settings"),
                    c -> new SettingsMenu(mgr, c.player(), spaceId, () -> new SpaceMenu(mgr, c.player(), spaceId, back).open()).open());
        }

        if (admin && s.isPublic() && s.joinMode() == JoinMode.APPROVAL) {
            set(29, ui.builder("requests", Material.PAPER, "gui.space.requests",
                            Messages.comp("count", requests < 0 ? msg().get("gui.state.progress-loading") : Component.text(requests)))
                    .amount(Math.max(1, requests)).glow(requests > 0).build(),
                    c -> new RequestsMenu(mgr, c.player(), spaceId, () -> new SpaceMenu(mgr, c.player(), spaceId, back).open()).open());
        }

        if (admin && s.type() == SpaceType.GROUP) {
            set(31, ui.item("space-invites", Material.MAP, "gui.space.invites"),
                    c -> new InvitationsMenu(mgr, c.player(), () -> new SpaceMenu(mgr, c.player(), spaceId, back).open(), true, spaceId, 0).open());
        }

        navigation();

        if (s.type() == SpaceType.GROUP && s.status() == SpaceStatus.ACTIVE) {
            set(38, ui.item("leave", Material.RED_DYE, role == Role.OWNER ? "gui.space.leave-owner" : "gui.space.leave"), c -> confirmLeave(c.player(), s));
        }
        if (role == Role.OWNER && s.status() == SpaceStatus.ACTIVE) {
            set(42, ui.item("disband", Material.TNT, "gui.space.disband"), c -> confirmDisband(c.player(), s));
        }
    }

    private boolean canSelect() {
        return space(spaceId).filter(s -> s.status() == SpaceStatus.ACTIVE).isPresent() && roleOf(spaceId).isPresent();
    }

    private void reopen(Player p) { new SpaceMenu(mgr, p, spaceId, back).open(); }

    private void confirmLeave(Player p, EndSpace s) {
        if (roleOf(spaceId).isEmpty()) {
            refresh();
            return;
        }
        ConfirmMenu.of(mgr, p, msg().get("gui.confirm.leave.title", Messages.arg("space", s.name())),
                ui.item("leave", Material.RED_DYE, "gui.confirm.leave.summary", Messages.arg("space", s.name())), false,
                () -> plugin.actions().leave(p, s),
                r -> {
                    if (r.isOk()) mgr.openSpaces(p);
                    else reopen(p);
                }, () -> reopen(p)).open();
    }

    private void confirmDisband(Player p, EndSpace s) {
        if (roleOf(spaceId).filter(r -> r == Role.OWNER).isEmpty()) {
            refresh();
            return;
        }
        ConfirmMenu.of(mgr, p, msg().get("gui.confirm.disband.title", Messages.arg("space", s.name())),
                ui.item("disband", Material.TNT, "gui.confirm.disband.summary", Messages.arg("space", s.name())), true,
                () -> plugin.actions().disband(p, s),
                r -> {
                    if (r.isOk()) mgr.openSpaces(p);
                    else reopen(p);
                }, () -> reopen(p)).open();
    }
}
