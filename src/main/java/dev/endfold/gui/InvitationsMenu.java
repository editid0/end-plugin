package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Invitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Invitations of the viewer (incoming / outgoing tabs). With a space filter it lists the pending invitations of that
 * one space instead (for administrators of the space).
 */
final class InvitationsMenu extends PaginatedMenu<Invitation> {
    private static final long DAY = 86_400_000L;
    private final boolean outgoingTab;
    private final UUID spaceFilter;
    private List<Invitation> in;
    private List<Invitation> out;

    InvitationsMenu(MenuManager mgr, Player viewer, Runnable back, boolean outgoingTab, UUID spaceFilter, int page) {
        super(mgr, viewer, back, "gui.invitations.empty", page);
        this.outgoingTab = outgoingTab || spaceFilter != null;
        this.spaceFilter = spaceFilter;
        this.loading = true;
    }

    @Override
    protected Component title() { return msg().get(spaceFilter == null ? "gui.invitations.title" : "gui.invitations.title-space"); }

    @Override
    protected void onOpened() {
        if (spaceFilter != null) {
            load(plugin.services().members.spaceInvites(spaceFilter), l -> out = l);
        } else if (outgoingTab) {
            load(plugin.services().members.outgoing(viewerId), l -> out = l);
            async(plugin.services().members.incoming(viewerId), l -> in = l);
        } else {
            load(plugin.services().members.incoming(viewerId), l -> in = l);
            async(plugin.services().members.outgoing(viewerId), l -> out = l);
        }
    }

    @Override
    protected void reload() { onOpened(); }

    private void reopen(Player p) { new InvitationsMenu(mgr, p, back, outgoingTab, spaceFilter, 0).open(); }

    @Override
    protected List<Invitation> entries() {
        List<Invitation> l = outgoingTab ? out : in;
        return l == null ? List.of() : l;
    }

    @Override
    protected ItemStack icon(Invitation inv) {
        long now = System.currentTimeMillis();
        Optional<EndSpace> sp = space(inv.spaceId());
        String spaceName = sp.map(EndSpace::name).orElse("?");
        String owner = sp.map(ui::ownerName).orElse("?");
        Component type = sp.map(ui::type).orElse(Component.text("?"));
        int members = plugin.services().dir().memberCount(inv.spaceId());
        boolean soon = inv.expiresAt() - now < DAY;
        String base = outgoingTab ? "gui.invitations.outgoing" : "gui.invitations.incoming";
        return ui.builder(outgoingTab ? "invite-out" : "invite-in", outgoingTab ? Material.MAP : Material.PAPER, base,
                Messages.arg("space", spaceName), Messages.arg("inviter", plugin.actions().nameOf(inv.inviterUuid())),
                Messages.arg("invitee", plugin.actions().nameOf(inv.inviteeUuid())), Messages.arg("owner", owner), Messages.comp("type", type),
                Messages.arg("members", members), Messages.arg("max", sp.map(EndSpace::maxMembers).orElse(0)),
                Messages.arg("created", ui.date(inv.createdAt())), Messages.arg("expires", ui.duration(inv.expiresAt() - now)),
                Messages.comp("soon", soon ? msg().get("gui.invitations.expiring") : Component.empty())).glow(soon && !outgoingTab).build();
    }

    @Override
    protected void clicked(Invitation inv, Click c) {
        Player p = c.player();
        if (outgoingTab) {
            if (c.right()) perform(p, () -> plugin.actions().revoke(p, inv), r -> reopen(p));
            return;
        }
        if (c.left()) perform(p, () -> plugin.actions().accept(p, inv), r -> {
            if (r.isOk()) new SpaceMenu(mgr, p, r.value().spaceId(), () -> mgr.openSpaces(p)).open();
            else reopen(p);
        });
        else if (c.right()) perform(p, () -> plugin.actions().decline(p, inv), r -> reopen(p));
    }

    @Override
    protected void toolbar() {
        if (spaceFilter != null) {
            set(4, ui.item("invitations", Material.WRITABLE_BOOK, "gui.invitations.header-space",
                    Messages.arg("space", space(spaceFilter).map(EndSpace::name).orElse("?"))));
            return;
        }
        set(2, ui.builder("invite-in", Material.PAPER, outgoingTab ? "gui.invitations.tab-incoming" : "gui.invitations.tab-incoming-active",
                Messages.comp("count", count(in))).glow(!outgoingTab).build(),
                c -> new InvitationsMenu(mgr, c.player(), back, false, null, 0).open());
        set(6, ui.builder("invite-out", Material.MAP, outgoingTab ? "gui.invitations.tab-outgoing-active" : "gui.invitations.tab-outgoing",
                Messages.comp("count", count(out))).glow(outgoingTab).build(),
                c -> new InvitationsMenu(mgr, c.player(), back, true, null, 0).open());
    }

    private Component count(List<Invitation> l) { return l == null ? msg().get("gui.state.progress-loading") : Component.text(l.size()); }
}
