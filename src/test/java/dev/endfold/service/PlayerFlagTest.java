package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The "entered the End through a portal" flag that unlocks direct teleporting (portals.direct-teleport). */
class PlayerFlagTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }

    @AfterEach void tearDown() { f.close(); }

    @Test void flagIsSetOnceAndCached() {
        String flag = ProfileService.FLAG_END_PORTAL_USED;
        assertFalse(f.svc.profiles.hasFlag(ALICE, flag));
        assertTrue(ok(f.svc.profiles.setFlag(ALICE, flag)), "first set is new");
        assertFalse(ok(f.svc.profiles.setFlag(ALICE, flag)), "second set is a no-op");
        assertTrue(f.svc.profiles.hasFlag(ALICE, flag));
        assertFalse(f.svc.profiles.hasFlag(BOB, flag), "flags are per player");
    }

    @Test void flagSurvivesRestart() {
        ok(f.svc.profiles.setFlag(BOB, ProfileService.FLAG_END_PORTAL_USED));
        f.restart();
        assertTrue(f.svc.profiles.hasFlag(BOB, ProfileService.FLAG_END_PORTAL_USED));
        assertFalse(f.svc.profiles.hasFlag(ALICE, ProfileService.FLAG_END_PORTAL_USED));
    }
}
