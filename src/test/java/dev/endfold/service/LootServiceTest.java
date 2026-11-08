package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static dev.endfold.service.ConcurrencySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.BlockPos;
import dev.endfold.model.EndSpace;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LootServiceTest {
    @TempDir Path dir;
    Fixture f;
    final BlockPos pos = new BlockPos(10, 64, -20);
    static final String TABLE = "minecraft:chests/end_city_treasure";

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    @Test void createOnceFreshThenStored() {
        EndSpace g = f.newGroup(ALICE);
        var first = ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {1, 2, 3}));
        assertTrue(first.fresh());
        var second = ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {9, 9, 9}));
        assertFalse(second.fresh());
        assertArrayEquals(new byte[] {1, 2, 3}, second.inventory().contents());
        assertEquals(27, second.inventory().size());
    }

    @Test void concurrentCreateStoresExactlyOneGeneration() throws Exception {
        EndSpace g = f.newGroup(ALICE);
        var rs = race(24, i -> f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {(byte) i, 42}).join());
        assertEquals(24, countOk(rs));
        long fresh = rs.stream().filter(r -> r.value().fresh()).count();
        assertEquals(1, fresh);
        byte[] winner = rs.stream().filter(r -> r.value().fresh()).findFirst().get().value().inventory().contents();
        for (var r : rs) assertArrayEquals(winner, r.value().inventory().contents());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_personal_inventories WHERE space_id=?", g.id()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_loot_claims WHERE space_id=?", g.id()));
    }

    @Test void differentPlayersAndSpacesAreIndependent() {
        EndSpace g = f.newGroup(ALICE, "Group One");
        EndSpace g2 = f.newGroup(ALICE, "Group Two");
        assertTrue(ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {1})).fresh());
        assertTrue(ok(f.svc.loot.createOnce(g.id(), BOB, pos, TABLE, 27, new byte[] {2})).fresh());
        assertTrue(ok(f.svc.loot.createOnce(g2.id(), ALICE, pos, TABLE, 27, new byte[] {3})).fresh());
        assertTrue(ok(f.svc.loot.createOnce(g.id(), ALICE, new BlockPos(1, 1, 1), TABLE, 27, new byte[] {4})).fresh());
        assertArrayEquals(new byte[] {2}, ok(f.svc.loot.load(g.id(), BOB, pos)).orElseThrow().contents());
        assertArrayEquals(new byte[] {1}, ok(f.svc.loot.load(g.id(), ALICE, pos)).orElseThrow().contents());
        assertTrue(ok(f.svc.loot.load(g.id(), CAROL, pos)).isEmpty());
        assertEquals(3, ok(f.svc.loot.inventoryCount(ALICE)));
    }

    @Test void saveIncrementsRevisionAndRejectsStale() {
        EndSpace g = f.newGroup(ALICE);
        var c = ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {1}));
        assertEquals(0, c.inventory().rev());
        assertEquals(1, ok(f.svc.loot.save(g.id(), ALICE, pos, new byte[] {2}, 0)));
        assertEquals(2, ok(f.svc.loot.save(g.id(), ALICE, pos, new byte[] {3}, 1)));
        fails(Failure.INVALID_VALUE, f.svc.loot.save(g.id(), ALICE, pos, new byte[] {99}, 0));
        var loaded = ok(f.svc.loot.load(g.id(), ALICE, pos)).orElseThrow();
        assertArrayEquals(new byte[] {3}, loaded.contents());
        assertEquals(2, loaded.rev());
    }

    @Test void concurrentSavesWithSameRevisionOnlyOneWins() throws Exception {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {1}));
        var rs = race(8, i -> f.svc.loot.save(g.id(), ALICE, pos, new byte[] {(byte) i}, 0).join());
        assertEquals(1, countOk(rs));
    }

    @Test void claimsPersistAcrossRestart() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {7, 7}));
        ok(f.svc.loot.save(g.id(), ALICE, pos, new byte[] {8, 8}, 0));
        f.restart();
        assertEquals(List.of(LootService.chestClaimKey(pos)), ok(f.svc.loot.claims(g.id(), ALICE)));
        var again = ok(f.svc.loot.createOnce(g.id(), ALICE, pos, TABLE, 27, new byte[] {5}));
        assertFalse(again.fresh());
        assertArrayEquals(new byte[] {8, 8}, again.inventory().contents());
        assertEquals(1, again.inventory().rev());
    }

    @Test void containerRegistrationIdempotent() {
        EndSpace g = f.newGroup(ALICE);
        assertTrue(ok(f.svc.loot.registerContainer(g.id(), pos, TABLE)));
        assertFalse(ok(f.svc.loot.registerContainer(g.id(), pos, TABLE)));
        assertEquals(1, ok(f.svc.loot.containers(g.id())).size());
    }
}
