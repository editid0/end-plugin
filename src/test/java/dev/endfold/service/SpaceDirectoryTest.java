package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpaceDirectoryTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    @Test void canEnterRules() {
        EndSpace g = f.newGroup(ALICE);
        SpaceDirectory d = f.svc.dir();
        assertTrue(d.canEnter(g.id(), ALICE));
        assertFalse(d.canEnter(g.id(), BOB));
        assertFalse(d.canEnter(UUID.randomUUID(), ALICE));
        f.join(g, ALICE, BOB);
        assertTrue(d.canEnter(g.id(), BOB));
        ok(f.svc.spaces.disband(ALICE, g.id()));
        assertFalse(d.canEnter(g.id(), ALICE));
        assertFalse(d.canEnter(g.id(), BOB));
    }

    @Test void brokenSpaceIsNotEnterable() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.adminSetStatus(ALICE, g.id(), SpaceStatus.BROKEN, "test"));
        assertFalse(f.svc.dir().canEnter(g.id(), ALICE));
    }

    @Test void resolveByShortIdFullUuidAndWorldName() {
        EndSpace g = f.newGroup(ALICE);
        SpaceDirectory d = f.svc.dir();
        assertEquals(g.id(), d.resolve(g.shortId()).orElseThrow().id());
        assertEquals(g.id(), d.resolve(g.id().toString()).orElseThrow().id());
        assertEquals(g.id(), d.resolve(g.worldName()).orElseThrow().id());
        assertEquals(g.id(), d.resolve(g.worldName().toUpperCase()).orElseThrow().id());
        assertEquals(g.id(), d.resolve("  " + g.shortId() + " ").orElseThrow().id());
        assertEquals(g.id(), d.spaceByWorldName(g.worldName()).orElseThrow().id());
        assertTrue(d.resolve("nothere").isEmpty());
        assertTrue(d.resolve("").isEmpty());
        assertTrue(d.resolve(null).isEmpty());
        assertTrue(d.resolve(UUID.randomUUID().toString()).isEmpty());
    }

    @Test void cacheReflectsDbAfterOperationsAndRestart() {
        EndSpace solo = f.newSolo(ALICE);
        EndSpace g = f.newGroup(BOB);
        f.join(g, BOB, ALICE);
        f.join(g, BOB, CAROL);
        ok(f.svc.members.setRole(BOB, g.id(), CAROL, Role.ADMIN));
        ok(f.svc.members.kick(BOB, g.id(), ALICE));
        ok(f.svc.spaces.rename(BOB, g.id(), "Renamed"));
        ok(f.svc.members.transferOwnership(BOB, g.id(), CAROL));
        ok(f.svc.profiles.select(CAROL, g.id()));

        SpaceDirectory live = f.svc.dir();
        f.restart(); // fresh directory loaded purely from the database
        SpaceDirectory loaded = f.svc.dir();
        assertNotSame(live, loaded);
        assertEquals(live.allSpaces().stream().sorted(Comparator.comparing(EndSpace::id)).toList(),
                loaded.allSpaces().stream().sorted(Comparator.comparing(EndSpace::id)).toList());
        for (UUID sp : List.of(solo.id(), g.id())) {
            assertEquals(live.members(sp), loaded.members(sp));
            assertEquals(live.memberCount(sp), loaded.memberCount(sp));
        }
        for (UUID p : List.of(ALICE, BOB, CAROL)) {
            assertEquals(live.spacesOf(p), loaded.spacesOf(p));
            assertEquals(live.selectedId(p), loaded.selectedId(p));
        }
    }

    @Test void removeSpaceClearsSelectionsAndMembership() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.profiles.select(BOB, g.id()));
        f.svc.dir().removeSpace(g.id());
        assertTrue(f.svc.dir().space(g.id()).isEmpty());
        assertTrue(f.svc.dir().spaceByWorldName(g.worldName()).isEmpty());
        assertTrue(f.svc.dir().selectedId(BOB).isEmpty());
        assertTrue(f.svc.dir().selectedId(ALICE).isEmpty());
        assertTrue(f.svc.dir().spacesOf(BOB).isEmpty());
        assertEquals(0, f.svc.dir().memberCount(g.id()));
    }

    @Test void spacesOfOrdersSoloFirst() {
        EndSpace g = f.newGroup(ALICE, "Aaa Group");
        EndSpace s = f.newSolo(ALICE);
        List<EndSpace> list = f.svc.dir().spacesOf(ALICE);
        assertEquals(List.of(s.id(), g.id()), list.stream().map(EndSpace::id).toList());
    }

    @Test void membersSortedByRoleThenJoinTime() {
        EndSpace g = f.newGroup(ALICE);
        f.clock.advance(10);
        f.join(g, ALICE, BOB);
        f.clock.advance(10);
        f.join(g, ALICE, CAROL);
        ok(f.svc.members.setRole(ALICE, g.id(), CAROL, Role.ADMIN));
        assertEquals(List.of(ALICE, CAROL, BOB), f.svc.dir().members(g.id()).stream().map(Membership::playerUuid).toList());
    }

    @Test void profileByNameCaseInsensitive() {
        f.newSolo(ALICE);
        assertEquals(ALICE, f.svc.dir().profileByName("P" + ALICE.getLeastSignificantBits()).orElseThrow().playerUuid());
    }
}
