package dev.endfold.action;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Invitation;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Membership;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceType;
import dev.endfold.service.MembershipService.Removal;
import dev.endfold.service.Result;
import dev.endfold.util.Sync;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Player-facing operations shared by GUIs and commands. Each method calls the service layer (which re-validates
 * everything), then - on the server thread - sends feedback, notifies affected players and evicts players who lost
 * access. The returned future completes on the server thread after feedback was sent, so menus can refresh.
 */
public final class Actions {
    private final EndfoldPlugin plugin;

    public Actions(EndfoldPlugin plugin) { this.plugin = plugin; }

    private Messages msg() { return plugin.messages(); }

    private <T> CompletableFuture<Result<T>> after(Player actor, CompletableFuture<Result<T>> f, Consumer<T> onOk) {
        return f.thenApplyAsync(r -> {
            if (r.isOk()) {
                try {
                    onOk.accept(r.value());
                } catch (RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Post-action handler failed", e);
                }
            } else if (actor != null && actor.isOnline()) {
                msg().fail(actor, r.failure());
            }
            return r;
        }, Sync.main());
    }

    public String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return plugin.services().dir().lastKnownName(uuid).orElseGet(() -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            return op.getName() == null ? msg().raw("common.unknown-player") : op.getName();
        });
    }

    private void notify(UUID player, String key, Map<String, String> tokens, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) msg().sendTokens(p, key, tokens, r);
    }

    private void notifyAdmins(EndSpace space, String key, Map<String, String> tokens, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        for (Membership m : plugin.services().dir().members(space.id())) {
            if (m.role().atLeast(Role.ADMIN)) notify(m.playerUuid(), key, tokens, r);
        }
    }

    // ------------------------------------------------------------------ destination & travel

    /** Selects the portal destination. Does not teleport and does not touch memberships. */
    public CompletableFuture<Result<EndSpace>> select(Player p, EndSpace space) {
        return after(p, plugin.services().profiles.select(p.getUniqueId(), space.id()),
                s -> msg().send(p, "space.selected", Messages.arg("space", s.name())));
    }

    /** Teleports into the space now (does not change the selected destination). */
    public void enter(Player p, EndSpace space) { plugin.router().enter(p, space, false); }

    public void enterSelected(Player p) { plugin.router().enterSelected(p, false); }

    public void exit(Player p) { plugin.router().exit(p, false); }

    // ------------------------------------------------------------------ space lifecycle

    private final Map<UUID, Long> lastCreate = new java.util.concurrent.ConcurrentHashMap<>();

    /** Creates a group EndSpace, throttled per player by {@code instances.worlds.create-cooldown-seconds}. */
    public CompletableFuture<Result<EndSpace>> createGroup(Player p, String name) {
        long cooldown = plugin.config().createCooldownSeconds * 1000L;
        Long last = lastCreate.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (cooldown > 0 && last != null && now - last < cooldown && !p.hasPermission("endfold.admin.worlds")) {
            msg().send(p, "errors.create-cooldown", Messages.arg("seconds", (cooldown - (now - last) + 999) / 1000));
            return CompletableFuture.completedFuture(Result.fail(dev.endfold.service.Failure.RATE_LIMITED));
        }
        return after(p, plugin.services().spaces.create(p.getUniqueId(), p.getName(), SpaceType.GROUP, name), s -> {
            lastCreate.put(p.getUniqueId(), System.currentTimeMillis());
            msg().send(p, "space.created", Messages.arg("space", s.name()));
        });
    }

    /** Creates (if needed) and selects the player's personal End. */
    public CompletableFuture<Result<EndSpace>> solo(Player p) {
        String name = plugin.config().soloDefaultName.replace("{player}", p.getName());
        if (name.length() > plugin.config().nameMax) name = name.substring(0, plugin.config().nameMax);
        boolean existed = plugin.services().dir().spacesOf(p.getUniqueId()).stream()
                .anyMatch(s -> s.type() == SpaceType.SOLO && s.ownerUuid().equals(p.getUniqueId()) && s.status().isLive());
        CompletableFuture<Result<EndSpace>> f = plugin.services().spaces.ensureSolo(p.getUniqueId(), p.getName(), name)
                .thenCompose(r -> r.isOk() ? plugin.services().profiles.select(p.getUniqueId(), r.value().id()) : CompletableFuture.completedFuture(r));
        return after(p, f, s -> {
            if (!existed) msg().send(p, "space.created", Messages.arg("space", s.name()));
            msg().send(p, "space.selected", Messages.arg("space", s.name()));
        });
    }

    public CompletableFuture<Result<EndSpace>> rename(Player p, EndSpace space, String name) {
        return after(p, plugin.services().spaces.rename(p.getUniqueId(), space.id(), name),
                s -> msg().send(p, "space.renamed", Messages.arg("space", s.name())));
    }

    public CompletableFuture<Result<EndSpace>> setVisibility(Player p, EndSpace space, boolean isPublic, JoinMode mode) {
        return after(p, plugin.services().spaces.setVisibility(p.getUniqueId(), space.id(), isPublic, mode), s -> {
            if (s.isPublic()) msg().send(p, "space.visibility-public", Messages.arg("space", s.name()),
                    Messages.comp("mode", msg().get(s.joinMode() == JoinMode.OPEN ? "common.join-open" : "common.join-approval")));
            else msg().send(p, "space.visibility-private", Messages.arg("space", s.name()));
        });
    }

    public CompletableFuture<Result<EndSpace>> setMaxMembers(Player p, EndSpace space, int max) {
        return after(p, plugin.services().spaces.setMaxMembers(p.getUniqueId(), space.id(), max),
                s -> msg().send(p, "space.max-members", Messages.arg("max", s.maxMembers())));
    }

    /** Archives the space (owner only). Members are notified and moved out; the world is kept. */
    public CompletableFuture<Result<EndSpace>> disband(Player p, EndSpace space) {
        var members = plugin.services().dir().members(space.id());
        return after(p, plugin.services().spaces.disband(p.getUniqueId(), space.id()), s -> {
            for (Membership m : members) notify(m.playerUuid(), "space.disbanded", Map.of(), Messages.arg("space", s.name()));
            plugin.router().evictUnauthorised(s);
        });
    }

    public CompletableFuture<Result<Removal>> leave(Player p, EndSpace space) {
        return after(p, plugin.services().members.leave(p.getUniqueId(), space.id()), r -> {
            if (r.newOwner() != null) {
                msg().send(p, "space.left-new-owner", Messages.arg("space", space.name()), Messages.arg("player", nameOf(r.newOwner())));
                notify(r.newOwner(), "space.you-are-owner", Map.of(), Messages.arg("space", space.name()));
            } else {
                msg().send(p, "space.left", Messages.arg("space", space.name()));
            }
            plugin.router().evictUnauthorised(space);
        });
    }

    // ------------------------------------------------------------------ invitations

    public CompletableFuture<Result<Invitation>> invite(Player p, EndSpace space, UUID target, String targetName) {
        return after(p, plugin.services().members.invite(p.getUniqueId(), space.id(), target, targetName), inv -> {
            msg().send(p, "invite.sent", Messages.arg("player", targetName), Messages.arg("space", space.name()));
            notify(target, "invite.received", Map.of("id", inv.shortId()), Messages.arg("player", p.getName()), Messages.arg("space", space.name()));
        });
    }

    public CompletableFuture<Result<Membership>> accept(Player p, Invitation inv) {
        EndSpace space = plugin.services().dir().space(inv.spaceId()).orElse(null);
        String spaceName = space == null ? "?" : space.name();
        return after(p, plugin.services().members.acceptInvite(p.getUniqueId(), p.getName(), inv.id()), m -> {
            msg().sendTokens(p, "invite.accepted", Map.of("id", EndSpace.shortId(m.spaceId())), Messages.arg("space", spaceName));
            if (space != null) notifyAdmins(space, "invite.accepted-notify", Map.of(), Messages.arg("player", p.getName()), Messages.arg("space", spaceName));
        });
    }

    public CompletableFuture<Result<Invitation>> decline(Player p, Invitation inv) {
        String spaceName = plugin.services().dir().space(inv.spaceId()).map(EndSpace::name).orElse("?");
        return after(p, plugin.services().members.declineInvite(p.getUniqueId(), inv.id()),
                i -> msg().send(p, "invite.declined", Messages.arg("space", spaceName)));
    }

    public CompletableFuture<Result<Invitation>> revoke(Player p, Invitation inv) {
        return after(p, plugin.services().members.revokeInvite(p.getUniqueId(), inv.id()),
                i -> msg().send(p, "invite.revoked", Messages.arg("player", nameOf(i.inviteeUuid()))));
    }

    /** Accept/decline by short id, full id, space id or space name (used by commands and chat buttons). */
    public void acceptToken(Player p, String token, boolean accept) {
        Sync.then(plugin.services().members.findInvitation(p.getUniqueId(), token), r -> {
            if (!r.isOk()) {
                msg().fail(p, r.failure());
                return;
            }
            if (accept) accept(p, r.value());
            else decline(p, r.value());
        });
    }

    // ------------------------------------------------------------------ members

    public CompletableFuture<Result<Removal>> kick(Player p, EndSpace space, UUID target) {
        String targetName = nameOf(target);
        return after(p, plugin.services().members.kick(p.getUniqueId(), space.id(), target), r -> {
            msg().send(p, "space.kicked", Messages.arg("player", targetName), Messages.arg("space", space.name()));
            notify(target, "space.you-were-kicked", Map.of(), Messages.arg("space", space.name()));
            plugin.router().evictUnauthorised(space);
        });
    }

    public CompletableFuture<Result<Membership>> setRole(Player p, EndSpace space, UUID target, Role role) {
        return after(p, plugin.services().members.setRole(p.getUniqueId(), space.id(), target, role), m -> {
            var roleText = msg().get("common.role-" + role.name().toLowerCase(java.util.Locale.ROOT));
            msg().send(p, "space.role-changed", Messages.arg("player", nameOf(target)), Messages.comp("role", roleText), Messages.arg("space", space.name()));
            notify(target, "space.role-changed", Map.of(), Messages.arg("player", nameOf(target)), Messages.comp("role", roleText), Messages.arg("space", space.name()));
        });
    }

    public CompletableFuture<Result<EndSpace>> transfer(Player p, EndSpace space, UUID target) {
        return after(p, plugin.services().members.transferOwnership(p.getUniqueId(), space.id(), target), s -> {
            msg().send(p, "space.ownership-transferred", Messages.arg("player", nameOf(target)), Messages.arg("space", s.name()));
            notify(target, "space.you-are-owner", Map.of(), Messages.arg("space", s.name()));
        });
    }

    // ------------------------------------------------------------------ public expeditions

    public CompletableFuture<Result<Membership>> joinPublic(Player p, EndSpace space) {
        return after(p, plugin.services().members.joinPublic(p.getUniqueId(), p.getName(), space.id()), m -> {
            msg().sendTokens(p, "public.joined", Map.of("id", space.shortId()), Messages.arg("space", space.name()));
            notifyAdmins(space, "invite.accepted-notify", Map.of(), Messages.arg("player", p.getName()), Messages.arg("space", space.name()));
        });
    }

    public CompletableFuture<Result<MembershipRequest>> requestJoin(Player p, EndSpace space) {
        return after(p, plugin.services().members.requestJoin(p.getUniqueId(), p.getName(), space.id()), r -> {
            msg().send(p, "public.requested", Messages.arg("space", space.name()));
            notifyAdmins(space, "public.request-received", Map.of("id", space.shortId()), Messages.arg("player", p.getName()), Messages.arg("space", space.name()));
        });
    }

    public CompletableFuture<Result<MembershipRequest>> cancelRequest(Player p, MembershipRequest req) {
        return after(p, plugin.services().members.cancelRequest(p.getUniqueId(), req.id()), r -> msg().send(p, "public.request-cancelled"));
    }

    public CompletableFuture<Result<Membership>> approve(Player p, MembershipRequest req) {
        String spaceName = plugin.services().dir().space(req.spaceId()).map(EndSpace::name).orElse("?");
        return after(p, plugin.services().members.approveRequest(p.getUniqueId(), req.id()), m -> {
            msg().send(p, "invite.accepted-notify", Messages.arg("player", nameOf(req.playerUuid())), Messages.arg("space", spaceName));
            notify(req.playerUuid(), "public.request-approved", Map.of(), Messages.arg("space", spaceName));
        });
    }

    public CompletableFuture<Result<MembershipRequest>> deny(Player p, MembershipRequest req) {
        String spaceName = plugin.services().dir().space(req.spaceId()).map(EndSpace::name).orElse("?");
        return after(p, plugin.services().members.denyRequest(p.getUniqueId(), req.id()),
                r -> notify(req.playerUuid(), "public.request-denied", Map.of(), Messages.arg("space", spaceName)));
    }

    public Optional<EndSpace> selected(Player p) { return plugin.services().profiles.selectedSpace(p.getUniqueId()); }
}
