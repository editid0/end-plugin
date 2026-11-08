package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.PendingDelivery;
import java.util.List;
import java.util.UUID;

public interface ElytraRepository {
    /** @return true if inserted (false = this player already claimed this ship in this space) */
    boolean insertClaim(Tx tx, UUID spaceId, UUID player, String shipId, UUID deliveryId, long now) throws Exception;

    boolean hasClaim(Tx tx, UUID spaceId, UUID player, String shipId) throws Exception;

    int countClaims(Tx tx, UUID player) throws Exception;

    int countClaimsInSpace(Tx tx, UUID spaceId, UUID player) throws Exception;

    long lastClaimAt(Tx tx, UUID player) throws Exception;

    // Outbox
    void insertDelivery(Tx tx, PendingDelivery d) throws Exception;

    boolean markDelivered(Tx tx, UUID deliveryId, long now) throws Exception;

    List<PendingDelivery> pending(Tx tx, UUID player) throws Exception;

    int countPending(Tx tx, UUID player) throws Exception;
}
