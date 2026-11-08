package dev.endfold.service;

import static dev.endfold.Fixture.*;
import static dev.endfold.service.ConcurrencySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.Fixture;
import dev.endfold.model.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MembershipServiceTest {
    @TempDir Path dir;
    Fixture f;

    @BeforeEach void setUp() { f = new Fixture(dir); }
    @AfterEach void tearDown() { f.close(); }

    private void settings(int defaultMax, ServiceSettings.OwnerLeavePolicy policy, boolean membersInvite) {
        f.configure(s -> new ServiceSettings(true, true, defaultMax, 50, 2, 25, s.inviteTtlMillis(), 20, membersInvite, true, policy,
                3, 24, s.seedMode(), 0, 0, 1, 0, 0, 0));
    }

    private long memberCount(UUID space) { return f.count("SELECT member_count FROM end_spaces WHERE id=?", space); }
    private long memberRows(UUID space) { return f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=?", space); }

    // ---------------------------------------------------------------- invitations

    @Test void inviteFlowPermissions() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        fails(Failure.NO_PERMISSION, f.svc.members.invite(BOB, g.id(), CAROL, "carol")); // members can't invite by default
        fails(Failure.NOT_MEMBER, f.svc.members.invite(DAVE, g.id(), CAROL, "carol"));
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        ok(f.svc.members.invite(BOB, g.id(), CAROL, "carol"));
    }

    @Test void membersCanInviteWhenConfigured() {
        settings(10, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, true);
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.members.invite(BOB, g.id(), CAROL, "carol"));
    }

    @Test void inviteEdgeCases() {
        EndSpace g = f.newGroup(ALICE);
        fails(Failure.CANNOT_TARGET_SELF, f.svc.members.invite(ALICE, g.id(), ALICE, "a"));
        ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        fails(Failure.ALREADY_INVITED, f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        f.join(g, ALICE, CAROL);
        fails(Failure.ALREADY_MEMBER, f.svc.members.invite(ALICE, g.id(), CAROL, "carol"));
    }

    @Test void soloNotShareable() {
        EndSpace s = f.newSolo(ALICE);
        fails(Failure.SOLO_NOT_SHAREABLE, f.svc.members.invite(ALICE, s.id(), BOB, "bob"));
        fails(Failure.SOLO_NOT_SHAREABLE, f.svc.members.transferOwnership(ALICE, s.id(), BOB));
    }

    @Test void inviteToFullSpaceRejected() {
        settings(2, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        fails(Failure.SPACE_FULL, f.svc.members.invite(ALICE, g.id(), CAROL, "carol"));
    }

    @Test void acceptByWrongPlayer() {
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        fails(Failure.INVITE_NOT_FOR_YOU, f.svc.members.acceptInvite(CAROL, "carol", inv.id()));
        assertEquals(1, memberRows(g.id()));
    }

    @Test void acceptExpired() {
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        f.clock.advance(inv.expiresAt() - inv.createdAt() - 1);
        assertEquals(1, ok(f.svc.members.incoming(BOB)).size());
        f.clock.advance(1);
        assertEquals(0, ok(f.svc.members.incoming(BOB)).size());
        fails(Failure.INVITE_EXPIRED, f.svc.members.acceptInvite(BOB, "bob", inv.id()));
        assertFalse(f.svc.dir().isMember(g.id(), BOB));
        assertEquals(1, ok(f.svc.members.expireInvitations()));
        // a new invite is possible after expiry
        ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
    }

    @Test void doubleAcceptFromTwoThreadsGivesOneMembership() throws Exception {
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        var rs = race(2, i -> f.svc.members.acceptInvite(BOB, "bob", inv.id()).join());
        assertEquals(1, countOk(rs));
        assertEquals(2, memberRows(g.id()));
        assertEquals(2, memberCount(g.id()));
    }

    @Test void acceptWhenSpaceFullKeepsCount() {
        settings(2, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        Invitation inv2 = ok(f.svc.members.invite(ALICE, g.id(), CAROL, "carol"));
        ok(f.svc.members.acceptInvite(BOB, "bob", inv.id()));
        fails(Failure.SPACE_FULL, f.svc.members.acceptInvite(CAROL, "carol", inv2.id()));
        assertEquals(2, memberCount(g.id()));
        assertEquals(2, memberRows(g.id()));
        assertFalse(f.svc.dir().isMember(g.id(), CAROL));
    }

    @Test void declineAndRevoke() {
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        fails(Failure.INVITE_NOT_FOR_YOU, f.svc.members.declineInvite(CAROL, inv.id()));
        ok(f.svc.members.declineInvite(BOB, inv.id()));
        fails(Failure.NOT_FOUND, f.svc.members.declineInvite(BOB, inv.id()));
        fails(Failure.NOT_FOUND, f.svc.members.acceptInvite(BOB, "bob", inv.id()));

        Invitation inv2 = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        fails(Failure.NOT_MEMBER, f.svc.members.revokeInvite(CAROL, inv2.id()));
        ok(f.svc.members.revokeInvite(ALICE, inv2.id()));
        fails(Failure.NOT_FOUND, f.svc.members.acceptInvite(BOB, "bob", inv2.id()));
        assertFalse(f.svc.dir().isMember(g.id(), BOB));
    }

    @Test void findInvitationByShortId() {
        EndSpace g = f.newGroup(ALICE);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        assertEquals(inv.id(), ok(f.svc.members.findInvitation(BOB, inv.shortId())).id());
        assertEquals(inv.id(), ok(f.svc.members.findInvitation(BOB, inv.id().toString())).id());
        fails(Failure.NOT_FOUND, f.svc.members.findInvitation(CAROL, inv.shortId()));
    }

    // ---------------------------------------------------------------- kick / roles / transfer

    @Test void kickRoleHierarchy() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        f.join(g, ALICE, DAVE);
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        ok(f.svc.members.setRole(ALICE, g.id(), CAROL, Role.ADMIN));
        fails(Failure.NO_PERMISSION, f.svc.members.kick(DAVE, g.id(), BOB));      // member can't kick admin
        fails(Failure.NO_PERMISSION, f.svc.members.kick(BOB, g.id(), CAROL));     // admin can't kick admin
        fails(Failure.CANNOT_TARGET_OWNER, f.svc.members.kick(BOB, g.id(), ALICE));
        fails(Failure.CANNOT_TARGET_SELF, f.svc.members.kick(BOB, g.id(), BOB));
        ok(f.svc.members.kick(BOB, g.id(), DAVE));                                  // admin can kick member
        assertFalse(f.svc.dir().isMember(g.id(), DAVE));
        assertEquals(3, memberCount(g.id()));
        ok(f.svc.members.kick(ALICE, g.id(), CAROL));                               // owner can kick admin
        assertEquals(2, memberRows(g.id()));
    }

    @Test void memberCannotKickMember() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        fails(Failure.NO_PERMISSION, f.svc.members.kick(BOB, g.id(), CAROL));
    }

    @Test void kickClearsSelectionOnlyIfPointedThere() {
        EndSpace solo = f.newSolo(BOB);
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        ok(f.svc.profiles.select(BOB, g.id()));
        var removal = ok(f.svc.members.kick(ALICE, g.id(), BOB));
        assertTrue(removal.selectionCleared());
        assertTrue(f.svc.dir().selectedId(BOB).isEmpty());
        assertNotNull(solo);
    }

    @Test void setRoleOnlyOwner() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.join(g, ALICE, CAROL);
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.ADMIN));
        fails(Failure.NO_PERMISSION, f.svc.members.setRole(BOB, g.id(), CAROL, Role.ADMIN));
        fails(Failure.INVALID_VALUE, f.svc.members.setRole(ALICE, g.id(), CAROL, Role.OWNER));
        fails(Failure.CANNOT_TARGET_SELF, f.svc.members.setRole(ALICE, g.id(), ALICE, Role.MEMBER));
        assertEquals(Role.ADMIN, f.svc.dir().role(g.id(), BOB).orElseThrow());
        ok(f.svc.members.setRole(ALICE, g.id(), BOB, Role.MEMBER));
        assertEquals(Role.MEMBER, f.svc.dir().role(g.id(), BOB).orElseThrow());
    }

    @Test void transferOwnership() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        fails(Failure.NO_PERMISSION, f.svc.members.transferOwnership(BOB, g.id(), ALICE));
        fails(Failure.NOT_MEMBER, f.svc.members.transferOwnership(ALICE, g.id(), CAROL));
        fails(Failure.CANNOT_TARGET_SELF, f.svc.members.transferOwnership(ALICE, g.id(), ALICE));
        EndSpace u = ok(f.svc.members.transferOwnership(ALICE, g.id(), BOB));
        assertEquals(BOB, u.ownerUuid());
        assertEquals(BOB, f.dbSpace(g.id()).ownerUuid());
        assertEquals(BOB, f.svc.dir().space(g.id()).orElseThrow().ownerUuid());
        assertEquals(Role.ADMIN, f.svc.dir().role(g.id(), ALICE).orElseThrow());
        assertEquals(Role.OWNER, f.svc.dir().role(g.id(), BOB).orElseThrow());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=? AND role='OWNER'", g.id()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=? AND role='ADMIN' AND player_uuid=?", g.id(), ALICE));
    }

    @Test void transferRespectsOwnedGroupLimitOfTarget() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        f.newGroup(BOB, "Bob One");
        f.newGroup(BOB, "Bob Two");
        fails(Failure.OWNED_LIMIT_REACHED, f.svc.members.transferOwnership(ALICE, g.id(), BOB));
        assertEquals(Role.OWNER, f.svc.dir().role(g.id(), ALICE).orElseThrow());
    }

    // ---------------------------------------------------------------- leave

    @Test void memberLeaveClearsSelectionOnlyWhenPointingAtSpace() {
        EndSpace bobsSolo = f.newSolo(BOB);
        EndSpace g1 = f.newGroup(ALICE, "Group One");
        EndSpace g2 = f.newGroup(ALICE, "Group Two");
        f.join(g1, ALICE, BOB);
        f.join(g2, ALICE, BOB);

        ok(f.svc.profiles.select(BOB, g1.id()));
        var r = ok(f.svc.members.leave(BOB, g2.id()));
        assertFalse(r.selectionCleared());
        assertEquals(g1.id(), f.svc.dir().selectedId(BOB).orElse(null));

        var r2 = ok(f.svc.members.leave(BOB, g1.id()));
        assertTrue(r2.selectionCleared());
        assertTrue(f.svc.dir().selectedId(BOB).isEmpty());
        assertEquals(0, f.count("SELECT COUNT(*) FROM end_player_profiles WHERE player_uuid=? AND selected_space_id IS NOT NULL", BOB));
        assertTrue(f.svc.dir().isMember(bobsSolo.id(), BOB));
        assertEquals(1, memberCount(g1.id()));
    }

    @Test void ownerLeaveRequireTransfer() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        fails(Failure.OWNER_MUST_TRANSFER, f.svc.members.leave(ALICE, g.id()));
        assertTrue(f.svc.dir().isMember(g.id(), ALICE));
    }

    @Test void soleOwnerMustDisband() {
        EndSpace g = f.newGroup(ALICE);
        fails(Failure.OWNER_MUST_DISBAND, f.svc.members.leave(ALICE, g.id()));
        EndSpace s = f.newSolo(BOB);
        fails(Failure.OWNER_MUST_DISBAND, f.svc.members.leave(BOB, s.id()));
    }

    @Test void ownerLeaveAutoTransferGoesToLongestServingAdmin() {
        settings(10, ServiceSettings.OwnerLeavePolicy.AUTO_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        f.clock.advance(1000);
        f.join(g, ALICE, BOB);      // member, earliest
        f.clock.advance(1000);
        f.join(g, ALICE, CAROL);    // admin, later
        f.clock.advance(1000);
        f.join(g, ALICE, DAVE);     // admin, latest
        ok(f.svc.members.setRole(ALICE, g.id(), CAROL, Role.ADMIN));
        ok(f.svc.members.setRole(ALICE, g.id(), DAVE, Role.ADMIN));

        var r = ok(f.svc.members.leave(ALICE, g.id()));
        assertEquals(CAROL, r.newOwner());
        assertEquals(CAROL, f.dbSpace(g.id()).ownerUuid());
        assertFalse(f.svc.dir().isMember(g.id(), ALICE));
        assertEquals(Role.OWNER, f.svc.dir().role(g.id(), CAROL).orElseThrow());
        assertEquals(1, f.count("SELECT COUNT(*) FROM end_space_members WHERE space_id=? AND role='OWNER'", g.id()));
        assertEquals(3, memberCount(g.id()));
    }

    @Test void ownerLeaveAutoTransferFallsBackToMember() {
        settings(10, ServiceSettings.OwnerLeavePolicy.AUTO_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, BOB);
        var r = ok(f.svc.members.leave(ALICE, g.id()));
        assertEquals(BOB, r.newOwner());
    }

    // ---------------------------------------------------------------- races

    @Test void concurrentPublicJoinsRacingForLastSlots() throws Exception {
        settings(4, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.OPEN));
        var rs = race(12, i -> f.svc.members.joinPublic(player(100 + i), "p" + i, g.id()).join());
        assertEquals(3, countOk(rs));
        rs.stream().filter(r -> !r.isOk()).forEach(r -> assertEquals(Failure.SPACE_FULL, r.failure()));
        assertEquals(4, memberCount(g.id()));
        assertEquals(4, memberRows(g.id()));
    }

    @Test void concurrentInviteAcceptsRacingForLastSlot() throws Exception {
        settings(2, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        List<Invitation> invs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) invs.add(ok(f.svc.members.invite(ALICE, g.id(), player(i), "p" + i)));
        var rs = race(6, i -> f.svc.members.acceptInvite(player(i), "p" + i, invs.get(i).id()).join());
        assertEquals(1, countOk(rs));
        assertEquals(2, memberCount(g.id()));
        assertEquals(2, memberRows(g.id()));
    }

    // ---------------------------------------------------------------- public join / requests

    @Test void joinPublicOpenVsApproval() {
        EndSpace g = f.newGroup(ALICE);
        fails(Failure.NOT_PUBLIC, f.svc.members.joinPublic(BOB, "bob", g.id()));
        fails(Failure.NOT_PUBLIC, f.svc.members.requestJoin(BOB, "bob", g.id()));
        ok(f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.APPROVAL));
        fails(Failure.APPROVAL_REQUIRED, f.svc.members.joinPublic(BOB, "bob", g.id()));
        ok(f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.OPEN));
        fails(Failure.OPEN_JOIN_ONLY, f.svc.members.requestJoin(BOB, "bob", g.id()));
        ok(f.svc.members.joinPublic(BOB, "bob", g.id()));
        assertEquals(Role.MEMBER, f.svc.dir().role(g.id(), BOB).orElseThrow());
        fails(Failure.ALREADY_MEMBER, f.svc.members.joinPublic(BOB, "bob", g.id()));
    }

    @Test void requestApproveDenyCancel() {
        EndSpace g = f.newGroup(ALICE);
        f.join(g, ALICE, DAVE);
        ok(f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.APPROVAL));

        MembershipRequest r1 = ok(f.svc.members.requestJoin(BOB, "bob", g.id()));
        fails(Failure.ALREADY_REQUESTED, f.svc.members.requestJoin(BOB, "bob", g.id()));
        fails(Failure.NO_PERMISSION, f.svc.members.approveRequest(DAVE, r1.id()));
        fails(Failure.NOT_MEMBER, f.svc.members.approveRequest(CAROL, r1.id()));
        ok(f.svc.members.approveRequest(ALICE, r1.id()));
        assertTrue(f.svc.dir().isMember(g.id(), BOB));
        fails(Failure.NOT_FOUND, f.svc.members.approveRequest(ALICE, r1.id()));

        MembershipRequest r2 = ok(f.svc.members.requestJoin(CAROL, "carol", g.id()));
        fails(Failure.NO_PERMISSION, f.svc.members.cancelRequest(BOB, r2.id()));
        ok(f.svc.members.cancelRequest(CAROL, r2.id()));
        fails(Failure.NOT_FOUND, f.svc.members.approveRequest(ALICE, r2.id()));

        MembershipRequest r3 = ok(f.svc.members.requestJoin(CAROL, "carol", g.id()));
        fails(Failure.NO_PERMISSION, f.svc.members.denyRequest(DAVE, r3.id()));
        ok(f.svc.members.denyRequest(ALICE, r3.id()));
        assertFalse(f.svc.dir().isMember(g.id(), CAROL));
        assertEquals(0, ok(f.svc.members.requestsForSpace(g.id())).size());
    }

    @Test void approveAfterSpaceBecameFullFails() {
        settings(2, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER, false);
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.setVisibility(ALICE, g.id(), true, JoinMode.APPROVAL));
        MembershipRequest r = ok(f.svc.members.requestJoin(BOB, "bob", g.id()));
        f.join(g, ALICE, CAROL); // fills the last slot
        fails(Failure.SPACE_FULL, f.svc.members.approveRequest(ALICE, r.id()));
        assertFalse(f.svc.dir().isMember(g.id(), BOB));
        assertEquals(2, memberCount(g.id()));
        assertEquals(1, ok(f.svc.members.requestsForSpace(g.id())).size()); // request stays pending
    }

    @Test void membershipLimitPerPlayerEnforcedOnJoin() {
        f.configure(s -> new ServiceSettings(true, true, 10, 50, 2, 1, s.inviteTtlMillis(), 20, false, true, s.ownerLeavePolicy(),
                3, 24, s.seedMode(), 0, 0, 1, 0, 0, 0));
        EndSpace g = f.newGroup(ALICE);
        f.newGroup(BOB);
        Invitation inv = ok(f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
        fails(Failure.MEMBERSHIP_LIMIT_REACHED, f.svc.members.acceptInvite(BOB, "bob", inv.id()));
    }

    @Test void adminAddAndRemove() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.members.adminAdd(CAROL, g.id(), BOB, "bob"));
        assertTrue(f.svc.dir().isMember(g.id(), BOB));
        ok(f.svc.members.adminRemove(CAROL, g.id(), BOB));
        fails(Failure.CANNOT_TARGET_OWNER, f.svc.members.adminRemove(CAROL, g.id(), ALICE));
        assertEquals(1, memberCount(g.id()));
    }

    @Test void inviteOnArchivedSpaceRejected() {
        EndSpace g = f.newGroup(ALICE);
        ok(f.svc.spaces.disband(ALICE, g.id()));
        fails(Failure.SPACE_NOT_ACTIVE, f.svc.members.invite(ALICE, g.id(), BOB, "bob"));
    }
}
