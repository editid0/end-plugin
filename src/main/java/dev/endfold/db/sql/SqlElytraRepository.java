package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.PendingDelivery;
import dev.endfold.repo.ElytraRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class SqlElytraRepository implements ElytraRepository {
    @Override public boolean insertClaim(Tx tx, UUID spaceId, UUID player, String shipId, UUID deliveryId, long now) throws SQLException {
        return tx.update("INSERT INTO end_elytra_claims(space_id, player_uuid, ship_id, delivery_id, claimed_at) VALUES (?,?,?,?,?) "
                + "ON CONFLICT(space_id, player_uuid, ship_id) DO NOTHING", spaceId, player, shipId, deliveryId, now) == 1;
    }

    @Override public boolean hasClaim(Tx tx, UUID spaceId, UUID player, String shipId) throws SQLException {
        return tx.count("SELECT COUNT(*) FROM end_elytra_claims WHERE space_id=? AND player_uuid=? AND ship_id=?", spaceId, player, shipId) > 0;
    }

    @Override public int countClaims(Tx tx, UUID player) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_elytra_claims WHERE player_uuid=?", player);
    }

    @Override public int countClaimsInSpace(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_elytra_claims WHERE space_id=? AND player_uuid=?", spaceId, player);
    }

    @Override public long lastClaimAt(Tx tx, UUID player) throws SQLException {
        return tx.count("SELECT COALESCE(MAX(claimed_at),0) FROM end_elytra_claims WHERE player_uuid=?", player);
    }

    @Override public void insertDelivery(Tx tx, PendingDelivery d) throws SQLException {
        tx.update("INSERT INTO end_pending_deliveries(delivery_id, player_uuid, space_id, kind, item_bytes, created_at) VALUES (?,?,?,?,?,?)",
                d.deliveryId(), d.playerUuid(), d.spaceId() == null ? null : d.spaceId().toString(), d.kind(), d.itemBytes(), d.createdAt());
    }

    @Override public boolean markDelivered(Tx tx, UUID deliveryId, long now) throws SQLException {
        return tx.update("UPDATE end_pending_deliveries SET delivered_at=? WHERE delivery_id=? AND delivered_at IS NULL", now, deliveryId) == 1;
    }

    @Override public List<PendingDelivery> pending(Tx tx, UUID player) throws SQLException {
        return tx.query("SELECT * FROM end_pending_deliveries WHERE player_uuid=? AND delivered_at IS NULL ORDER BY created_at",
                rs -> new PendingDelivery(Tx.uuid(rs, "delivery_id"), player, Tx.uuid(rs, "space_id"), rs.getString("kind"), rs.getBytes("item_bytes"),
                        rs.getLong("created_at")), player);
    }

    @Override public int countPending(Tx tx, UUID player) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_pending_deliveries WHERE player_uuid=? AND delivered_at IS NULL", player);
    }
}
