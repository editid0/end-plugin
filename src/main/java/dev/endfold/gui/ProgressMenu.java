package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.PlayerProgress;
import dev.endfold.model.SpaceProgress;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Shared (space) progression next to the viewer's individual contribution in that space. */
final class ProgressMenu extends Menu {
    private final UUID spaceId;
    private SpaceProgress shared;
    private PlayerProgress personal;

    ProgressMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, 3, back);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.progress.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected void onOpened() {
        load(plugin.services().progress.space(spaceId), p -> shared = p);
        async(plugin.services().progress.player(spaceId, viewerId), p -> personal = p);
    }

    @Override
    protected void render() {
        if (space(spaceId).isEmpty() || roleOf(spaceId).isEmpty()) {
            showGone();
            return;
        }
        navigation();
        if (loading) {
            showLoading();
            return;
        }
        if (failed || shared == null) {
            showFailed();
            return;
        }
        set(11, ui.item("dragon", Material.DRAGON_HEAD, "gui.progress.shared",
                Messages.comp("dragon", ui.dragon(shared)), Messages.arg("kills", shared.dragonKills()),
                Messages.arg("gateways", shared.gatewaysActivated()), Messages.arg("first", ui.date(shared.firstKillAt())),
                Messages.arg("last", ui.date(shared.lastKillAt()))));
        PlayerProgress pp = personal == null ? PlayerProgress.empty(spaceId, viewerId) : personal;
        set(15, ui.item("player", Material.PLAYER_HEAD, "gui.progress.personal",
                Messages.arg("participations", pp.dragonParticipations()), Messages.arg("kills", pp.dragonKills()),
                Messages.arg("damage", Math.round(pp.damageDealt())), Messages.arg("chests", pp.chestsLooted()),
                Messages.arg("elytra", pp.elytraClaimed()), Messages.arg("shulkers", pp.shulkersKilled())));
    }
}
