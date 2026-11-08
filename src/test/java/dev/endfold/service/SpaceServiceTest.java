package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static dev.endfold.service.ConcurrencySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpaceServiceTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    @Test void createSolo() {
        EndSpace s = f.newSolo(ALICE);
        assertEquals(SpaceType.SOLO, s.type());
        assertEquals(SpaceStatus.ACTIVE, s.status());
        assertEquals(1, s.maxMembers());
        assertEquals(ALICE, s.ownerUuid());
        assertEquals(1, f.dbSpace(s.id()).maxMembers());
        assertEquals(Role.OWNER, f.svc.dir().role(s.id(), ALICE).orElseThrow());
        assertEquals(1, f.count("SELECT member_count FROM end_spaces WHERE id=?", s.id()));
    }

    @Test void createGroup() {
        EndSpace s = f.newGroup(ALICE);
        assertEquals(SpaceType.GROUP, s.type());
        assertEquals(10, s.maxMembers());
        assertFalse(s.isPublic());
        assertTrue(f.svc.dir().canEnter(s.id(), ALICE));
    }

    @Test void soloUniquePerPlayer() {
        f.newSolo(ALICE);
        fails(Failure.SOLO_ALREADY_EXISTS, f.svc.spaces.create(ALICE, "a", SpaceType.SOLO, "Another"));
        f.newSolo(BOB); // other players unaffected
    }

    @Test void concurrentSoloCreatesYieldExactlyOne() throws Exception {
        var rs = race(16, i -> f.svc.spaces.create(ALICE, "alice", SpaceType.SOLO, "Race Solo").join());
        assertEquals(1, countOk(rs));
        rs.stream().filter(r -> !r.isOk()).forEach(r -> assertEquals(Failure.SOLO_ALREADY_EXISTS, r.failure()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_spaces WHERE type='SOLO' AND owner_uuid=?", ALICE));
    }

    @Test void archivedSoloAllowsNewSolo() {
        EndSpace s = f.newSolo(ALICE);
        ok(f.svc.spaces.disband(ALICE, s.id()));
        f.newSolo(ALICE);
    }

    @Test void ownedGroupLimit() {
        f.newGroup(ALICE, "Group One");
        f.newGroup(ALICE, "Group Two");
        fails(Failure.OWNED_LIMIT_REACHED, f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, "Group Three"));
    }

    @Test void membershipLimit() {
        f.configure(s -> new ServiceSettings(s.soloEnabled(), s.groupEnabled(), s.groupDefaultMaxMembers(), s.groupHardMaxMembers(),
                s.maxOwnedGroups(), 2, s.inviteTtlMillis(), s.maxPendingInvitesPerSpace(), s.membersCanInvite(), s.publicEnabled(),
                s.ownerLeavePolicy(), s.nameMinLength(), s.nameMaxLength(), s.seedMode(), s.fixedSeed(), s.overworldSeed(),
                s.elytraPerShip(), s.elytraPerSpace(), s.elytraGlobalLimit(), s.elytraCooldownMillis()));
        f.newSolo(ALICE);
        f.newGroup(ALICE);
        fails(Failure.MEMBERSHIP_LIMIT_REACHED, f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, "Third One"));
    }

    @Test void disabledTypes() {
        f.configure(s -> new ServiceSettings(false, false, 10, 50, 2, 25, 1000, 20, false, true, s.ownerLeavePolicy(), 3, 24,
                s.seedMode(), 0, 0, 1, 0, 0, 0));
        fails(Failure.TYPE_DISABLED, f.svc.spaces.create(ALICE, "a", SpaceType.SOLO, "Solo Space"));
        fails(Failure.TYPE_DISABLED, f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, "Group Space"));
    }

    @Test void invalidNames() {
        for (String bad : List.of("ab", "a".repeat(25), "<red>Name", "Na§me", "  ", "<click:run_command:/op>", "x\u200by z")) {
            fails(Failure.INVALID_NAME, f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, bad));
        }
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_spaces"));
    }

    @Test void nameIsNormalised() {
        EndSpace s = ok(f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, "  Hello    World "));
        assertEquals("Hello World", s.name());
    }

    @Test void worldNameDeterministicAndUnique() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            EndSpace s = f.newSolo(player(i));
            assertEquals("endspace_" + s.id().toString().replace("-", "").substring(0, 8), s.worldName());
            assertEquals(EndSpace.worldNameFor(s.id()), s.worldName());
            assertTrue(s.worldName().matches("endspace_[0-9a-f]{8}"));
            assertTrue(names.add(s.worldName()));
        }
    }

    @Test void firstCreatedSpaceAutoSelectedOnly() {
        EndSpace solo = f.newSolo(ALICE);
        assertEquals(solo.id(), f.svc.dir().selectedId(ALICE).orElse(null));
        EndSpace group = f.newGroup(ALICE);
        assertEquals(solo.id(), f.svc.dir().selectedId(ALICE).orElse(null));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id=?", ALICE, solo.id()));
        assertNotEquals(solo.id(), group.id());
    }

    @Test void ensureSoloReturnsExisting() {
        EndSpace s = ok(f.svc.spaces.ensureSolo(ALICE, "a", "My Solo"));
        EndSpace again = ok(f.svc.spaces.ensureSolo(ALICE, "a", "My Solo"));
        assertEquals(s.id(), again.id());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_spaces"));
    }

    @Test void renamePermissions() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        fails(Failure.NO_PERMISSION, f.svc.spaces.rename(BOB, g.id(), "Bobs Name"));
        fails(Failure.NOT_MEMBER, f.svc.spaces.rename(DAVE, g.id(), "Daves Name"));
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        assertEquals("Bobs Name", ok(f.svc.spaces.rename(BOB, g.id(), "Bobs Name")).name());
        assertEquals("Bobs Name", f.svc.dir().space(g.id()).orElseThrow().name());
        fails(Failure.INVALID_NAME, f.svc.spaces.rename(BOB, g.id(), "<b>x"));
        assertEquals("Bobs Name", f.dbSpace(g.id()).name());
    }

    @Test void visibilityPermissions() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        fails(Failure.NO_PERMISSION, f.svc.spaces.setVisibility(BOB, g.id(), true, JoinMode.OPEN));
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        EndSpace u = ok(f.svc.spaces.setVisibility(BOB, g.id(), true, JoinMode.OPEN));
        assertTrue(u.isPublic());
        assertEquals(JoinMode.OPEN, f.svc.dir().space(g.id()).orElseThrow().joinMode());
    }

    @Test void soloCannotBePublic() {
        EndSpace s = f.newSolo(ALICE);
        fails(Failure.SOLO_NOT_SHAREABLE, f.svc.spaces.setVisibility(ALICE, s.id(), true, JoinMode.OPEN));
    }

    @Test void publicDisabledBlocksPublic() {
        EndSpace g = f.newGroup(ALICE);
        f.configure(s -> new ServiceSettings(true, true, 10, 50, 2, 25, 1000, 20, false, false, s.ownerLeavePolicy(), 3, 24,
                s.seedMode(), 0, 0, 1, 0, 0, 0));
        fails(Failure.PUBLIC_DISABLED, f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.OPEN));
    }

    @Test void setMaxMembersBounds() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        fails(Failure.INVALID_VALUE, f.svc.spaces.setMaxMembers(ALICE, g.id(), 2)); // below current count
        fails(Failure.INVALID_VALUE, f.svc.spaces.setMaxMembers(ALICE, g.id(), 51)); // above hard max
        fails(Failure.INVALID_VALUE, f.svc.spaces.setMaxMembers(ALICE, g.id(), 1));
        assertEquals(3, ok(f.svc.spaces.setMaxMembers(ALICE, g.id(), 3)).maxMembers());
        assertEquals(50, ok(f.svc.spaces.setMaxMembers(ALICE, g.id(), 50)).maxMembers());
        fails(Failure.NO_PERMISSION, f.svc.spaces.setMaxMembers(BOB, g.id(), 5));
        EndSpace solo = f.newSolo(DAVE);
        fails(Failure.SOLO_NOT_SHAREABLE, f.svc.spaces.setMaxMembers(DAVE, solo.id(), 5));
    }

    @Test void setMaxMembersTwoOnFreshGroup() {
        EndSpace g = f.newGroup(ALICE);
        assertEquals(2, ok(f.svc.spaces.setMaxMembers(ALICE, g.id(), 2)).maxMembers());
    }

    @Test void disbandArchivesWithoutDeletingAnything() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        ok(f.svc.profiles.select(BOB, g.id()));
        ok(f.svc.profiles.select(CAROL, g.id()));
        ok(f.svc.members.invite(ALICE, g.id(), DAVE, "dave"));
        ok(f.svc.progress.recordDragonKill(new ProgressService.FightOutcome(g.id(), ALICE, java.util.Map.of(ALICE, 10.0, BOB, 5.0), Set.of(ALICE, BOB), 0)));
        long members = f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=?", g.id());
        long progress = f.count("SELECT COUNT(*) FROM end_player_progress WHERE space_id=?", g.id());
        assertEquals(3, members);
        assertEquals(2, progress);

        fails(Failure.NO_PERMISSION, f.svc.spaces.disband(BOB, g.id()));
        EndSpace archived = ok(f.svc.spaces.disband(ALICE, g.id()));

        assertEquals(SpaceStatus.ARCHIVED, archived.status());
        assertEquals(SpaceStatus.ARCHIVED, f.dbSpace(g.id()).status());
        assertEquals(members, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=?", g.id()));
        assertEquals(progress, f.count("SELECT COUNT(*) FROM end_player_progress WHERE space_id=?", g.id()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_progress WHERE space_id=?", g.id()));
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_invitations WHERE space_id=? AND status='PENDING'", g.id()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_invitations WHERE space_id=? AND status='REVOKED'", g.id()));
        for (UUID p : List.of(ALICE, BOB, CAROL)) {
            assertTrue(f.svc.dir().selectedId(p).isEmpty());
            assertEquals(0, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id IS NOT NULL", p));
            assertFalse(f.svc.dir().canEnter(g.id(), p));
        }
        fails(Failure.SPACE_NOT_ACTIVE, f.svc.spaces.disband(ALICE, g.id()));
    }

    @Test void adminRestoreFromArchive() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.disband(ALICE, g.id()));
        ok(f.svc.spaces.adminSetStatus(ALICE, g.id(), SpaceStatus.ACTIVE, "restore"));
        assertTrue(f.svc.dir().canEnter(g.id(), ALICE));
    }

    @Test void adminDeleteMarksDeletedAndRemovesFromDirectory() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.adminMarkDeleted(ALICE, g.id()));
        assertEquals(SpaceStatus.DELETED, f.dbSpace(g.id()).status());
        assertTrue(f.svc.dir().space(g.id()).isEmpty());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=?", g.id()));
    }

    @Test void markWorldGeneratedAndTouch() {
        EndSpace g = f.newGroup(ALICE);
        UUID w = UUID.randomUUID();
        assertEquals(w, ok(f.svc.spaces.markWorldGenerated(g.id(), w)).worldUuid());
        assertEquals(w, f.dbSpace(g.id()).worldUuid());
        f.clock.advance(5000);
        ok(f.svc.spaces.touch(g.id()));
        assertEquals(f.clock.millis(), f.svc.dir().space(g.id()).orElseThrow().lastActiveAt());
    }

    @Test void unknownSpaceNotFound() {
        fails(Failure.NOT_FOUND, f.svc.spaces.rename(ALICE, UUID.randomUUID(), "Whatever"));
    }

    @Test void sqlInjectionNameRejectedAndNoDamage() {
        fails(Failure.INVALID_NAME, f.svc.spaces.create(ALICE, "a", SpaceType.GROUP, "x'); DROP TABLE end_spaces;--"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_spaces"));
    }
}
