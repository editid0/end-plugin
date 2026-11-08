package dev.endfold.service;

import dev.endfold.db.Tx;
import dev.endfold.model.Achievement;
import dev.endfold.model.PlayerProgress;
import dev.endfold.model.SpaceProgress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Shared (space) and individual (player) progression, dragon fight records and achievements. */
public final class ProgressService {
    private final ServiceContext ctx;
    private final Supplier<Set<AchievementType>> enabled;

    public ProgressService(ServiceContext ctx, Supplier<Set<AchievementType>> enabled) {
        this.ctx = ctx;
        this.enabled = enabled;
    }

    /** Newly granted achievements keyed by player, to be announced by the caller. */
    public record Grants(Map<UUID, List<AchievementType>> byPlayer) {
        public static Grants none() { return new Grants(Map.of()); }
    }

    /** Summary of a completed dragon fight, as passed to {@link #recordDragonKill}. */
    public record FightOutcome(UUID spaceId, UUID killer, Map<UUID, Double> damage, Set<UUID> participants, int gatewayCount) {}

    public CompletableFuture<Result<SpaceProgress>> space(UUID spaceId) {
        return ctx.tx("space-progress", tx -> ctx.repo.progress().space(tx, spaceId));
    }

    public CompletableFuture<Result<PlayerProgress>> player(UUID spaceId, UUID player) {
        return ctx.tx("player-progress", tx -> ctx.repo.progress().player(tx, spaceId, player));
    }

    public CompletableFuture<Result<List<PlayerProgress>>> playerAll(UUID player) {
        return ctx.tx("player-progress-all", tx -> ctx.repo.progress().playerAll(tx, player));
    }

    public CompletableFuture<Result<List<Achievement>>> achievements(UUID player) {
        return ctx.tx("achievements", tx -> ctx.repo.progress().achievements(tx, player));
    }

    public CompletableFuture<Result<UUID>> fightStarted(UUID spaceId) {
        return ctx.tx("fight-start", tx -> ctx.repo.progress().startFight(tx, spaceId, ctx.now()));
    }

    /**
     * Records a dragon victory for the space and individual participation for every participant, in one transaction.
     * Participation is per fight, so players who join later can earn it in a subsequent fight.
     */
    public CompletableFuture<Result<Grants>> recordDragonKill(FightOutcome o) {
        return ctx.tx("dragon-kill", tx -> {
            long now = ctx.now();
            UUID fight = ctx.repo.progress().startFight(tx, o.spaceId(), now);
            ctx.repo.progress().saveParticipants(tx, fight, o.damage(), o.participants());
            ctx.repo.progress().endFight(tx, fight, "KILLED", o.killer(), now);
            SpaceProgress sp = ctx.repo.progress().space(tx, o.spaceId());
            SpaceProgress updated = new SpaceProgress(o.spaceId(), true, sp.dragonKills() + 1, Math.max(sp.gatewaysActivated(), o.gatewayCount()),
                    sp.firstKillAt() == 0 ? now : sp.firstKillAt(), now);
            ctx.repo.progress().saveSpace(tx, updated, now);
            Map<UUID, List<AchievementType>> grants = new HashMap<>();
            for (UUID p : o.participants()) {
                boolean killer = p.equals(o.killer());
                ctx.repo.progress().addPlayer(tx, o.spaceId(), p, 1, killer ? 1 : 0, o.damage().getOrDefault(p, 0.0), 0, 0, 0, now);
                grant(tx, grants, p, o.spaceId(), AchievementType.DRAGON_PARTICIPANT);
                if (killer) grant(tx, grants, p, o.spaceId(), AchievementType.DRAGON_FINAL_BLOW);
                int total = ctx.repo.progress().playerAll(tx, p).stream().mapToInt(PlayerProgress::dragonParticipations).sum();
                if (total >= 5) grant(tx, grants, p, o.spaceId(), AchievementType.DRAGON_VETERAN);
            }
            ctx.audit(tx, o.spaceId(), o.killer(), "DRAGON_KILL", "participants=" + o.participants().size() + " kills=" + updated.dragonKills());
            return new Grants(grants);
        });
    }

    public CompletableFuture<Result<Void>> fightAborted(UUID spaceId) {
        return ctx.tx("fight-abort", tx -> {
            var active = ctx.repo.progress().activeFight(tx, spaceId);
            if (active.isPresent()) ctx.repo.progress().endFight(tx, active.get(), "ABORTED", null, ctx.now());
            return null;
        });
    }

    public CompletableFuture<Result<Void>> gateways(UUID spaceId, int count) {
        return ctx.tx("gateways", tx -> {
            SpaceProgress sp = ctx.repo.progress().space(tx, spaceId);
            if (count > sp.gatewaysActivated()) {
                ctx.repo.progress().saveSpace(tx, new SpaceProgress(spaceId, sp.dragonDefeated(), sp.dragonKills(), count, sp.firstKillAt(), sp.lastKillAt()), ctx.now());
            }
            return null;
        });
    }

    public CompletableFuture<Result<Grants>> arrived(UUID spaceId, UUID player, int membershipCount) {
        return ctx.tx("arrived", tx -> {
            Map<UUID, List<AchievementType>> g = new HashMap<>();
            grant(tx, g, player, spaceId, AchievementType.FIRST_ARRIVAL);
            if (membershipCount >= 3) grant(tx, g, player, spaceId, AchievementType.EXPEDITIONIST);
            return new Grants(g);
        });
    }

    public CompletableFuture<Result<Grants>> chestLooted(UUID spaceId, UUID player) {
        return ctx.tx("chest-looted", tx -> {
            long now = ctx.now();
            ctx.repo.progress().addPlayer(tx, spaceId, player, 0, 0, 0, 1, 0, 0, now);
            Map<UUID, List<AchievementType>> g = new HashMap<>();
            grant(tx, g, player, spaceId, AchievementType.CITY_EXPLORER);
            int total = ctx.repo.progress().playerAll(tx, player).stream().mapToInt(PlayerProgress::chestsLooted).sum();
            if (total >= 10) grant(tx, g, player, spaceId, AchievementType.TREASURE_HUNTER);
            return new Grants(g);
        });
    }

    /** Grants the Elytra achievement after a successful claim (the claim itself is recorded by ElytraService). */
    public CompletableFuture<Result<Grants>> elytraClaimed(UUID spaceId, UUID player) {
        return ctx.tx("elytra-claimed", tx -> {
            Map<UUID, List<AchievementType>> g = new HashMap<>();
            grant(tx, g, player, spaceId, AchievementType.WINGS_OF_THE_VOID);
            return new Grants(g);
        });
    }

    public CompletableFuture<Result<Grants>> shulkerKilled(UUID spaceId, UUID player) {
        return ctx.tx("shulker-killed", tx -> {
            ctx.repo.progress().addPlayer(tx, spaceId, player, 0, 0, 0, 0, 0, 1, ctx.now());
            Map<UUID, List<AchievementType>> g = new HashMap<>();
            int total = ctx.repo.progress().playerAll(tx, player).stream().mapToInt(PlayerProgress::shulkersKilled).sum();
            if (total >= 10) grant(tx, g, player, spaceId, AchievementType.SHELL_COLLECTOR);
            return new Grants(g);
        });
    }

    private void grant(Tx tx, Map<UUID, List<AchievementType>> grants, UUID player, UUID spaceId, AchievementType type) throws Exception {
        Set<AchievementType> on = enabled.get();
        if (on != null && !on.contains(type)) return;
        if (ctx.repo.progress().grantAchievement(tx, player, type.key(), spaceId, ctx.now())) {
            grants.computeIfAbsent(player, k -> new ArrayList<>()).add(type);
        }
    }

    public static Set<AchievementType> all() { return EnumSet.allOf(AchievementType.class); }
}
