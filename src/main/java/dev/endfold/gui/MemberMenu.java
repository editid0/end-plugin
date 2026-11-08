package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Actions on one member: promote, demote, remove, transfer ownership (or leave when it is the viewer). */
final class MemberMenu extends Menu {
    private final UUID spaceId;
    private final UUID target;

    /** @param back returns to the (re-created) members list */
    MemberMenu(MenuManager mgr, Player viewer, UUID spaceId, UUID target, Runnable back) {
        super(mgr, viewer, 3, back);
        this.spaceId = spaceId;
        this.target = target;
    }

    @Override
    protected Component title() { return msg().get("gui.member.title", Messages.arg("player", plugin.actions().nameOf(target))); }

    private Optional<Role> targetRole() { return plugin.services().dir().role(spaceId, target); }

    private boolean self() { return target.equals(viewerId); }

    @Override
    protected void render() {
        Optional<EndSpace> s = space(spaceId);
        Optional<Role> mine = roleOf(spaceId);
        Optional<Role> theirs = targetRole();
        if (s.isEmpty() || mine.isEmpty() || theirs.isEmpty() || s.get().status() != SpaceStatus.ACTIVE) {
            showGone();
            return;
        }
        navigation();
        String name = plugin.actions().nameOf(target);
        set(4, ui.builder("player", Material.PLAYER_HEAD, "gui.member.info", Messages.arg("player", name), Messages.comp("role", ui.role(theirs.get())))
                .head(target).build());
        boolean group = s.get().type() == SpaceType.GROUP;
        if (self()) {
            if (group) set(13, ui.item("leave", Material.RED_DYE, "gui.space.leave"), c -> confirmLeave(c.player(), s.get()));
            return;
        }
        Role me = mine.get();
        Role them = theirs.get();
        if (me == Role.OWNER && them == Role.MEMBER) {
            set(10, ui.item("promote", Material.LIME_DYE, "gui.member.promote", Messages.arg("player", name)), c -> setRole(c.player(), Role.ADMIN));
        }
        if (me == Role.OWNER && them == Role.ADMIN) {
            set(12, ui.item("demote", Material.ORANGE_DYE, "gui.member.demote", Messages.arg("player", name)), c -> setRole(c.player(), Role.MEMBER));
        }
        if (me.atLeast(Role.ADMIN) && me.outranks(them)) {
            set(14, ui.item("kick", Material.IRON_BOOTS, "gui.member.kick", Messages.arg("player", name)), c -> confirmKick(c.player(), s.get()));
        }
        if (me == Role.OWNER && group) {
            set(16, ui.item("transfer", Material.GOLDEN_HELMET, "gui.member.transfer", Messages.arg("player", name)), c -> confirmTransfer(c.player(), s.get()));
        }
    }

    private boolean stillValid(Role needed) {
        return space(spaceId).isPresent() && roleOf(spaceId).filter(r -> r == needed).isPresent() && targetRole().isPresent();
    }

    private void setRole(Player p, Role role) {
        if (!stillValid(Role.OWNER)) {
            refresh();
            return;
        }
        EndSpace s = space(spaceId).orElseThrow();
        perform(p, () -> plugin.actions().setRole(p, s, target, role), r -> back.run());
    }

    private void confirmKick(Player p, EndSpace s) {
        Optional<Role> mine = roleOf(spaceId);
        Optional<Role> theirs = targetRole();
        if (mine.isEmpty() || theirs.isEmpty() || !mine.get().atLeast(Role.ADMIN) || !mine.get().outranks(theirs.get())) {
            refresh();
            return;
        }
        String name = plugin.actions().nameOf(target);
        ConfirmMenu.of(mgr, p, msg().get("gui.confirm.kick.title", Messages.arg("player", name)),
                ui.builder("kick", Material.IRON_BOOTS, "gui.confirm.kick.summary", Messages.arg("player", name), Messages.arg("space", s.name()))
                        .build(), false,
                () -> plugin.actions().kick(p, s, target), r -> back.run(), () -> reopen(p)).open();
    }

    private void confirmTransfer(Player p, EndSpace s) {
        if (!stillValid(Role.OWNER)) {
            refresh();
            return;
        }
        String name = plugin.actions().nameOf(target);
        ConfirmMenu.of(mgr, p, msg().get("gui.confirm.transfer.title", Messages.arg("player", name)),
                ui.item("transfer", Material.GOLDEN_HELMET, "gui.confirm.transfer.summary", Messages.arg("player", name), Messages.arg("space", s.name())), true,
                () -> plugin.actions().transfer(p, s, target), r -> back.run(), () -> reopen(p)).open();
    }

    private void confirmLeave(Player p, EndSpace s) {
        ConfirmMenu.of(mgr, p, msg().get("gui.confirm.leave.title", Messages.arg("space", s.name())),
                ui.item("leave", Material.RED_DYE, "gui.confirm.leave.summary", Messages.arg("space", s.name())), false,
                () -> plugin.actions().leave(p, s),
                r -> {
                    if (r.isOk()) mgr.openSpaces(p);
                    else reopen(p);
                }, () -> reopen(p)).open();
    }

    private void reopen(Player p) { new MemberMenu(mgr, p, spaceId, target, back).open(); }
}
