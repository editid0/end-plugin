package dev.endfold.repo;

import dev.endfold.db.Tx;
import java.util.List;
import java.util.UUID;

public interface AuditRepository {
    record Entry(long ts, UUID spaceId, UUID actor, String action, String detail) {}

    void log(Tx tx, UUID spaceId, UUID actor, String action, String detail, long now) throws Exception;

    List<Entry> recent(Tx tx, UUID spaceId, int limit) throws Exception;
}
