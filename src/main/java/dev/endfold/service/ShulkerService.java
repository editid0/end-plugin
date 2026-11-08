package dev.endfold.service;

import dev.endfold.model.BlockPos;
import dev.endfold.model.ShulkerSpawn;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persistence of shulker spawn points and their respawn schedules (independent per EndSpace). */
public final class ShulkerService {
    private final ServiceContext ctx;

    public ShulkerService(ServiceContext ctx) { this.ctx = ctx; }

    public CompletableFuture<Result<Boolean>> register(ShulkerSpawn spawn) {
        return ctx.tx("shulker-register", tx -> ctx.repo.shulkers().register(tx, spawn));
    }

    public CompletableFuture<Result<List<ShulkerSpawn>>> list(UUID spaceId) {
        return ctx.tx("shulker-list", tx -> ctx.repo.shulkers().list(tx, spaceId));
    }

    public CompletableFuture<Result<Void>> schedule(UUID spaceId, BlockPos pos, long nextSpawnAt, UUID entityUuid) {
        return ctx.tx("shulker-schedule", tx -> {
            ctx.repo.shulkers().schedule(tx, spaceId, pos, nextSpawnAt, entityUuid);
            return null;
        });
    }
}
