package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.PlayerProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {
    /** Inserts or refreshes name/last-seen. Never touches the selected space. */
    PlayerProfile upsertSeen(Tx tx, UUID player, String name, long now) throws Exception;

    Optional<PlayerProfile> find(Tx tx, UUID player) throws Exception;

    Optional<PlayerProfile> findByName(Tx tx, String name) throws Exception;

    void setSelected(Tx tx, UUID player, UUID spaceId) throws Exception;

    /** Clears the selection of {@code player} only if it currently points at {@code spaceId}. */
    boolean clearSelectedIf(Tx tx, UUID player, UUID spaceId) throws Exception;

    /** Clears every profile selecting {@code spaceId}. @return affected players */
    List<UUID> clearSelectedForSpace(Tx tx, UUID spaceId) throws Exception;

    List<PlayerProfile> listAll(Tx tx) throws Exception;

    /** Sets a per-player flag once. @return true if newly set */
    boolean setFlag(Tx tx, UUID player, String flag, long now) throws Exception;

    /** All players having {@code flag}. */
    List<UUID> playersWithFlag(Tx tx, String flag) throws Exception;
}
