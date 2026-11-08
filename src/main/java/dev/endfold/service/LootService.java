package dev.endfold.service;

import dev.endfold.model.BlockPos;
import dev.endfold.repo.LootRepository.Container;
import dev.endfold.repo.LootRepository.PersonalInventory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Personal End City chest inventories. Each (space, player, chest) gets loot generated exactly once.
 *
 * <p>Ordering for crash safety: loot is rolled on the server thread, then a single transaction inserts the once-only
 * claim and the inventory row together. If the claim already exists the stored inventory is returned instead, so a
 * second roll is never shown. Inventory edits are saved with optimistic revisions.</p>
 */
public final class LootService {
    private final ServiceContext ctx;

    public LootService(ServiceContext ctx) { this.ctx = ctx; }

    public static String chestClaimKey(BlockPos pos) { return "chest:" + pos.key(); }

    public CompletableFuture<Result<Boolean>> registerContainer(UUID spaceId, BlockPos pos, String lootTable) {
        return ctx.tx("register-container", tx -> ctx.repo.loot().registerContainer(tx, spaceId, pos, lootTable, ctx.now()));
    }

    public CompletableFuture<Result<List<Container>>> containers(UUID spaceId) {
        return ctx.tx("containers", tx -> ctx.repo.loot().containers(tx, spaceId));
    }

    public CompletableFuture<Result<Optional<PersonalInventory>>> load(UUID spaceId, UUID player, BlockPos pos) {
        return ctx.tx("load-inv", tx -> ctx.repo.loot().inventory(tx, spaceId, player, pos));
    }

    /** Outcome of {@link #createOnce}: the stored inventory and whether this call created it. */
    public record Created(PersonalInventory inventory, boolean fresh) {}

    public CompletableFuture<Result<Created>> createOnce(UUID spaceId, UUID player, BlockPos pos, String lootTable, int size, byte[] contents) {
        return ctx.tx("create-inv", tx -> {
            if (ctx.repo.loot().claim(tx, spaceId, player, chestClaimKey(pos), ctx.now())) {
                PersonalInventory inv = new PersonalInventory(spaceId, player, pos, lootTable, size, contents, 0);
                ctx.repo.loot().insertInventory(tx, inv, ctx.now());
                ctx.audit(tx, spaceId, player, "CHEST_GENERATED", pos.key());
                return new Created(inv, true);
            }
            PersonalInventory existing = ctx.repo.loot().inventory(tx, spaceId, player, pos)
                    .orElseThrow(() -> ServiceContext.fail(Failure.ALREADY_CLAIMED));
            return new Created(existing, false);
        });
    }

    /** @return the new revision, or a failure if the stored revision moved on (stale writer). */
    public CompletableFuture<Result<Long>> save(UUID spaceId, UUID player, BlockPos pos, byte[] contents, long rev) {
        return ctx.tx("save-inv", tx -> {
            long next = ctx.repo.loot().saveInventory(tx, spaceId, player, pos, contents, rev, ctx.now());
            if (next < 0) throw ServiceContext.fail(Failure.INVALID_VALUE);
            return next;
        });
    }

    public CompletableFuture<Result<List<String>>> claims(UUID spaceId, UUID player) {
        return ctx.tx("claims", tx -> ctx.repo.loot().claims(tx, spaceId, player));
    }

    public CompletableFuture<Result<Integer>> inventoryCount(UUID player) {
        return ctx.tx("inv-count", tx -> ctx.repo.loot().countInventories(tx, player));
    }
}
