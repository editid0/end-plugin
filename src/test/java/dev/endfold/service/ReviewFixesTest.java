package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Invitation;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for findings of the implementation review. */
class ReviewFixesTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }

    @AfterEach void tearDown() { f.close(); }

    @Test void invitationFromKickedMemberCannotBeAccepted() {
        f.configure(s -> new ServiceSettings(s.soloEnabled(), s.groupEnabled(), s.groupDefaultMaxMembers(), s.groupHardMaxMembers(),
                s.maxOwnedGroups(), s.maxMembershipsPerPlayer(), s.inviteTtlMillis(), s.maxPendingInvitesPerSpace(), true, s.publicEnabled(),
                s.ownerLeavePolicy(), s.nameMinLength(), s.nameMaxLength(), s.seedMode(), s.fixedSeed(), s.overworldSeed(), s.elytraPerShip(),
                s.elytraPerSpace(), s.elytraGlobalLimit(), s.elytraCooldownMillis()));
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        Invitation inv = ok(f.svc.members.invite(BOB, g.id(), CAROL, "Carol"));
        ok(f.svc.members.kick(ALICE, g.id(), BOB));
        fails(Failure.NOT_FOUND, f.svc.members.acceptInvite(CAROL, "Carol", inv.id()));
        assertFalse(f.svc.dir().isMember(g.id(), CAROL));
    }

    @Test void invitationFromDemotedAdminCannotBeAccepted() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        Invitation inv = ok(f.svc.members.invite(BOB, g.id(), CAROL, "Carol"));
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.MEMBER));
        fails(Failure.INVITE_EXPIRED, f.svc.members.acceptInvite(CAROL, "Carol", inv.id()));
    }

    @Test void deletedSpaceStatusIsFinal() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.adminMarkDeleted(null, g.id()));
        fails(Failure.SPACE_NOT_ACTIVE, f.svc.spaces.adminSetStatus(null, g.id(), SpaceStatus.BROKEN, "late load failure"));
        assertTrue(f.svc.dir().space(g.id()).isEmpty());
    }
}
