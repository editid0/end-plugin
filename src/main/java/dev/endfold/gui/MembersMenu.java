package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Membership;
import dev.endfold.model.Role;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Player heads of all members with their roles; click to manage a member you outrank. */
final class MembersMenu extends PaginatedMenu<Membership> {
    private final UUID spaceId;

    MembersMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back, int page) {
        super(mgr, viewer, back, "gui.members.empty", page);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.members.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected List<Membership> entries() {
        return plugin.services().dir().members(spaceId).stream()
                .sorted(Comparator.comparingInt((Membership m) -> -m.role().rank())
                        .thenComparing(m -> plugin.actions().nameOf(m.playerUuid()).toLowerCase(Locale.ROOT)))
                .toList();
    }

    private boolean manageable(Role mine, Membership m) {
        return m.playerUuid().equals(viewerId) || (mine.atLeast(Role.ADMIN) && mine.outranks(m.role()));
    }

    @Override
    protected ItemStack icon(Membership m) {
        Role mine = roleOf(spaceId).orElse(Role.MEMBER);
        boolean online = Bukkit.getPlayer(m.playerUuid()) != null;
        Component hint = msg().get(manageable(mine, m) ? "gui.members.hint-manage" : "gui.members.hint-none");
        return ui.builder("player", Material.PLAYER_HEAD, "gui.members.entry", Messages.arg("player", plugin.actions().nameOf(m.playerUuid())),
                Messages.comp("role", ui.role(m.role())), Messages.comp("online", msg().get(online ? "gui.state.online" : "gui.state.offline")),
                Messages.arg("joined", ui.date(m.joinedAt())), Messages.comp("hint", hint)).head(m.playerUuid()).build();
    }

    @Override
    protected void clicked(Membership m, Click c) {
        Optional<Role> mine = roleOf(spaceId);
        if (mine.isEmpty()) {
            refresh();
            return;
        }
        Optional<Membership> fresh = plugin.services().dir().membership(spaceId, m.playerUuid());
        if (fresh.isEmpty() || !manageable(mine.get(), fresh.get())) {
            refresh();
            return;
        }
        int pg = page;
        new MemberMenu(mgr, c.player(), spaceId, m.playerUuid(), () -> new MembersMenu(mgr, c.player(), spaceId, back, pg).open()).open();
    }

    @Override
    protected void toolbar() {
        Optional<EndSpace> s = space(spaceId);
        Optional<Role> r = roleOf(spaceId);
        if (s.isEmpty() || r.isEmpty()) return;
        set(4, ui.item("members", Material.PLAYER_HEAD, "gui.members.header", Messages.arg("count", plugin.services().dir().memberCount(spaceId)),
                Messages.arg("max", s.get().maxMembers()), Messages.comp("role", ui.role(r.get()))));
        if (canInvite(s.get(), r.get())) {
            set(52, ui.item("invite", Material.WRITABLE_BOOK, "gui.space.invite"),
                    c -> mgr.invitePicker(c.player(), spaceId, () -> new MembersMenu(mgr, c.player(), spaceId, back, 0).open()).open());
        }
    }
}
