package dev.endfold.service;

import static dev.endfold.service.ServiceContext.fail;

import dev.endfold.db.Tx;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Membership;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Creating, configuring, disbanding and administering EndSpaces (records only; worlds are handled by InstanceManager). */
public final class SpaceService {
    private final ServiceContext ctx;
    private final SecureRandom random = new SecureRandom();

    public SpaceService(ServiceContext ctx) { this.ctx = ctx; }

    public CompletableFuture<Result<EndSpace>> create(UUID actor, String actorName, SpaceType type, String rawName) {
        String name = Names.normalise(rawName);
        return ctx.tx("create-space", tx -> {
            ServiceSettings s = ctx.settings();
            if (type == SpaceType.SOLO && !s.soloEnabled()) throw fail(Failure.TYPE_DISABLED);
            if (type == SpaceType.GROUP && !s.groupEnabled()) throw fail(Failure.TYPE_DISABLED);
            if (!Names.valid(name, s.nameMinLength(), s.nameMaxLength())) throw fail(Failure.INVALID_NAME);
            if (type == SpaceType.SOLO && ctx.repo.spaces().countOwnedLive(tx, actor, SpaceType.SOLO) > 0) throw fail(Failure.SOLO_ALREADY_EXISTS);
            if (type == SpaceType.GROUP && ctx.repo.spaces().countOwnedLive(tx, actor, SpaceType.GROUP) >= s.maxOwnedGroups()) {
                throw fail(Failure.OWNED_LIMIT_REACHED);
            }
            if (liveMemberships(tx, actor) >= s.maxMembershipsPerPlayer()) throw fail(Failure.MEMBERSHIP_LIMIT_REACHED);

            UUID id;
            String world;
            do {
                id = UUID.randomUUID();
                world = EndSpace.worldNameFor(id);
            } while (ctx.repo.spaces().worldNameExists(tx, world));

            long now = ctx.now();
            int max = type == SpaceType.SOLO ? 1 : s.groupDefaultMaxMembers();
            EndSpace space = new EndSpace(id, type, name, actor, world, null, seed(s), SpaceStatus.ACTIVE, max, false, JoinMode.APPROVAL, "", now, now);
            ctx.repo.spaces().insert(tx, space);
            ctx.repo.profiles().upsertSeen(tx, actor, actorName, now);
            if (!ctx.repo.spaces().reserveSlot(tx, id)) throw fail(Failure.INTERNAL_ERROR);
            Membership owner = new Membership(id, actor, Role.OWNER, now);
            ctx.repo.members().insert(tx, owner);
            var profile = ctx.repo.profiles().find(tx, actor).orElseThrow();
            boolean autoSelect = profile.selectedSpaceId() == null;
            if (autoSelect) ctx.repo.profiles().setSelected(tx, actor, id);
            ctx.audit(tx, id, actor, "SPACE_CREATE", type + " '" + name + "' world=" + world);
            var finalProfile = autoSelect ? profile.withSelected(id) : profile;
            tx.afterCommit(() -> {
                ctx.dir.putSpace(space);
                ctx.dir.putMember(owner);
                ctx.dir.putProfile(finalProfile);
            });
            return space;
        });
    }

    private int liveMemberships(Tx tx, UUID player) throws Exception {
        int n = 0;
        for (Membership m : ctx.repo.members().listByPlayer(tx, player)) {
            Optional<EndSpace> sp = ctx.repo.spaces().find(tx, m.spaceId());
            if (sp.isPresent() && sp.get().status().isLive()) n++;
        }
        return n;
    }

    private long seed(ServiceSettings s) {
        return switch (s.seedMode()) {
            case RANDOM -> random.nextLong();
            case FIXED -> s.fixedSeed();
            case OVERWORLD -> s.overworldSeed();
        };
    }

    /** Returns the player's live SOLO space, creating it when missing and solo spaces are enabled. */
    public CompletableFuture<Result<EndSpace>> ensureSolo(UUID player, String playerName, String defaultName) {
        Optional<EndSpace> existing = ctx.dir.spacesOf(player).stream()
                .filter(sp -> sp.type() == SpaceType.SOLO && sp.ownerUuid().equals(player) && sp.status().isLive()).findFirst();
        if (existing.isPresent()) return CompletableFuture.completedFuture(Result.ok(existing.get()));
        return create(player, playerName, SpaceType.SOLO, defaultName);
    }

    private EndSpace requireSpace(Tx tx, UUID spaceId) throws Exception {
        return ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
    }

    private Role requireRole(Tx tx, UUID spaceId, UUID actor, Role min) throws Exception {
        Role r = ctx.repo.members().find(tx, spaceId, actor).map(Membership::role).orElseThrow(() -> fail(Failure.NOT_MEMBER));
        if (!r.atLeast(min)) throw fail(Failure.NO_PERMISSION);
        return r;
    }

    public CompletableFuture<Result<EndSpace>> rename(UUID actor, UUID spaceId, String rawName) {
        String name = Names.normalise(rawName);
        return ctx.tx("rename", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            if (sp.status() != SpaceStatus.ACTIVE) throw fail(Failure.SPACE_NOT_ACTIVE);
            requireRole(tx, spaceId, actor, Role.ADMIN);
            ServiceSettings s = ctx.settings();
            if (!Names.valid(name, s.nameMinLength(), s.nameMaxLength())) throw fail(Failure.INVALID_NAME);
            EndSpace updated = sp.withName(name);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, actor, "SPACE_RENAME", sp.name() + " -> " + name);
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    public CompletableFuture<Result<EndSpace>> setVisibility(UUID actor, UUID spaceId, boolean isPublic, JoinMode mode) {
        return ctx.tx("visibility", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            if (sp.status() != SpaceStatus.ACTIVE) throw fail(Failure.SPACE_NOT_ACTIVE);
            requireRole(tx, spaceId, actor, Role.ADMIN);
            if (sp.type() == SpaceType.SOLO && isPublic) throw fail(Failure.SOLO_NOT_SHAREABLE);
            if (isPublic && !ctx.settings().publicEnabled()) throw fail(Failure.PUBLIC_DISABLED);
            EndSpace updated = sp.withPublic(isPublic, mode == null ? sp.joinMode() : mode);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, actor, "SPACE_VISIBILITY", "public=" + isPublic + " mode=" + updated.joinMode());
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    public CompletableFuture<Result<EndSpace>> setDescription(UUID actor, UUID spaceId, String description) {
        String d = Names.normalise(description);
        return ctx.tx("description", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            requireRole(tx, spaceId, actor, Role.ADMIN);
            if (d.length() > 120 || d.indexOf('<') >= 0) throw fail(Failure.INVALID_VALUE);
            EndSpace updated = sp.withDescription(d);
            ctx.repo.spaces().update(tx, updated);
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    public CompletableFuture<Result<EndSpace>> setMaxMembers(UUID actor, UUID spaceId, int max) {
        return ctx.tx("max-members", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            requireRole(tx, spaceId, actor, Role.OWNER);
            if (sp.type() == SpaceType.SOLO) throw fail(Failure.SOLO_NOT_SHAREABLE);
            int count = ctx.repo.spaces().memberCount(tx, spaceId);
            if (max < Math.max(2, count) || max > ctx.settings().groupHardMaxMembers()) throw fail(Failure.INVALID_VALUE);
            EndSpace updated = sp.withMaxMembers(max);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, actor, "SPACE_MAX_MEMBERS", String.valueOf(max));
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    /**
     * Disbands (archives) a space. Only the owner may do this. The world and every record are retained so an admin can
     * restore it; members lose access and their selection is cleared. Never deletes files.
     */
    public CompletableFuture<Result<EndSpace>> disband(UUID actor, UUID spaceId) {
        return ctx.tx("disband", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            if (sp.status() != SpaceStatus.ACTIVE && sp.status() != SpaceStatus.BROKEN) throw fail(Failure.SPACE_NOT_ACTIVE);
            requireRole(tx, spaceId, actor, Role.OWNER);
            return archive(tx, sp, actor, "SPACE_DISBAND");
        });
    }

    private EndSpace archive(Tx tx, EndSpace sp, UUID actor, String action) throws Exception {
        long now = ctx.now();
        EndSpace updated = sp.withStatus(SpaceStatus.ARCHIVED).withPublic(false, sp.joinMode());
        ctx.repo.spaces().update(tx, updated);
        ctx.repo.invitations().revokeAllForSpace(tx, sp.id(), now);
        ctx.repo.requests().cancelAllForSpace(tx, sp.id(), now);
        List<UUID> cleared = ctx.repo.profiles().clearSelectedForSpace(tx, sp.id());
        ctx.audit(tx, sp.id(), actor, action, "members=" + ctx.repo.spaces().memberCount(tx, sp.id()));
        tx.afterCommit(() -> {
            ctx.dir.putSpace(updated);
            cleared.forEach(p -> ctx.dir.setSelected(p, null));
        });
        return updated;
    }

    public CompletableFuture<Result<EndSpace>> markWorldGenerated(UUID spaceId, UUID worldUuid) {
        return ctx.tx("world-generated", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            if (worldUuid.equals(sp.worldUuid())) return sp;
            EndSpace updated = sp.withWorldUuid(worldUuid);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, null, sp.worldUuid() == null ? "WORLD_GENERATED" : "WORLD_UUID_CHANGED", worldUuid.toString());
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    public CompletableFuture<Result<Void>> touch(UUID spaceId) {
        long now = ctx.now();
        return ctx.tx("touch", tx -> {
            ctx.repo.spaces().touch(tx, spaceId, now);
            tx.afterCommit(() -> ctx.dir.space(spaceId).ifPresent(s -> ctx.dir.putSpace(s.withLastActive(now))));
            return null;
        });
    }

    // ---- admin ----

    public CompletableFuture<Result<EndSpace>> adminSetStatus(UUID admin, UUID spaceId, SpaceStatus status, String reason) {
        return ctx.tx("admin-status", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            if (sp.status() == SpaceStatus.DELETED) throw fail(Failure.SPACE_NOT_ACTIVE); // deleted spaces are final
            if (status == SpaceStatus.ARCHIVED) return archive(tx, sp, admin, "ADMIN_ARCHIVE");
            EndSpace updated = sp.withStatus(status);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, admin, "ADMIN_STATUS", sp.status() + " -> " + status + (reason == null ? "" : " (" + reason + ")"));
            tx.afterCommit(() -> ctx.dir.putSpace(updated));
            return updated;
        });
    }

    /** Admin purge of all records. The caller must already have unloaded the world and moved its folder to trash. */
    public CompletableFuture<Result<EndSpace>> adminMarkDeleted(UUID admin, UUID spaceId) {
        return ctx.tx("admin-delete", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            EndSpace updated = sp.withStatus(SpaceStatus.DELETED).withPublic(false, sp.joinMode());
            ctx.repo.spaces().update(tx, updated);
            ctx.repo.invitations().revokeAllForSpace(tx, spaceId, ctx.now());
            ctx.repo.requests().cancelAllForSpace(tx, spaceId, ctx.now());
            List<UUID> cleared = ctx.repo.profiles().clearSelectedForSpace(tx, spaceId);
            ctx.audit(tx, spaceId, admin, "ADMIN_DELETE", sp.worldName());
            tx.afterCommit(() -> {
                ctx.dir.removeSpace(spaceId);
                cleared.forEach(p -> ctx.dir.setSelected(p, null));
            });
            return updated;
        });
    }

    public CompletableFuture<Result<EndSpace>> adminTransfer(UUID admin, UUID spaceId, UUID newOwner, String newOwnerName) {
        return ctx.tx("admin-transfer", tx -> {
            EndSpace sp = requireSpace(tx, spaceId);
            UUID old = sp.ownerUuid();
            if (old.equals(newOwner)) return sp;
            if (ctx.repo.members().find(tx, spaceId, newOwner).isEmpty()) {
                if (!ctx.repo.spaces().reserveSlot(tx, spaceId)) throw fail(Failure.SPACE_FULL);
                ctx.repo.profiles().upsertSeen(tx, newOwner, newOwnerName, ctx.now());
                ctx.repo.members().insert(tx, new Membership(spaceId, newOwner, Role.MEMBER, ctx.now()));
            }
            ctx.repo.members().updateRole(tx, spaceId, old, Role.ADMIN);
            ctx.repo.members().updateRole(tx, spaceId, newOwner, Role.OWNER);
            EndSpace updated = sp.withOwner(newOwner);
            ctx.repo.spaces().update(tx, updated);
            ctx.audit(tx, spaceId, admin, "ADMIN_TRANSFER", old + " -> " + newOwner);
            List<Membership> members = ctx.repo.members().listBySpace(tx, spaceId);
            tx.afterCommit(() -> {
                ctx.dir.putSpace(updated);
                members.forEach(ctx.dir::putMember);
            });
            return updated;
        });
    }

    public CompletableFuture<Result<List<EndSpace>>> listAllFromDb() {
        return ctx.tx("list-all", tx -> ctx.repo.spaces().listAll(tx));
    }
}
