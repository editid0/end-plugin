package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.repo.AuditRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class SqlAuditRepository implements AuditRepository {
    @Override public void log(Tx tx, UUID spaceId, UUID actor, String action, String detail, long now) throws SQLException {
        tx.update("INSERT INTO end_audit_log(ts, space_id, actor_uuid, action, detail) VALUES (?,?,?,?,?)", now,
                spaceId == null ? null : spaceId.toString(), actor == null ? null : actor.toString(), action, detail);
    }

    @Override public List<Entry> recent(Tx tx, UUID spaceId, int limit) throws SQLException {
        if (spaceId == null) {
            return tx.query("SELECT * FROM end_audit_log ORDER BY id DESC LIMIT ?", rs -> new Entry(rs.getLong("ts"), Tx.uuid(rs, "space_id"),
                    Tx.uuid(rs, "actor_uuid"), rs.getString("action"), rs.getString("detail")), limit);
        }
        return tx.query("SELECT * FROM end_audit_log WHERE space_id=? ORDER BY id DESC LIMIT ?", rs -> new Entry(rs.getLong("ts"), Tx.uuid(rs, "space_id"),
                Tx.uuid(rs, "actor_uuid"), rs.getString("action"), rs.getString("detail")), spaceId, limit);
    }
}
