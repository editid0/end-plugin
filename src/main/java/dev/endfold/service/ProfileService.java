package dev.endfold.service;

import static dev.endfold.service.ServiceContext.fail;

import dev.endfold.model.EndSpace;
import dev.endfold.model.PlayerProfile;
import dev.endfold.model.ReturnLocation;
import dev.endfold.model.SpaceStatus;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Player profiles, destination selection and per-(player, space) return locations. */
public final class ProfileService {
    private final ServiceContext ctx;

    /** Set the first time a player enters any EndSpace through a real End portal (the stronghold route). */
    public static final String FLAG_END_PORTAL_USED = "end_portal_used";

    public ProfileService(ServiceContext ctx) { this.ctx = ctx; }

    /** Records a per-player flag (idempotent). @return true if it was newly set */
    public CompletableFuture<Result<Boolean>> setFlag(UUID player, String flag) {
        return ctx.tx("set-flag", tx -> {
            boolean fresh = ctx.repo.profiles().setFlag(tx, player, flag, ctx.now());
            tx.afterCommit(() -> ctx.dir.putFlag(player, flag));
            return fresh;
        });
    }

    /** Cache read; true once {@link #setFlag} committed for this player. */
    public boolean hasFlag(UUID player, String flag) { return ctx.dir.hasFlag(player, flag); }

    public CompletableFuture<Result<PlayerProfile>> seen(UUID player, String name) {
        return ctx.tx("profile-seen", tx -> {
            PlayerProfile p = ctx.repo.profiles().upsertSeen(tx, player, name, ctx.now());
            // Defensive repair: a selection that points at a space the player can no longer enter is cleared.
            if (p.selectedSpaceId() != null) {
                Optional<EndSpace> sp = ctx.repo.spaces().find(tx, p.selectedSpaceId());
                boolean ok = sp.isPresent() && sp.get().status() == SpaceStatus.ACTIVE
                        && ctx.repo.members().find(tx, p.selectedSpaceId(), player).isPresent();
                if (!ok) {
                    ctx.repo.profiles().setSelected(tx, player, null);
                    p = p.withSelected(null);
                }
            }
            PlayerProfile fin = p;
            tx.afterCommit(() -> ctx.dir.putProfile(fin));
            return fin;
        });
    }

    /**
     * Selects the End portal destination for {@code player} only. Never changes memberships, other players'
     * selections, progress or claims.
     */
    public CompletableFuture<Result<EndSpace>> select(UUID player, UUID spaceId) {
        return ctx.tx("select", tx -> {
            EndSpace sp = ctx.repo.spaces().find(tx, spaceId).orElseThrow(() -> fail(Failure.NOT_FOUND));
            if (sp.status() != SpaceStatus.ACTIVE) throw fail(Failure.SPACE_NOT_ACTIVE);
            if (ctx.repo.members().find(tx, spaceId, player).isEmpty()) throw fail(Failure.NOT_MEMBER);
            ctx.repo.profiles().setSelected(tx, player, spaceId);
            tx.afterCommit(() -> ctx.dir.setSelected(player, spaceId));
            return sp;
        });
    }

    public CompletableFuture<Result<Void>> clearSelection(UUID player) {
        return ctx.tx("clear-select", tx -> {
            ctx.repo.profiles().setSelected(tx, player, null);
            tx.afterCommit(() -> ctx.dir.setSelected(player, null));
            return null;
        });
    }

    /** The player's selected destination if it is still enterable (cache read, no I/O). */
    public Optional<EndSpace> selectedSpace(UUID player) {
        return ctx.dir.selectedId(player).filter(id -> ctx.dir.canEnter(id, player)).flatMap(ctx.dir::space);
    }

    public CompletableFuture<Result<Void>> saveReturn(ReturnLocation loc) {
        return ctx.tx("save-return", tx -> {
            ctx.repo.returns().upsert(tx, loc, ctx.now());
            return null;
        });
    }

    public CompletableFuture<Result<Optional<ReturnLocation>>> findReturn(UUID player, UUID spaceId) {
        return ctx.tx("find-return", tx -> ctx.repo.returns().find(tx, player, spaceId));
    }

    public CompletableFuture<Result<Void>> saveSession(ReturnLocation inSpace) {
        return ctx.tx("save-session", tx -> {
            ctx.repo.returns().saveSession(tx, inSpace, ctx.now());
            return null;
        });
    }

    /** Reads and deletes the logout session (one-shot). */
    public CompletableFuture<Result<Optional<ReturnLocation>>> takeSession(UUID player) {
        return ctx.tx("take-session", tx -> {
            Optional<ReturnLocation> s = ctx.repo.returns().session(tx, player);
            if (s.isPresent()) ctx.repo.returns().clearSession(tx, player);
            return s;
        });
    }
}
