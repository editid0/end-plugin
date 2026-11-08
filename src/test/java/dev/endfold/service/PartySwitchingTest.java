package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The critical scenario: switching between solo and group spaces never destroys or leaks anything. */
class PartySwitchingTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    private ReturnLocation loc(UUID player, UUID space, double x) {
        return new ReturnLocation(player, space, UUID.randomUUID(), "world", x, 70, -x, 90f, 10f);
    }

    @Test void aliceSwitchesBetweenSoloAndBobsGroup() {
        EndSpace aliceSolo = f.newSolo(ALICE);
        EndSpace bobGroup = f.newGroup(BOB, "Bobs Group");
        EndSpace bobSolo = f.newSolo(BOB);
        // Bob's auto-selection was the first space he created.
        UUID bobSelection = bobGroup.id();
        assertEquals(bobSelection, f.svc.dir().selectedId(BOB).orElse(null));

        // progress in both spaces before switching
        ok(f.svc.progress.recordDragonKill(new ProgressService.FightOutcome(bobGroup.id(), BOB, Map.of(BOB, 50.0), Set.of(BOB), 1)));
        ok(f.svc.progress.chestLooted(aliceSolo.id(), ALICE));
        long progressBefore = f.count("SELECT COUNT(*) FROM end_player_progress");
        long soloKillsBefore = f.count("SELECT dragon_kills FROM end_space_progress WHERE space_id=?", bobGroup.id());

        // return locations per (player, space)
        ok(f.svc.profiles.saveReturn(loc(ALICE, aliceSolo.id(), 10)));

        f.join(bobGroup, BOB, ALICE);
        assertEquals(aliceSolo.id(), f.svc.dir().selectedId(ALICE).orElse(null)); // joining never changes selection
        ok(f.svc.profiles.select(ALICE, bobGroup.id()));
        assertEquals(bobGroup.id(), f.svc.dir().selectedId(ALICE).orElse(null));
        assertEquals(bobSelection, f.svc.dir().selectedId(BOB).orElse(null));
        ok(f.svc.profiles.saveReturn(loc(ALICE, bobGroup.id(), 99)));

        ok(f.svc.profiles.select(ALICE, aliceSolo.id()));
        assertEquals(aliceSolo.id(), f.svc.dir().selectedId(ALICE).orElse(null));
        assertEquals(bobSelection, f.svc.dir().selectedId(BOB).orElse(null));
        assertEquals(bobSelection, f.dbSpace(bobGroup.id()).id());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id=?", BOB, bobSelection));

        // memberships never removed
        assertTrue(f.svc.dir().isMember(aliceSolo.id(), ALICE));
        assertTrue(f.svc.dir().isMember(bobGroup.id(), ALICE));
        assertTrue(f.svc.dir().isMember(bobGroup.id(), BOB));
        assertTrue(f.svc.dir().isMember(bobSolo.id(), BOB));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=? AND player_uuid=?", bobGroup.id(), ALICE));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=? AND player_uuid=?", aliceSolo.id(), ALICE));

        // return locations independent
        ReturnLocation a = ok(f.svc.profiles.findReturn(ALICE, aliceSolo.id())).orElseThrow();
        ReturnLocation b = ok(f.svc.profiles.findReturn(ALICE, bobGroup.id())).orElseThrow();
        assertEquals(10, a.x());
        assertEquals(99, b.x());
        assertTrue(ok(f.svc.profiles.findReturn(BOB, bobGroup.id())).isEmpty());
        // overwriting one leaves the other untouched
        ok(f.svc.profiles.saveReturn(loc(ALICE, bobGroup.id(), 123)));
        assertEquals(10, ok(f.svc.profiles.findReturn(ALICE, aliceSolo.id())).orElseThrow().x());
        assertEquals(123, ok(f.svc.profiles.findReturn(ALICE, bobGroup.id())).orElseThrow().x());

        // progress untouched by switching/joining
        assertEquals(progressBefore, f.count("SELECT COUNT(*) FROM end_player_progress"));
        assertEquals(soloKillsBefore, f.count("SELECT dragon_kills FROM end_space_progress WHERE space_id=?", bobGroup.id()));
        assertEquals(0, ok(f.svc.progress.player(bobGroup.id(), ALICE)).dragonKills());
        assertEquals(1, ok(f.svc.progress.player(aliceSolo.id(), ALICE)).chestsLooted());

        // survives a restart
        f.restart();
        assertEquals(aliceSolo.id(), f.svc.dir().selectedId(ALICE).orElse(null));
        assertEquals(bobSelection, f.svc.dir().selectedId(BOB).orElse(null));
        assertTrue(f.svc.dir().isMember(bobGroup.id(), ALICE));
    }

    @Test void leavingGroupDoesNotTouchSoloOrOthersSelection() {
        EndSpace aliceSolo = f.newSolo(ALICE);
        EndSpace bobGroup = f.newGroup(BOB);
        f.join(bobGroup, BOB, ALICE);
        ok(f.svc.profiles.select(ALICE, bobGroup.id()));
        ok(f.svc.members.leave(ALICE, bobGroup.id()));
        assertTrue(f.svc.dir().selectedId(ALICE).isEmpty());
        assertTrue(f.svc.dir().isMember(aliceSolo.id(), ALICE));
        assertEquals(bobGroup.id(), f.svc.dir().selectedId(BOB).orElse(null));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=?", aliceSolo.id()));
    }
}
