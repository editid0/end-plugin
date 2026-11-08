package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import dev.endfold.util.Sync;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Admin tools for one EndSpace. Destructive tools ask for confirmation; permissions are re-checked on every click. */
final class AdminSpaceMenu extends Menu {
    private final UUID spaceId;

    AdminSpaceMenu(MenuManager mgr, Player viewer, UUID spaceId, Runnable back) {
        super(mgr, viewer, 5, back);
        this.spaceId = spaceId;
    }

    @Override
    protected Component title() {
        return msg().get("gui.admin.detail-title", Messages.arg("space", space(spaceId).map(EndSpace::name).orElse("?")));
    }

    @Override
    protected boolean allowed(Player p) { return p.hasPermission("endfold.admin.worlds"); }

    private boolean has(Player p, String perm) {
        if (p.hasPermission(perm)) return true;
        msg().fail(p, dev.endfold.service.Failure.NO_PERMISSION);
        return false;
    }

    private void refreshLater() { Sync.later(30L, this::refresh); }

    @Override
    protected void render() {
        Player p = viewer();
        Optional<EndSpace> opt = space(spaceId);
        if (p == null || opt.isEmpty()) {
            showGone();
            return;
        }
        EndSpace s = opt.get();
        var dir = plugin.services().dir();
        navigation();
        set(4, ui.item(s.type() == SpaceType.SOLO ? "space-solo" : "space-group", s.type() == SpaceType.SOLO ? Material.ENDER_EYE : Material.ENDER_CHEST,
                "gui.admin.info", Messages.arg("space", s.name()), Messages.arg("id", s.shortId()), Messages.arg("world", s.worldName()),
                Messages.comp("type", ui.type(s)), Messages.comp("status", ui.status(s.status())), Messages.arg("owner", ui.ownerName(s)),
                Messages.arg("members", dir.memberCount(spaceId)), Messages.arg("max", s.maxMembers()), Messages.comp("state", ui.loaded(s)),
                Messages.arg("online", plugin.instances().playersIn(s).size()), Messages.arg("created", ui.date(s.createdAt())),
                Messages.arg("active", ui.date(s.lastActiveAt()))));

        if (plugin.instances().isLoaded(s)) {
            set(10, ui.item("unload", Material.HOPPER, "gui.admin.unload"), c -> {
                plugin.admin().unload(c.player(), s);
                refreshLater();
            });
        } else {
            set(10, ui.item("load", Material.LIME_DYE, "gui.admin.load"), c -> {
                plugin.admin().load(c.player(), s);
                refreshLater();
            });
        }

        if (p.hasPermission("endfold.admin.teleport")) {
            set(12, ui.item("teleport", Material.ENDER_PEARL, "gui.admin.teleport"), c -> {
                if (!has(c.player(), "endfold.admin.teleport")) return;
                c.player().closeInventory();
                plugin.admin().teleport(c.player(), s);
            });
        }

        if (p.hasPermission("endfold.admin.backup")) {
            set(14, ui.item("backup", Material.CHEST_MINECART, "gui.admin.backup"), c -> {
                if (!has(c.player(), "endfold.admin.backup")) return;
                plugin.admin().backup(c.player(), s, "gui");
                msg().send(c.player(), "gui.admin.backup-started", Messages.arg("space", s.name()));
            });
        }

        if (s.status() == SpaceStatus.ACTIVE) {
            if (p.hasPermission("endfold.admin.delete")) {
                set(16, ui.item("archive", Material.BARREL, "gui.admin.archive"), c -> confirmArchive(c.player(), s));
            }
        } else if (s.status() != SpaceStatus.DELETED) {
            set(16, ui.item("activate", Material.LIME_CONCRETE, "gui.admin.activate"), c -> {
                plugin.admin().activate(c.player(), s);
                refreshLater();
            });
        }

        if (p.hasPermission("endfold.admin.delete")) {
            set(24, ui.item("delete", Material.TNT, "gui.admin.delete"), c -> confirmDelete(c.player(), s));
        }
    }

    private void reopen(Player p) { new AdminSpaceMenu(mgr, p, spaceId, back).open(); }

    private void confirmArchive(Player p, EndSpace s) {
        if (!has(p, "endfold.admin.delete")) return;
        ConfirmMenu.ofRun(mgr, p, msg().get("gui.confirm.archive.title", Messages.arg("space", s.name())),
                ui.item("archive", Material.BARREL, "gui.confirm.archive.summary", Messages.arg("space", s.name())), false,
                () -> plugin.admin().archive(p, s), () -> Sync.later(20L, () -> reopen(p)), () -> reopen(p)).open();
    }

    private void confirmDelete(Player p, EndSpace s) {
        if (!has(p, "endfold.admin.delete")) return;
        ConfirmMenu.ofRun(mgr, p, msg().get("gui.confirm.delete.title", Messages.arg("space", s.name())),
                ui.item("delete", Material.TNT, "gui.confirm.delete.summary", Messages.arg("space", s.name())), true,
                () -> plugin.admin().delete(p, s), () -> back.run(), () -> reopen(p)).open();
    }
}
