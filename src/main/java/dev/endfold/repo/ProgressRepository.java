package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.Achievement;
import dev.endfold.model.PlayerProgress;
import dev.endfold.model.SpaceProgress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProgressRepository {
    SpaceProgress space(Tx tx, UUID spaceId) throws Exception;

    void saveSpace(Tx tx, SpaceProgress p, long now) throws Exception;

    PlayerProgress player(Tx tx, UUID spaceId, UUID player) throws Exception;

    List<PlayerProgress> playerAll(Tx tx, UUID player) throws Exception;

    List<PlayerProgress> spacePlayers(Tx tx, UUID spaceId) throws Exception;

    /** Adds deltas to a player's progress row, creating it when missing. */
    void addPlayer(Tx tx, UUID spaceId, UUID player, int participations, int kills, double damage, int chests, int elytra,
                   int shulkers, long now) throws Exception;

    /** @return true if newly granted */
    boolean grantAchievement(Tx tx, UUID player, String key, UUID spaceId, long now) throws Exception;

    List<Achievement> achievements(Tx tx, UUID player) throws Exception;

    // Dragon fights
    Optional<UUID> activeFight(Tx tx, UUID spaceId) throws Exception;

    UUID startFight(Tx tx, UUID spaceId, long now) throws Exception;

    void endFight(Tx tx, UUID fightId, String outcome, UUID killer, long now) throws Exception;

    void saveParticipants(Tx tx, UUID fightId, Map<UUID, Double> damage, java.util.Set<UUID> participated) throws Exception;

    int fightCount(Tx tx, UUID spaceId) throws Exception;
}
