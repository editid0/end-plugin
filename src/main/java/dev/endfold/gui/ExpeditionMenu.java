package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.SpaceProgress;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Detail of a public expedition with the join / request / cancel-request action. */
final class ExpeditionMenu extends Menu {
    private final UUID spaceId;
    private MembershipRequest request;
    private SpaceProgress progress;
    private boolean requestsLoaded;

    ExpeditionMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, 5, back);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.expedition.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected void onOpened() { reload(); }

    @Override
    protected void reload() {
        requestsLoaded = false;
        request = null;
        async(plugin.services().members.requestsOf(viewerId), list -> {
            request = list.stream().filter(r -> r.spaceId().equals(spaceId)).findFirst().orElse(null);
            requestsLoaded = true;
        });
        async(plugin.services().progress.space(spaceId), p -> progress = p);
        refresh();
    }

    @Override
    protected void render() {
        Optional<EndSpace> opt = space(spaceId);
        var dir = plugin.services().dir();
        boolean member = dir.isMember(spaceId, viewerId);
        if (opt.isEmpty() || opt.get().status() != SpaceStatus.ACTIVE || opt.get().type() != SpaceType.GROUP || (!opt.get().isPublic() && !member)) {
            showGone();
            return;
        }
        EndSpace s = opt.get();
        Player p = viewer();
        navigation();
        int members = dir.memberCount(spaceId);
        String desc = s.description() == null || s.description().isBlank() ? msg().raw("gui.expedition.no-description") : s.description().strip();
        set(4, ui.item("space-group", Material.ENDER_CHEST, "gui.expedition.info", Messages.arg("space", s.name()), Messages.arg("owner", ui.ownerName(s)),
                Messages.arg("members", members), Messages.arg("max", s.maxMembers()), Messages.comp("join", ui.join(s.joinMode())),
                Messages.arg("created", ui.date(s.createdAt())), Messages.arg("desc", desc)));

        if (member) {
            set(11, ui.item("enter", Material.ENDER_PEARL, "gui.expedition.member"), c -> new SpaceMenu(mgr, c.player(), spaceId, back).open());
        } else if (p != null && !p.hasPermission("endfold.join")) {
            set(11, ui.item("disabled", Material.BARRIER, "gui.expedition.no-permission"));
        } else if (!requestsLoaded && s.joinMode() == JoinMode.APPROVAL) {
            showLoadingAt(11);
        } else if (members >= s.maxMembers()) {
            set(11, ui.item("disabled", Material.BARRIER, "gui.expedition.full"));
        } else if (s.joinMode() == JoinMode.OPEN) {
            set(11, ui.item("join", Material.LIME_CONCRETE, "gui.expedition.join", Messages.arg("space", s.name())), c -> join(c.player(), s));
        } else if (request != null) {
            MembershipRequest req = request;
            set(11, ui.item("pending", Material.CLOCK, "gui.expedition.cancel-request", Messages.arg("space", s.name())), c -> cancel(c.player(), req));
        } else {
            set(11, ui.item("request", Material.YELLOW_CONCRETE, "gui.expedition.request", Messages.arg("space", s.name())), c -> requestJoin(c.player(), s));
        }

        set(13, ui.builder("player", Material.PLAYER_HEAD, "gui.expedition.owner", Messages.arg("player", ui.ownerName(s))).head(s.ownerUuid()).build());
        set(15, ui.item("dragon", Material.DRAGON_HEAD, "gui.expedition.progress", Messages.comp("dragon", ui.dragon(progress)),
                Messages.arg("gateways", progress == null ? 0 : progress.gatewaysActivated())));
    }

    private void showLoadingAt(int slot) { set(slot, ui.item("loading", Material.CLOCK, "gui.common.loading")); }

    private void join(Player p, EndSpace s) {
        perform(p, () -> plugin.actions().joinPublic(p, s), r -> {
            if (r.isOk()) new SpaceMenu(mgr, p, spaceId, () -> mgr.openSpaces(p)).open();
            else reload();
        });
    }

    private void requestJoin(Player p, EndSpace s) { perform(p, () -> plugin.actions().requestJoin(p, s), r -> reload()); }

    private void cancel(Player p, MembershipRequest req) { perform(p, () -> plugin.actions().cancelRequest(p, req), r -> reload()); }
}
