package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpaceRepository {
    void insert(Tx tx, EndSpace space) throws Exception;

    /** Persists mutable columns (name, owner, status, world uuid, max members, last activity) and the public listing. */
    void update(Tx tx, EndSpace space) throws Exception;

    Optional<EndSpace> find(Tx tx, UUID id) throws Exception;

    Optional<EndSpace> findByWorldName(Tx tx, String worldName) throws Exception;

    /** All spaces except DELETED ones. */
    List<EndSpace> listNotDeleted(Tx tx) throws Exception;

    List<EndSpace> listAll(Tx tx) throws Exception;

    int countOwnedLive(Tx tx, UUID owner, SpaceType type) throws Exception;

    boolean worldNameExists(Tx tx, String worldName) throws Exception;

    /** Atomically increments member_count if below max_members. @return true if a slot was reserved */
    boolean reserveSlot(Tx tx, UUID spaceId) throws Exception;

    void releaseSlot(Tx tx, UUID spaceId) throws Exception;

    int memberCount(Tx tx, UUID spaceId) throws Exception;

    void touch(Tx tx, UUID spaceId, long now) throws Exception;

    /** Hard delete (cascades). Only used by admin purge. */
    void purge(Tx tx, UUID spaceId) throws Exception;
}
