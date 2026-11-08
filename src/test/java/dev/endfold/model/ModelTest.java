package dev.endfold.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.service.Names;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelTest {
    @Test void roleOrdering() {
        assertTrue(Role.OWNER.atLeast(Role.ADMIN));
        assertTrue(Role.ADMIN.atLeast(Role.MEMBER));
        assertTrue(Role.ADMIN.atLeast(Role.ADMIN));
        assertFalse(Role.MEMBER.atLeast(Role.ADMIN));
        assertTrue(Role.OWNER.outranks(Role.ADMIN));
        assertTrue(Role.ADMIN.outranks(Role.MEMBER));
        assertFalse(Role.ADMIN.outranks(Role.ADMIN));
        assertFalse(Role.MEMBER.outranks(Role.OWNER));
    }

    @Test void blockPosRoundTrip() {
        BlockPos p = new BlockPos(-12, 64, 100000);
        assertEquals("-12,64,100000", p.key());
        assertEquals(p, BlockPos.parse(p.key()));
        assertEquals(p, BlockPos.parse(" -12 , 64 ,100000"));
        assertThrows(IllegalArgumentException.class, () -> BlockPos.parse("1,2"));
        assertThrows(NumberFormatException.class, () -> BlockPos.parse("a,b,c"));
    }

    @Test void worldNameAndShortId() {
        UUID id = UUID.fromString("12345678-abcd-4def-8123-456789abcdef");
        assertEquals("12345678", EndSpace.shortId(id));
        assertEquals("endspace_12345678", EndSpace.worldNameFor(id));
    }

    @Test void spaceStatusLive() {
        assertTrue(SpaceStatus.ACTIVE.isLive());
        assertTrue(SpaceStatus.BROKEN.isLive());
        assertFalse(SpaceStatus.ARCHIVED.isLive());
        assertFalse(SpaceStatus.DELETED.isLive());
    }

    @Test void invitationExpiry() {
        Invitation i = new Invitation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), InviteStatus.PENDING, 0, 100);
        assertFalse(i.isExpired(99));
        assertTrue(i.isExpired(100));
    }

    @Test void namesValidation() {
        assertTrue(Names.valid("My Space", 3, 24));
        assertTrue(Names.valid("  Spaced   out  ", 3, 24));
        assertFalse(Names.valid("ab", 3, 24));
        assertFalse(Names.valid("a".repeat(25), 3, 24));
        assertTrue(Names.valid("a".repeat(24), 3, 24));
        assertFalse(Names.valid("<red>Hi", 3, 24));
        assertFalse(Names.valid("Hi§cthere", 3, 24));
        assertFalse(Names.valid("<click:run_command:/op x>", 3, 40));
        assertFalse(Names.valid("new\u0007line", 3, 24));
        assertFalse(Names.valid(null, 3, 24));
        assertFalse(Names.valid("   ", 1, 24));
        assertFalse(Names.valid("'; DROP TABLE x;--", 3, 40));
        assertEquals("a b", Names.normalise("  a \t  b "));
    }
}
