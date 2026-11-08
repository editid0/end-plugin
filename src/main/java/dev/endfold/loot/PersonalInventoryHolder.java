package dev.endfold.loot;

import dev.endfold.model.BlockPos;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Holder of one player's personal view of one End City chest. Mutable state is touched on the server thread only. */
final class PersonalInventoryHolder implements InventoryHolder {
    final UUID spaceId;
    final UUID player;
    final BlockPos pos;
    Inventory inventory;
    /** Last revision known to be stored in the database. */
    long rev;
    boolean dirty;
    boolean scheduled;
    boolean closed;
    /** Set when the stored revision moved on; no further saves are attempted for this view. */
    boolean stale;
    int retries;
    boolean failureNotified;
    CompletableFuture<dev.endfold.service.Result<Long>> inflight;

    PersonalInventoryHolder(UUID spaceId, UUID player, BlockPos pos, long rev) {
        this.spaceId = spaceId;
        this.player = player;
        this.pos = pos;
        this.rev = rev;
    }

    String key() { return spaceId + "/" + player + "/" + pos.key(); }

    @Override public Inventory getInventory() { return inventory; }
}
