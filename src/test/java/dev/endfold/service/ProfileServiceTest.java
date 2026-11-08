package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileServiceTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    private ReturnLocation loc(UUID p, UUID s, double x) { return new ReturnLocation(p, s, UUID.randomUUID(), "world", x, 1, 2, 3f, 4f); }

    @Test void selectNonMember() {
        EndSpace g = f.newGroup(ALICE);
        fails(Failure.NOT_MEMBER, f.svc.profiles.select(BOB, g.id()));
        fails(Failure.NOT_FOUND, f.svc.profiles.select(BOB, UUID.randomUUID()));
    }

    @Test void selectArchived() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.spaces.disband(ALICE, g.id()));
        fails(Failure.SPACE_NOT_ACTIVE, f.svc.profiles.select(BOB, g.id()));
    }

    @Test void selectUpdatesDirectoryAndDb() {
        EndSpace g = f.newGroup(ALICE);
        EndSpace s = f.newSolo(ALICE);
        ok(f.svc.profiles.select(ALICE, s.id()));
        assertEquals(s.id(), f.svc.profiles.selectedSpace(ALICE).orElseThrow().id());
        ok(f.svc.profiles.select(ALICE, g.id()));
        assertEquals(g.id(), f.svc.profiles.selectedSpace(ALICE).orElseThrow().id());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id=?", ALICE, g.id()));
        ok(f.svc.profiles.clearSelection(ALICE));
        assertTrue(f.svc.profiles.selectedSpace(ALICE).isEmpty());
    }

    @Test void seenRepairsSelectionOfRemovedMembership() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.profiles.select(BOB, g.id()));
        // simulate an out-of-band removal (e.g. manual DB edit) that bypassed the service
        f.exec("DELETE FROM end_space_members WHERE space_id=? AND player_uuid=?", g.id(), BOB);
        PlayerProfile p = ok(f.svc.profiles.seen(BOB, "bob"));
        assertNull(p.selectedSpaceId());
        assertTrue(f.svc.dir().selectedId(BOB).isEmpty());
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id IS NOT NULL", BOB));
    }

    @Test void seenKeepsValidSelectionAndUpdatesName() {
        EndSpace s = f.newSolo(ALICE);
        f.clock.advance(100);
        PlayerProfile p = ok(f.svc.profiles.seen(ALICE, "AliceRenamed"));
        assertEquals(s.id(), p.selectedSpaceId());
        assertEquals("AliceRenamed", f.svc.dir().lastKnownName(ALICE).orElseThrow());
    }

    @Test void seenRepairsSelectionOfNonActiveSpace() {
        EndSpace g = f.newGroup(ALICE);
        f.exec("UPDATE end_spaces SET status='BROKEN' WHERE id=?", g.id());
        PlayerProfile p = ok(f.svc.profiles.seen(ALICE, "alice"));
        assertNull(p.selectedSpaceId());
    }

    @Test void seenCreatesProfileForNewPlayer() {
        PlayerProfile p = ok(f.svc.profiles.seen(DAVE, "dave"));
        assertEquals("dave", p.lastName());
        assertNull(p.selectedSpaceId());
        assertTrue(f.svc.dir().profile(DAVE).isPresent());
    }

    @Test void sessionIsOneShot() {
        EndSpace g = f.newGroup(ALICE);
        assertTrue(ok(f.svc.profiles.takeSession(ALICE)).isEmpty());
        ok(f.svc.profiles.saveSession(loc(ALICE, g.id(), 5)));
        ok(f.svc.profiles.saveSession(loc(ALICE, g.id(), 6))); // overwrite
        var s = ok(f.svc.profiles.takeSession(ALICE));
        assertEquals(6, s.orElseThrow().x());
        assertEquals(g.id(), s.get().spaceId());
        assertTrue(ok(f.svc.profiles.takeSession(ALICE)).isEmpty());
    }

    @Test void returnLocationsPerPlayerAndSpace() {
        EndSpace g1 = f.newGroup(ALICE, "Group One");
        EndSpace g2 = f.newGroup(ALICE, "Group Two");
        ok(f.svc.profiles.saveReturn(loc(ALICE, g1.id(), 1)));
        ok(f.svc.profiles.saveReturn(loc(ALICE, g2.id(), 2)));
        ok(f.svc.profiles.saveReturn(loc(BOB, g1.id(), 3)));
        assertEquals(1, ok(f.svc.profiles.findReturn(ALICE, g1.id())).orElseThrow().x());
        assertEquals(2, ok(f.svc.profiles.findReturn(ALICE, g2.id())).orElseThrow().x());
        assertEquals(3, ok(f.svc.profiles.findReturn(BOB, g1.id())).orElseThrow().x());
        assertTrue(ok(f.svc.profiles.findReturn(BOB, g2.id())).isEmpty());
        f.restart();
        assertEquals(2, ok(f.svc.profiles.findReturn(ALICE, g2.id())).orElseThrow().x());
    }
}
