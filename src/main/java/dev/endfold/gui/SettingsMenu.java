package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceType;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Rename, visibility, join mode and member limit of a space. */
final class SettingsMenu extends Menu {
    private final UUID spaceId;

    SettingsMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, 5, back);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.settings.title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    private void reopen(Player p) { new SettingsMenu(mgr, p, spaceId, back).open(); }

    @Override
    protected void render() {
        Optional<EndSpace> opt = space(spaceId);
        Optional<Role> ro = roleOf(spaceId);
        if (opt.isEmpty() || ro.isEmpty() || !ro.get().atLeast(Role.ADMIN)) {
            showGone();
            return;
        }
        EndSpace s = opt.get();
        boolean group = s.type() == SpaceType.GROUP;
        boolean owner = ro.get() == Role.OWNER;
        navigation();
        set(4, ui.item("settings", Material.COMPARATOR, "gui.settings.info", Messages.arg("space", s.name()), Messages.comp("type", ui.type(s)),
                Messages.comp("visibility", msg().get(s.isPublic() ? "gui.state.public" : "gui.state.private")),
                Messages.comp("join", ui.join(s.joinMode())), Messages.arg("max", s.maxMembers())));

        set(11, ui.item("rename", Material.NAME_TAG, "gui.settings.rename", Messages.arg("space", s.name())), c -> rename(c.player()));

        if (group) {
            set(13, ui.item(s.isPublic() ? "public-on" : "public-off", s.isPublic() ? Material.LIME_DYE : Material.GRAY_DYE,
                    s.isPublic() ? "gui.settings.visibility-public" : "gui.settings.visibility-private"), c -> toggleVisibility(c.player()));
            if (s.isPublic()) {
                set(15, ui.item(s.joinMode() == JoinMode.OPEN ? "join-open" : "join-approval",
                        s.joinMode() == JoinMode.OPEN ? Material.OAK_DOOR : Material.IRON_DOOR,
                        s.joinMode() == JoinMode.OPEN ? "gui.settings.join-open" : "gui.settings.join-approval"), c -> toggleJoin(c.player()));
            }
            if (owner) {
                int members = plugin.services().dir().memberCount(spaceId);
                int min = Math.max(2, members);
                int hard = plugin.config().groupHardMaxMembers;
                set(31, ui.builder("max-members", Material.PLAYER_HEAD, "gui.settings.max-members", Messages.arg("max", s.maxMembers()),
                        Messages.arg("members", members), Messages.arg("hard", hard)).amount(s.maxMembers()).build());
                if (s.maxMembers() > min) {
                    set(30, ui.item("minus", Material.RED_CONCRETE, "gui.settings.max-minus"), c -> changeMax(c, -1, min, hard));
                }
                if (s.maxMembers() < hard) {
                    set(32, ui.item("plus", Material.LIME_CONCRETE, "gui.settings.max-plus"), c -> changeMax(c, 1, min, hard));
                }
            }
        }
    }

    private void rename(Player p) {
        EndSpace s = space(spaceId).orElse(null);
        if (s == null) return;
        mgr.prompts().ask(p, new TextPrompt.Request(msg().get("gui.settings.rename-title"),
                msg().get("gui.settings.rename-hint", Messages.arg("min", plugin.config().nameMin), Messages.arg("max", plugin.config().nameMax)),
                s.name(), plugin.config().nameMax, mgr::validateName,
                text -> mgr.exec(p, () -> plugin.actions().rename(p, s, text), r -> reopen(p)),
                () -> reopen(p)));
    }

    private void toggleVisibility(Player p) {
        space(spaceId).ifPresent(s -> perform(p, () -> plugin.actions().setVisibility(p, s, !s.isPublic(), s.joinMode()), r -> refresh()));
    }

    private void toggleJoin(Player p) {
        space(spaceId).ifPresent(s -> perform(p,
                () -> plugin.actions().setVisibility(p, s, s.isPublic(), s.joinMode() == JoinMode.OPEN ? JoinMode.APPROVAL : JoinMode.OPEN),
                r -> refresh()));
    }

    private void changeMax(Click c, int dir, int min, int hard) {
        space(spaceId).ifPresent(s -> {
            int step = c.shift() ? 5 : 1;
            int target = Math.max(min, Math.min(hard, s.maxMembers() + dir * step));
            if (target == s.maxMembers()) return;
            perform(c.player(), () -> plugin.actions().setMaxMembers(c.player(), s, target), r -> refresh());
        });
    }
}
