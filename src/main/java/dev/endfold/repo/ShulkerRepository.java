package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.BlockPos;
import dev.endfold.model.ShulkerSpawn;
import java.util.List;
import java.util.UUID;

public interface ShulkerRepository {
    boolean register(Tx tx, ShulkerSpawn spawn) throws Exception;

    List<ShulkerSpawn> list(Tx tx, UUID spaceId) throws Exception;

    void schedule(Tx tx, UUID spaceId, BlockPos pos, long nextSpawnAt, UUID entityUuid) throws Exception;
}
