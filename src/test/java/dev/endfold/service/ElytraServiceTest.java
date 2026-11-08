package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static dev.endfold.service.ConcurrencySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.EndSpace;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElytraServiceTest {
    @TempDir Path dir;
    Fixture f;
    static final byte[] ITEM = {1, 2, 3};

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    private void limits(int perSpace, int global, long cooldown) {
        f.configure(s -> new ServiceSettings(true, true, 10, 50, 2, 25, s.inviteTtlMillis(), 20, false, true, s.ownerLeavePolicy(),
                3, 24, s.seedMode(), 0, 0, 1, perSpace, global, cooldown));
    }

    @Test void claimOncePerShip() {
        EndSpace g = f.newGroup(ALICE);
        var d = ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        assertEquals("ELYTRA", d.kind());
        fails(Failure.ALREADY_CLAIMED, f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        assertTrue(ok(f.svc.elytra.hasClaim(g.id(), ALICE, "ship-1")));
        assertFalse(ok(f.svc.elytra.hasClaim(g.id(), ALICE, "ship-2")));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_pending_deliveries"));
        assertEquals(1, ok(f.svc.progress.player(g.id(), ALICE)).elytraClaimed());
    }

    @Test void concurrentClaimsExactlyOne() throws Exception {
        EndSpace g = f.newGroup(ALICE);
        var rs = race(16, i -> f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM).join());
        assertEquals(1, countOk(rs));
        rs.stream().filter(r -> !r.isOk()).forEach(r -> assertEquals(Failure.ALREADY_CLAIMED, r.failure()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_elytra_claims"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_pending_deliveries"));
    }

    @Test void differentPlayersSameShipBothSucceed() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        ok(f.svc.elytra.claim(g.id(), BOB, "ship-1", UUID.randomUUID(), ITEM));
        assertEquals(1, ok(f.svc.elytra.pending(ALICE)).size());
        assertEquals(1, ok(f.svc.elytra.pending(BOB)).size());
    }

    @Test void perSpaceLimit() {
        limits(1, 0, 0);
        EndSpace g1 = f.newGroup(ALICE, "Group One");
        EndSpace g2 = f.newGroup(ALICE, "Group Two");
        ok(f.svc.elytra.claim(g1.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        fails(Failure.CLAIM_LIMIT_REACHED, f.svc.elytra.claim(g1.id(), ALICE, "ship-2", UUID.randomUUID(), ITEM));
        ok(f.svc.elytra.claim(g2.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM)); // other space not affected
    }

    @Test void globalLimit() {
        limits(0, 2, 0);
        EndSpace g1 = f.newGroup(ALICE, "Group One");
        EndSpace g2 = f.newGroup(ALICE, "Group Two");
        ok(f.svc.elytra.claim(g1.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        ok(f.svc.elytra.claim(g2.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        fails(Failure.CLAIM_LIMIT_REACHED, f.svc.elytra.claim(g1.id(), ALICE, "ship-2", UUID.randomUUID(), ITEM));
        assertEquals(2, ok(f.svc.elytra.totalClaims(ALICE)));
        ok(f.svc.elytra.claim(g1.id(), BOB, "ship-2", UUID.randomUUID(), ITEM));
    }

    @Test void cooldown() {
        limits(0, 0, 60_000);
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        fails(Failure.CLAIM_COOLDOWN, f.svc.elytra.claim(g.id(), ALICE, "ship-2", UUID.randomUUID(), ITEM));
        f.clock.advance(59_999);
        fails(Failure.CLAIM_COOLDOWN, f.svc.elytra.claim(g.id(), ALICE, "ship-2", UUID.randomUUID(), ITEM));
        f.clock.advance(1);
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-2", UUID.randomUUID(), ITEM));
    }

    @Test void claimsSurviveRestart() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        f.restart();
        fails(Failure.ALREADY_CLAIMED, f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
        assertTrue(ok(f.svc.elytra.hasClaim(g.id(), ALICE, "ship-1")));
    }

    @Test void pendingUntilDeliveredAndMarkIsIdempotent() {
        EndSpace g = f.newGroup(ALICE);
        UUID delivery = UUID.randomUUID();
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", delivery, ITEM));
        var pending = ok(f.svc.elytra.pending(ALICE));
        assertEquals(1, pending.size());
        assertEquals(delivery, pending.getFirst().deliveryId());
        assertArrayEquals(ITEM, pending.getFirst().itemBytes());
        assertTrue(ok(f.svc.elytra.markDelivered(delivery)));
        assertFalse(ok(f.svc.elytra.markDelivered(delivery)));
        assertTrue(ok(f.svc.elytra.pending(ALICE)).isEmpty());
    }

    @Test void crashAfterClaimBeforeDeliveryRedelivers() {
        EndSpace g = f.newGroup(ALICE);
        UUID delivery = UUID.randomUUID();
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", delivery, ITEM));
        // "crash": delivery never marked; restart the database
        f.restart();
        var pending = ok(f.svc.elytra.pending(ALICE));
        assertEquals(1, pending.size());
        assertEquals(delivery, pending.getFirst().deliveryId());
        assertTrue(ok(f.svc.elytra.markDelivered(delivery)));
        f.restart();
        assertTrue(ok(f.svc.elytra.pending(ALICE)).isEmpty());
        fails(Failure.ALREADY_CLAIMED, f.svc.elytra.claim(g.id(), ALICE, "ship-1", UUID.randomUUID(), ITEM));
    }

    @Test void failedClaimLeavesNoPartialRows() {
        EndSpace g = f.newGroup(ALICE);
        UUID delivery = UUID.randomUUID();
        ok(f.svc.elytra.claim(g.id(), ALICE, "ship-1", delivery, ITEM));
        // same delivery id for a different ship violates UNIQUE(delivery_id): whole tx must roll back
        var r = f.svc.elytra.claim(g.id(), ALICE, "ship-2", delivery, ITEM).join();
        assertFalse(r.isOk());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_elytra_claims"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_pending_deliveries"));
    }
}
