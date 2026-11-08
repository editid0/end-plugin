package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import dev.endfold.service.ProgressService.FightOutcome;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressServiceTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    private FightOutcome kill(UUID space, UUID killer, Set<UUID> parts) {
        Map<UUID, Double> dmg = new java.util.HashMap<>();
        parts.forEach(p -> dmg.put(p, 10.0));
        return new FightOutcome(space, killer, dmg, parts, 0);
    }

    private boolean has(UUID player, AchievementType t) {
        return ok(f.svc.progress.achievements(player)).stream().anyMatch(a -> a.key().equals(t.key()));
    }

    @Test void killUpdatesSpaceAndParticipants() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.clock.advance(10);
        var grants = ok(f.svc.progress.recordDragonKill(kill(g.id(), ALICE, Set.of(ALICE, BOB))));
        SpaceProgress sp = ok(f.svc.progress.space(g.id()));
        assertTrue(sp.dragonDefeated());
        assertEquals(1, sp.dragonKills());
        assertEquals(f.clock.millis(), sp.firstKillAt());
        PlayerProgress a = ok(f.svc.progress.player(g.id(), ALICE));
        PlayerProgress b = ok(f.svc.progress.player(g.id(), BOB));
        assertEquals(1, a.dragonKills());
        assertEquals(1, a.dragonParticipations());
        assertEquals(0, b.dragonKills());
        assertEquals(1, b.dragonParticipations());
        assertEquals(10.0, b.damageDealt());
        assertTrue(grants.byPlayer().get(ALICE).contains(AchievementType.DRAGON_FINAL_BLOW));
        assertTrue(grants.byPlayer().get(BOB).contains(AchievementType.DRAGON_PARTICIPANT));
        assertFalse(grants.byPlayer().get(BOB).contains(AchievementType.DRAGON_FINAL_BLOW));
    }

    @Test void secondKillDoesNotRegrantFinalBlow() {
        EndSpace g = f.newGroup(ALICE);
        f.clock.advance(10);
        ok(f.svc.progress.recordDragonKill(kill(g.id(), ALICE, Set.of(ALICE))));
        long first = ok(f.svc.progress.space(g.id())).firstKillAt();
        f.clock.advance(1000);
        var second = ok(f.svc.progress.recordDragonKill(kill(g.id(), ALICE, Set.of(ALICE))));
        assertTrue(second.byPlayer().isEmpty());
        SpaceProgress sp = ok(f.svc.progress.space(g.id()));
        assertEquals(2, sp.dragonKills());
        assertEquals(first, sp.firstKillAt());
        assertEquals(1, ok(f.svc.progress.achievements(ALICE)).stream().filter(a -> a.key().equals("dragon_final_blow")).count());
        assertEquals(2, ok(f.svc.progress.player(g.id(), ALICE)).dragonKills());
    }

    @Test void lateJoinerWhoDidNotParticipateGetsNothing() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.progress.recordDragonKill(kill(g.id(), ALICE, Set.of(ALICE))));
        f.join(g, ALICE, CAROL);
        assertEquals(0, ok(f.svc.progress.player(g.id(), CAROL)).dragonParticipations());
        assertTrue(ok(f.svc.progress.achievements(CAROL)).isEmpty());
        assertTrue(ok(f.svc.progress.playerAll(CAROL)).isEmpty());
        // arrival achievements are still earned individually
        var arrived = ok(f.svc.progress.arrived(g.id(), CAROL, 1));
        assertEquals(List.of(AchievementType.FIRST_ARRIVAL), arrived.byPlayer().get(CAROL));
        assertFalse(has(CAROL, AchievementType.DRAGON_PARTICIPANT));
    }

    @Test void disabledAchievementsNotGranted() {
        EndSpace g = f.newGroup(ALICE);
        ProgressService limited = new ProgressService(f.svc.ctx, () -> EnumSet.of(AchievementType.DRAGON_PARTICIPANT));
        var grants = ok(limited.recordDragonKill(kill(g.id(), ALICE, Set.of(ALICE))));
        assertEquals(List.of(AchievementType.DRAGON_PARTICIPANT), grants.byPlayer().get(ALICE));
        assertFalse(has(ALICE, AchievementType.DRAGON_FINAL_BLOW));
        ProgressService none = new ProgressService(f.svc.ctx, () -> EnumSet.noneOf(AchievementType.class));
        assertTrue(ok(none.arrived(g.id(), ALICE, 5)).byPlayer().isEmpty());
    }

    @Test void veteranAfterFiveParticipations() {
        EndSpace g = f.newGroup(ALICE);
        for (int i = 0; i < 4; i++) {
            var gr = ok(f.svc.progress.recordDragonKill(kill(g.id(), BOB, Set.of(ALICE))));
            assertTrue(gr.byPlayer().getOrDefault(ALICE, List.of()).stream().noneMatch(a -> a == AchievementType.DRAGON_VETERAN));
        }
        assertFalse(has(ALICE, AchievementType.DRAGON_VETERAN));
        var fifth = ok(f.svc.progress.recordDragonKill(kill(g.id(), BOB, Set.of(ALICE))));
        assertTrue(fifth.byPlayer().get(ALICE).contains(AchievementType.DRAGON_VETERAN));
        assertTrue(has(ALICE, AchievementType.DRAGON_VETERAN));
    }

    @Test void expeditionistAtThreeMemberships() {
        EndSpace g = f.newGroup(ALICE);
        assertFalse(ok(f.svc.progress.arrived(g.id(), ALICE, 2)).byPlayer().get(ALICE).contains(AchievementType.EXPEDITIONIST));
        assertTrue(ok(f.svc.progress.arrived(g.id(), ALICE, 3)).byPlayer().get(ALICE).contains(AchievementType.EXPEDITIONIST));
    }

    @Test void chestAndShulkerCounters() {
        EndSpace g = f.newGroup(ALICE);
        for (int i = 0; i < 9; i++) ok(f.svc.progress.chestLooted(g.id(), ALICE));
        assertFalse(has(ALICE, AchievementType.TREASURE_HUNTER));
        assertTrue(has(ALICE, AchievementType.CITY_EXPLORER));
        ok(f.svc.progress.chestLooted(g.id(), ALICE));
        assertTrue(has(ALICE, AchievementType.TREASURE_HUNTER));
        for (int i = 0; i < 10; i++) ok(f.svc.progress.shulkerKilled(g.id(), ALICE));
        assertTrue(has(ALICE, AchievementType.SHELL_COLLECTOR));
        assertEquals(10, ok(f.svc.progress.player(g.id(), ALICE)).chestsLooted());
        assertEquals(10, ok(f.svc.progress.player(g.id(), ALICE)).shulkersKilled());
    }

    @Test void gatewaysOnlyIncrease() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.progress.gateways(g.id(), 3));
        ok(f.svc.progress.gateways(g.id(), 1));
        assertEquals(3, ok(f.svc.progress.space(g.id())).gatewaysActivated());
    }

    @Test void fightAbortedClosesActiveFight() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.progress.fightStarted(g.id()));
        ok(f.svc.progress.fightAborted(g.id()));
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_dragon_fights WHERE space_id=? AND outcome='ACTIVE'", g.id()));
        assertFalse(ok(f.svc.progress.space(g.id())).dragonDefeated());
    }
}
