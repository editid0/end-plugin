package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.BlockPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LootRepository {
    record Container(UUID spaceId, BlockPos pos, String lootTable) {}

    record PersonalInventory(UUID spaceId, UUID player, BlockPos pos, String lootTable, int size, byte[] contents, long rev) {}

    /** @return true if newly registered */
    boolean registerContainer(Tx tx, UUID spaceId, BlockPos pos, String lootTable, long now) throws Exception;

    Optional<Container> container(Tx tx, UUID spaceId, BlockPos pos) throws Exception;

    List<Container> containers(Tx tx, UUID spaceId) throws Exception;

    /** Inserts a once-only claim. @return true if this call created it (false = already claimed) */
    boolean claim(Tx tx, UUID spaceId, UUID player, String key, long now) throws Exception;

    boolean hasClaim(Tx tx, UUID spaceId, UUID player, String key) throws Exception;

    List<String> claims(Tx tx, UUID spaceId, UUID player) throws Exception;

    Optional<PersonalInventory> inventory(Tx tx, UUID spaceId, UUID player, BlockPos pos) throws Exception;

    void insertInventory(Tx tx, PersonalInventory inv, long now) throws Exception;

    /** Saves contents only if {@code expectedRev} matches, bumping the revision. @return new revision, or -1 if stale */
    long saveInventory(Tx tx, UUID spaceId, UUID player, BlockPos pos, byte[] contents, long expectedRev, long now) throws Exception;

    int countInventories(Tx tx, UUID player) throws Exception;
}
