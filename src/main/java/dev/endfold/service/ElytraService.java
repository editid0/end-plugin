package dev.endfold.service;

import static dev.endfold.service.ServiceContext.fail;

import dev.endfold.model.PendingDelivery;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Elytra claims with an outbox. The claim row and the pending delivery are committed together before any item is
 * given; delivery is then marked complete. On a crash between the two, the outbox entry is redelivered on next join,
 * and the delivered item carries the delivery id so redelivery can be detected.
 */
public final class ElytraService {
    private final ServiceContext ctx;

    public ElytraService(ServiceContext ctx) { this.ctx = ctx; }

    public CompletableFuture<Result<PendingDelivery>> claim(UUID spaceId, UUID player, String shipId, UUID deliveryId, byte[] itemBytes) {
        return ctx.tx("elytra-claim", tx -> {
            ServiceSettings s = ctx.settings();
            if (ctx.repo.elytra().hasClaim(tx, spaceId, player, shipId)) throw fail(Failure.ALREADY_CLAIMED);
            if (s.elytraPerSpace() > 0 && ctx.repo.elytra().countClaimsInSpace(tx, spaceId, player) >= s.elytraPerSpace()) {
                throw fail(Failure.CLAIM_LIMIT_REACHED);
            }
            if (s.elytraGlobalLimit() > 0 && ctx.repo.elytra().countClaims(tx, player) >= s.elytraGlobalLimit()) {
                throw fail(Failure.CLAIM_LIMIT_REACHED);
            }
            long now = ctx.now();
            if (s.elytraCooldownMillis() > 0 && now - ctx.repo.elytra().lastClaimAt(tx, player) < s.elytraCooldownMillis()) {
                throw fail(Failure.CLAIM_COOLDOWN);
            }
            if (!ctx.repo.elytra().insertClaim(tx, spaceId, player, shipId, deliveryId, now)) throw fail(Failure.ALREADY_CLAIMED);
            PendingDelivery d = new PendingDelivery(deliveryId, player, spaceId, "ELYTRA", itemBytes, now);
            ctx.repo.elytra().insertDelivery(tx, d);
            ctx.repo.progress().addPlayer(tx, spaceId, player, 0, 0, 0, 0, 1, 0, now);
            ctx.audit(tx, spaceId, player, "ELYTRA_CLAIM", shipId);
            return d;
        });
    }

    public CompletableFuture<Result<Boolean>> hasClaim(UUID spaceId, UUID player, String shipId) {
        return ctx.tx("elytra-has", tx -> ctx.repo.elytra().hasClaim(tx, spaceId, player, shipId));
    }

    public CompletableFuture<Result<Boolean>> markDelivered(UUID deliveryId) {
        return ctx.tx("delivered", tx -> ctx.repo.elytra().markDelivered(tx, deliveryId, ctx.now()));
    }

    public CompletableFuture<Result<List<PendingDelivery>>> pending(UUID player) {
        return ctx.tx("pending", tx -> ctx.repo.elytra().pending(tx, player));
    }

    public CompletableFuture<Result<Integer>> totalClaims(UUID player) {
        return ctx.tx("elytra-count", tx -> ctx.repo.elytra().countClaims(tx, player));
    }
}
