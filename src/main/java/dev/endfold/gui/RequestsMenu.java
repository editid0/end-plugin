package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.Role;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Pending join requests of an approval-required expedition; approve or deny (ADMIN and above). */
final class RequestsMenu extends PaginatedMenu<MembershipRequest> {
    private final UUID spaceId;
    private List<MembershipRequest> data;

    RequestsMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, back, "gui.requests.empty", 0);
        this.spaceId = spaceId;
        this.loading = true;
    }

    @Override
    protected Component title() {
        return msg().get("gui.requests.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected boolean allowed(Player p) { return roleOf(spaceId).filter(r -> r.atLeast(Role.ADMIN)).isPresent(); }

    @Override
    protected void onOpened() { load(plugin.services().members.requestsForSpace(spaceId), l -> data = l); }

    @Override
    protected void reload() { onOpened(); }

    @Override
    protected List<MembershipRequest> entries() { return data == null ? List.of() : data; }

    @Override
    protected ItemStack icon(MembershipRequest r) {
        return ui.builder("player", Material.PLAYER_HEAD, "gui.requests.entry", Messages.arg("player", plugin.actions().nameOf(r.playerUuid())),
                Messages.arg("created", ui.date(r.createdAt())),
                Messages.arg("ago", ui.duration(System.currentTimeMillis() - r.createdAt()))).head(r.playerUuid()).build();
    }

    @Override
    protected void clicked(MembershipRequest r, Click c) {
        Player p = c.player();
        if (c.left()) perform(p, () -> plugin.actions().approve(p, r), res -> new RequestsMenu(mgr, p, spaceId, back).open());
        else if (c.right()) perform(p, () -> plugin.actions().deny(p, r), res -> new RequestsMenu(mgr, p, spaceId, back).open());
    }

    @Override
    protected void toolbar() {
        set(4, ui.item("requests", Material.PAPER, "gui.requests.header", Messages.arg("count", entries().size())));
    }
}
