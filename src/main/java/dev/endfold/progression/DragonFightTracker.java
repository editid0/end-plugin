package dev.endfold.progression;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-memory state of one dragon fight in one EndSpace world. Server thread only. */
final class DragonFightTracker {
    final UUID dragonId;
    final long startedAt = System.currentTimeMillis();
    final Map<UUID, Double> damage = new HashMap<>();
    final Map<UUID, Integer> presenceSeconds = new HashMap<>();
    final Set<UUID> seen = new HashSet<>();
    UUID lastDamager;
    boolean scaled;

    DragonFightTracker(UUID dragonId) { this.dragonId = dragonId; }

    void addDamage(UUID player, double amount) {
        if (amount <= 0) return;
        damage.merge(player, amount, Double::sum);
        lastDamager = player;
        seen.add(player);
    }

    void addPresence(UUID player) {
        presenceSeconds.merge(player, 1, Integer::sum);
        seen.add(player);
    }

    /** Players who dealt at least {@code minDamage} or stayed at least {@code minSeconds}; {@code killer} always counts. */
    Set<UUID> participants(double minDamage, int minSeconds, UUID killer) {
        Set<UUID> out = new HashSet<>();
        for (UUID p : seen) {
            if (damage.getOrDefault(p, 0.0) >= minDamage && damage.containsKey(p) || presenceSeconds.getOrDefault(p, 0) >= minSeconds && presenceSeconds.containsKey(p)) {
                out.add(p);
            }
        }
        if (killer != null) out.add(killer);
        return out;
    }
}
