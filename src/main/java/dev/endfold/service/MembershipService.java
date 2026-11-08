package dev.endfold.service;

import static dev.endfold.service.ServiceContext.fail;

import dev.endfold.db.Tx;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Invitation;
import dev.endfold.model.InviteStatus;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Membership;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.RequestStatus;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Membership, invitations, roles, ownership and public-expedition joining. Every method re-validates permissions inside
 * its transaction, so GUIs and commands can share it safely even with stale menus.
 */
public final class MembershipService {
    private final ServiceContext ctx;

    public MembershipService(ServiceContext ctx) { this.ctx = ctx; }

    /** Result of a leave/kick: who was removed, and whether their selection was cleared. */
    public record Removal(UUID spaceId, UUID player, boolean selectionCleared, UUID newOwner) {}

    // ------------------------------------------------------------------ helpers

    private EndSpace activeSpace(Tx tx, UUID spaceId) throws Exception {
        EndSpace sp = ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
        if (sp.status() != SpaceStatus.ACTIVE) throw fail(Failure.SPACE_NOT_ACTIVE);
        return sp;
    }

    private Membership requireMember(Tx tx, UUID spaceId, UUID player) throws Exception {
        return ctx.repo.members().find(tx, spaceId, player).orElseThrow(() -> fail(Failure.NOT_MEMBER));
    }

    private int liveMemberships(Tx tx, UUID player) throws Exception {
        int n = 0;
        for (Membership m : ctx.repo.members().listByPlayer(tx, player)) {
            Optional<EndSpace> sp = ctx.repo.spaces().find(tx, m.spaceId());
            if (sp.isPresent() && sp.get().status().isLive()) n++;
        }
        return n;
    }

    /** Adds a MEMBER inside the current transaction, enforcing capacity and per-player membership limits. */
    private Membership addMember(Tx tx, EndSpace sp, UUID player, String name) throws Exception {
        if (ctx.repo.members().find(tx, sp.id(), player).isPresent()) throw fail(Failure.ALREADY_MEMBER);
        if (liveMemberships(tx, player) >= ctx.settings().maxMembershipsPerPlayer()) throw fail(Failure.MEMBERSHIP_LIMIT_REACHED);
        if (!ctx.repo.spaces().reserveSlot(tx, sp.id())) throw fail(Failure.SPACE_FULL);
        long now = ctx.now();
        ctx.repo.profiles().upsertSeen(tx, player, name, now);
        Membership m = new Membership(sp.id(), player, Role.MEMBER, now);
        ctx.repo.members().insert(tx, m);
        // A successful join supersedes any other pending path into the same space.
        ctx.repo.invitations().findPending(tx, sp.id(), player).ifPresent(i -> {
            try {
                ctx.repo.invitations().resolve(tx, i.id(), InviteStatus.ACCEPTED, now);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Optional<MembershipRequest> req = ctx.repo.requests().findPending(tx, sp.id(), player);
        if (req.isPresent()) ctx.repo.requests().resolve(tx, req.get().id(), RequestStatus.APPROVED, null, now);
        var profile = ctx.repo.profiles().find(tx, player).orElseThrow();
        tx.afterCommit(() -> {
            ctx.dir.putMember(m);
            ctx.dir.putProfile(profile);
        });
        return m;
    }

    private Removal removeMember(Tx tx, EndSpace sp, UUID player, UUID actor, String action) throws Exception {
        if (!ctx.repo.members().delete(tx, sp.id(), player)) throw fail(Failure.NOT_MEMBER);
        ctx.repo.spaces().releaseSlot(tx, sp.id());
        boolean cleared = ctx.repo.profiles().clearSelectedIf(tx, player, sp.id());
        // Invitations sent by someone who is no longer a member must not grant access afterwards.
        ctx.repo.invitations().revokeByInviter(tx, sp.id(), player, ctx.now());
        ctx.audit(tx, sp.id(), actor, action, player.toString());
        tx.afterCommit(() -> {
            ctx.dir.removeMember(sp.id(), player);
            if (cleared) ctx.dir.setSelected(player, null);
        });
        return new Removal(sp.id(), player, cleared, null);
    }

    // ------------------------------------------------------------------ invitations

    public CompletableFuture<Result<Invitation>> invite(UUID actor, UUID spaceId, UUID invitee, String inviteeName) {
        return ctx.tx("invite", tx -> {
            EndSpace sp = activeSpace(tx, spaceId);
            if (sp.type() == SpaceType.SOLO) throw fail(Failure.SOLO_NOT_SHAREABLE);
            Membership me = requireMember(tx, spaceId, actor);
            if (!me.role().atLeast(ctx.settings().membersCanInvite() ? Role.MEMBER : Role.ADMIN)) throw fail(Failure.NO_PERMISSION);
            if (actor.equals(invitee)) throw fail(Failure.CANNOT_TARGET_SELF);
            if (ctx.repo.members().find(tx, spaceId, invitee).isPresent()) throw fail(Failure.ALREADY_MEMBER);
            if (ctx.repo.invitations().findPending(tx, spaceId, invitee).isPresent()) throw fail(Failure.ALREADY_INVITED);
            if (ctx.repo.invitations().listPendingForSpace(tx, spaceId).size() >= ctx.settings().maxPendingInvitesPerSpace()) {
                throw fail(Failure.RATE_LIMITED);
            }
            if (ctx.repo.spaces().memberCount(tx, spaceId) >= sp.maxMembers()) throw fail(Failure.SPACE_FULL);
            long now = ctx.now();
            ctx.repo.profiles().upsertSeen(tx, invitee, inviteeName, ctx.repo.profiles().find(tx, invitee).map(p -> p.lastSeen()).orElse(now));
            Invitation inv = new Invitation(UUID.randomUUID(), spaceId, invitee, actor, InviteStatus.PENDING, now, now + ctx.settings().inviteTtlMillis());
            ctx.repo.invitations().insert(tx, inv);
            ctx.audit(tx, spaceId, actor, "INVITE", invitee.toString());
            var prof = ctx.repo.profiles().find(tx, invitee).orElseThrow();
            tx.afterCommit(() -> {
                if (ctx.dir.profile(invitee).isEmpty()) ctx.dir.putProfile(prof);
            });
            return inv;
        });
    }

    /** Resolves an invitation by full id or by its 8-character short id among the player's pending invitations. */
    public CompletableFuture<Result<Invitation>> findInvitation(UUID player, String token) {
        return ctx.tx("find-invite", tx -> {
            String t = token.strip().toLowerCase(java.util.Locale.ROOT);
            for (Invitation i : ctx.repo.invitations().listPendingForInvitee(tx, player)) {
                if (i.id().toString().equals(t) || i.shortId().equals(t)) return i;
                Optional<EndSpace> sp = ctx.repo.spaces().find(tx, i.spaceId());
                if (sp.isPresent() && (sp.get().shortId().equals(t) || sp.get().name().equalsIgnoreCase(token.strip()))) return i;
            }
            throw fail(Failure.NOT_FOUND);
        });
    }

    public CompletableFuture<Result<Membership>> acceptInvite(UUID player, String playerName, UUID inviteId) {
        return ctx.tx("accept-invite", tx -> {
            Invitation inv = ctx.repo.invitations().find(tx, inviteId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (!inv.inviteeUuid().equals(player)) throw fail(Failure.INVITE_NOT_FOR_YOU);
            if (inv.status() != InviteStatus.PENDING) throw fail(Failure.NOT_FOUND);
            // Expired rows are flipped to EXPIRED by the periodic sweeper; here we only refuse them.
            if (inv.isExpired(ctx.now())) throw fail(Failure.INVITE_EXPIRED);
            EndSpace sp = activeSpace(tx, inv.spaceId());
            // The inviter must still be allowed to invite (they may have been demoted or removed since).
            Role needed = ctx.settings().membersCanInvite() ? Role.MEMBER : Role.ADMIN;
            boolean inviterOk = ctx.repo.members().find(tx, sp.id(), inv.inviterUuid()).map(mm -> mm.role().atLeast(needed)).orElse(false);
            if (!inviterOk) {
                throw fail(Failure.INVITE_EXPIRED);
            }
            Membership m = addMember(tx, sp, player, playerName);
            if (!ctx.repo.invitations().resolve(tx, inviteId, InviteStatus.ACCEPTED, ctx.now())) {
                // addMember may already have resolved it; verify it is not some other terminal state
                Invitation now = ctx.repo.invitations().find(tx, inviteId).orElseThrow();
                if (now.status() != InviteStatus.ACCEPTED) throw fail(Failure.NOT_FOUND);
            }
            ctx.audit(tx, sp.id(), player, "INVITE_ACCEPT", inviteId.toString());
            return m;
        });
    }

    public CompletableFuture<Result<Invitation>> declineInvite(UUID player, UUID inviteId) {
        return ctx.tx("decline-invite", tx -> {
            Invitation inv = ctx.repo.invitations().find(tx, inviteId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (!inv.inviteeUuid().equals(player)) throw fail(Failure.INVITE_NOT_FOR_YOU);
            if (!ctx.repo.invitations().resolve(tx, inviteId, InviteStatus.DECLINED, ctx.now())) throw fail(Failure.NOT_FOUND);
            return inv;
        });
    }

    public CompletableFuture<Result<Invitation>> revokeInvite(UUID actor, UUID inviteId) {
        return ctx.tx("revoke-invite", tx -> {
            Invitation inv = ctx.repo.invitations().find(tx, inviteId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            Membership me = requireMember(tx, inv.spaceId(), actor);
            if (!me.role().atLeast(Role.ADMIN) && !inv.inviterUuid().equals(actor)) throw fail(Failure.NO_PERMISSION);
            if (!ctx.repo.invitations().resolve(tx, inviteId, InviteStatus.REVOKED, ctx.now())) throw fail(Failure.NOT_FOUND);
            ctx.audit(tx, inv.spaceId(), actor, "INVITE_REVOKE", inv.inviteeUuid().toString());
            return inv;
        });
    }

    public CompletableFuture<Result<List<Invitation>>> incoming(UUID player) {
        return ctx.tx("incoming", tx -> {
            long now = ctx.now();
            return ctx.repo.invitations().listPendingForInvitee(tx, player).stream().filter(i -> !i.isExpired(now)).toList();
        });
    }

    /** Pending invitations sent from spaces the player can manage, or sent by the player. */
    public CompletableFuture<Result<List<Invitation>>> outgoing(UUID player) {
        return ctx.tx("outgoing", tx -> {
            long now = ctx.now();
            java.util.Map<UUID, Invitation> out = new java.util.LinkedHashMap<>();
            for (Invitation i : ctx.repo.invitations().listPendingByInviter(tx, player)) out.put(i.id(), i);
            for (Membership m : ctx.repo.members().listByPlayer(tx, player)) {
                if (m.role().atLeast(Role.ADMIN)) {
                    for (Invitation i : ctx.repo.invitations().listPendingForSpace(tx, m.spaceId())) out.put(i.id(), i);
                }
            }
            return out.values().stream().filter(i -> !i.isExpired(now)).sorted(Comparator.comparingLong(Invitation::createdAt).reversed()).toList();
        });
    }

    public CompletableFuture<Result<List<Invitation>>> spaceInvites(UUID spaceId) {
        return ctx.tx("space-invites", tx -> {
            long now = ctx.now();
            return ctx.repo.invitations().listPendingForSpace(tx, spaceId).stream().filter(i -> !i.isExpired(now)).toList();
        });
    }

    public CompletableFuture<Result<Integer>> expireInvitations() {
        return ctx.tx("expire-invites", tx -> ctx.repo.invitations().expire(tx, ctx.now()));
    }

    // ------------------------------------------------------------------ member management

    public CompletableFuture<Result<Removal>> kick(UUID actor, UUID spaceId, UUID target) {
        return ctx.tx("kick", tx -> {
            EndSpace sp = ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (actor.equals(target)) throw fail(Failure.CANNOT_TARGET_SELF);
            Membership me = requireMember(tx, spaceId, actor);
            Membership them = requireMember(tx, spaceId, target);
            if (them.role() == Role.OWNER) throw fail(Failure.CANNOT_TARGET_OWNER);
            if (!me.role().atLeast(Role.ADMIN) || !me.role().outranks(them.role())) throw fail(Failure.NO_PERMISSION);
            return removeMember(tx, sp, target, actor, "MEMBER_KICK");
        });
    }

    public CompletableFuture<Result<Membership>> setRole(UUID actor, UUID spaceId, UUID target, Role role) {
        return ctx.tx("set-role", tx -> {
            activeSpace(tx, spaceId);
            if (role == Role.OWNER) throw fail(Failure.INVALID_VALUE);
            if (actor.equals(target)) throw fail(Failure.CANNOT_TARGET_SELF);
            Membership me = requireMember(tx, spaceId, actor);
            Membership them = requireMember(tx, spaceId, target);
            if (me.role() != Role.OWNER) throw fail(Failure.NO_PERMISSION);
            if (them.role() == Role.OWNER) throw fail(Failure.CANNOT_TARGET_OWNER);
            ctx.repo.members().updateRole(tx, spaceId, target, role);
            ctx.audit(tx, spaceId, actor, "MEMBER_ROLE", target + " " + them.role() + " -> " + role);
            Membership updated = them.withRole(role);
            tx.afterCommit(() -> ctx.dir.putMember(updated));
            return updated;
        });
    }

    public CompletableFuture<Result<EndSpace>> transferOwnership(UUID actor, UUID spaceId, UUID target) {
        return ctx.tx("transfer", tx -> {
            EndSpace sp = activeSpace(tx, spaceId);
            if (actor.equals(target)) throw fail(Failure.CANNOT_TARGET_SELF);
            if (sp.type() == SpaceType.SOLO) throw fail(Failure.SOLO_NOT_SHAREABLE);
            Membership me = requireMember(tx, spaceId, actor);
            if (me.role() != Role.OWNER) throw fail(Failure.NO_PERMISSION);
            Membership them = requireMember(tx, spaceId, target);
            return doTransfer(tx, sp, me, them, actor);
        });
    }

    private EndSpace doTransfer(Tx tx, EndSpace sp, Membership oldOwner, Membership newOwner, UUID actor) throws Exception {
        if (sp.type() == SpaceType.GROUP
                && ctx.repo.spaces().countOwnedLive(tx, newOwner.playerUuid(), SpaceType.GROUP) >= ctx.settings().maxOwnedGroups()) {
            throw fail(Failure.OWNED_LIMIT_REACHED);
        }
        // Demote first: the partial unique index allows only one OWNER row per space.
        ctx.repo.members().updateRole(tx, sp.id(), oldOwner.playerUuid(), Role.ADMIN);
        ctx.repo.members().updateRole(tx, sp.id(), newOwner.playerUuid(), Role.OWNER);
        EndSpace updated = sp.withOwner(newOwner.playerUuid());
        ctx.repo.spaces().update(tx, updated);
        ctx.audit(tx, sp.id(), actor, "OWNER_TRANSFER", oldOwner.playerUuid() + " -> " + newOwner.playerUuid());
        Membership a = oldOwner.withRole(Role.ADMIN);
        Membership b = newOwner.withRole(Role.OWNER);
        tx.afterCommit(() -> {
            ctx.dir.putSpace(updated);
            ctx.dir.putMember(a);
            ctx.dir.putMember(b);
        });
        return updated;
    }

    /**
     * Leaves a space. Owners cannot silently abandon a group: depending on policy they must transfer first, or
     * ownership passes automatically to the longest-serving admin (else member). A sole owner must disband instead.
     */
    public CompletableFuture<Result<Removal>> leave(UUID player, UUID spaceId) {
        return ctx.tx("leave", tx -> {
            EndSpace sp = ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            Membership me = requireMember(tx, spaceId, player);
            if (me.role() != Role.OWNER) return removeMember(tx, sp, player, player, "MEMBER_LEAVE");
            List<Membership> others = ctx.repo.members().listBySpace(tx, spaceId).stream().filter(m -> !m.playerUuid().equals(player)).toList();
            if (others.isEmpty() || sp.type() == SpaceType.SOLO) throw fail(Failure.OWNER_MUST_DISBAND);
            if (ctx.settings().ownerLeavePolicy() == ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER) throw fail(Failure.OWNER_MUST_TRANSFER);
            Membership heir = others.stream()
                    .sorted(Comparator.comparingInt((Membership m) -> -m.role().rank()).thenComparingLong(Membership::joinedAt))
                    .findFirst().orElseThrow();
            doTransfer(tx, sp, me, heir, player);
            Removal r = removeMember(tx, sp, player, player, "OWNER_LEAVE");
            return new Removal(r.spaceId(), r.player(), r.selectionCleared(), heir.playerUuid());
        });
    }

    // ------------------------------------------------------------------ public expeditions

    public CompletableFuture<Result<Membership>> joinPublic(UUID player, String playerName, UUID spaceId) {
        return ctx.tx("join-public", tx -> {
            EndSpace sp = activeSpace(tx, spaceId);
            if (!sp.isPublic()) throw fail(Failure.NOT_PUBLIC);
            if (sp.joinMode() != JoinMode.OPEN) throw fail(Failure.APPROVAL_REQUIRED);
            Membership m = addMember(tx, sp, player, playerName);
            ctx.audit(tx, spaceId, player, "PUBLIC_JOIN", "");
            return m;
        });
    }

    public CompletableFuture<Result<MembershipRequest>> requestJoin(UUID player, String playerName, UUID spaceId) {
        return ctx.tx("request-join", tx -> {
            EndSpace sp = activeSpace(tx, spaceId);
            if (!sp.isPublic()) throw fail(Failure.NOT_PUBLIC);
            if (sp.joinMode() != JoinMode.APPROVAL) throw fail(Failure.OPEN_JOIN_ONLY);
            if (ctx.repo.members().find(tx, spaceId, player).isPresent()) throw fail(Failure.ALREADY_MEMBER);
            if (ctx.repo.requests().findPending(tx, spaceId, player).isPresent()) throw fail(Failure.ALREADY_REQUESTED);
            if (ctx.repo.requests().listPendingForPlayer(tx, player).size() >= 10) throw fail(Failure.RATE_LIMITED);
            long now = ctx.now();
            ctx.repo.profiles().upsertSeen(tx, player, playerName, now);
            MembershipRequest r = new MembershipRequest(UUID.randomUUID(), spaceId, player, RequestStatus.PENDING, now);
            ctx.repo.requests().insert(tx, r);
            var prof = ctx.repo.profiles().find(tx, player).orElseThrow();
            tx.afterCommit(() -> ctx.dir.putProfile(prof));
            return r;
        });
    }

    public CompletableFuture<Result<MembershipRequest>> cancelRequest(UUID player, UUID requestId) {
        return ctx.tx("cancel-request", tx -> {
            MembershipRequest r = ctx.repo.requests().find(tx, requestId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (!r.playerUuid().equals(player)) throw fail(Failure.NO_PERMISSION);
            if (!ctx.repo.requests().resolve(tx, requestId, RequestStatus.CANCELLED, player, ctx.now())) throw fail(Failure.NOT_FOUND);
            return r;
        });
    }

    public CompletableFuture<Result<Membership>> approveRequest(UUID actor, UUID requestId) {
        return ctx.tx("approve-request", tx -> {
            MembershipRequest r = ctx.repo.requests().find(tx, requestId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (r.status() != RequestStatus.PENDING) throw fail(Failure.NOT_FOUND);
            EndSpace sp = activeSpace(tx, r.spaceId());
            if (!requireMember(tx, sp.id(), actor).role().atLeast(Role.ADMIN)) throw fail(Failure.NO_PERMISSION);
            String name = ctx.repo.profiles().find(tx, r.playerUuid()).map(p -> p.lastName()).orElse("unknown");
            Membership m = addMember(tx, sp, r.playerUuid(), name);
            ctx.audit(tx, sp.id(), actor, "REQUEST_APPROVE", r.playerUuid().toString());
            return m;
        });
    }

    public CompletableFuture<Result<MembershipRequest>> denyRequest(UUID actor, UUID requestId) {
        return ctx.tx("deny-request", tx -> {
            MembershipRequest r = ctx.repo.requests().find(tx, requestId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (!requireMember(tx, r.spaceId(), actor).role().atLeast(Role.ADMIN)) throw fail(Failure.NO_PERMISSION);
            if (!ctx.repo.requests().resolve(tx, requestId, RequestStatus.DENIED, actor, ctx.now())) throw fail(Failure.NOT_FOUND);
            ctx.audit(tx, r.spaceId(), actor, "REQUEST_DENY", r.playerUuid().toString());
            return r;
        });
    }

    public CompletableFuture<Result<List<MembershipRequest>>> requestsForSpace(UUID spaceId) {
        return ctx.tx("requests-space", tx -> ctx.repo.requests().listPendingForSpace(tx, spaceId));
    }

    public CompletableFuture<Result<List<MembershipRequest>>> requestsOf(UUID player) {
        return ctx.tx("requests-player", tx -> ctx.repo.requests().listPendingForPlayer(tx, player));
    }

    // ------------------------------------------------------------------ admin

    public CompletableFuture<Result<Removal>> adminRemove(UUID admin, UUID spaceId, UUID target) {
        return ctx.tx("admin-remove", tx -> {
            EndSpace sp = ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            Membership them = requireMember(tx, spaceId, target);
            if (them.role() == Role.OWNER) throw fail(Failure.CANNOT_TARGET_OWNER);
            return removeMember(tx, sp, target, admin, "ADMIN_REMOVE_MEMBER");
        });
    }

    public CompletableFuture<Result<Membership>> adminAdd(UUID admin, UUID spaceId, UUID target, String name) {
        return ctx.tx("admin-add", tx -> {
            EndSpace sp = activeSpace(tx, spaceId);
            Membership m = addMember(tx, sp, target, name);
            ctx.audit(tx, spaceId, admin, "ADMIN_ADD_MEMBER", target.toString());
            return m;
        });
    }
}
