package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.ReturnLocation;
import java.util.Optional;
import java.util.UUID;

public interface ReturnLocationRepository {
    void upsert(Tx tx, ReturnLocation loc, long now) throws Exception;

    Optional<ReturnLocation> find(Tx tx, UUID player, UUID spaceId) throws Exception;

    /** Logout position inside an EndSpace (worldUuid/worldName unused). */
    void saveSession(Tx tx, ReturnLocation inSpace, long now) throws Exception;

    Optional<ReturnLocation> session(Tx tx, UUID player) throws Exception;

    void clearSession(Tx tx, UUID player) throws Exception;
}
