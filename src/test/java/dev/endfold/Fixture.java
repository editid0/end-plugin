package dev.endfold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.endfold.db.MigrationRunner;
import dev.endfold.db.SqliteDatabase;
import dev.endfold.db.sql.Repositories;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceType;
import dev.endfold.service.Failure;
import dev.endfold.service.Result;
import dev.endfold.service.ServiceContext;
import dev.endfold.service.ServiceSettings;
import dev.endfold.service.Services;
import dev.endfold.service.ProgressService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Test fixture: real SQLite file, real services, mutable clock and settings. */
public final class Fixture implements AutoCloseable {

    /** Mutable clock. */
    public static final class TestClock extends Clock {
        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
        public void advance(long ms) { now.addAndGet(ms); }
    }

    public static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    public static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    public static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    public static UUID player(int n) { return new UUID(0xABCDEFL, n); }

    public final Path file;
    public final TestClock clock = new TestClock();
    public final AtomicReference<ServiceSettings> settings = new AtomicReference<>(ServiceSettings.defaults());
    public final Logger logger = Logger.getLogger("endfold-test");
    public SqliteDatabase db;
    public Services svc;

    public Fixture(Path dir) {
        logger.setLevel(Level.WARNING);
        this.file = dir.resolve("data").resolve("endfold.db");
        open();
    }

    private void open() {
        try {
            db = new SqliteDatabase(file, logger);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new MigrationRunner(db, logger, name -> Fixture.class.getClassLoader().getResourceAsStream(name)).migrate();
        ServiceContext ctx = new ServiceContext(db, Repositories.sql(), new dev.endfold.service.SpaceDirectory(), clock, logger, settings::get);
        svc = new Services(ctx, () -> ProgressService.all());
        svc.loadDirectory();
    }

    /** Simulates a server restart: close the database, reopen the same file with fresh services and a fresh directory. */
    public void restart() {
        db.close();
        open();
    }

    public void configure(java.util.function.UnaryOperator<ServiceSettings> f) { settings.set(f.apply(settings.get())); }

    // ---- result helpers ----

    public static <T> T ok(CompletableFuture<Result<T>> f) {
        Result<T> r = f.join();
        assertTrue(r.isOk(), "expected ok but was " + r);
        return r.value();
    }

    public static <T> void fails(Failure expected, CompletableFuture<Result<T>> f) {
        Result<T> r = f.join();
        assertTrue(!r.isOk(), "expected failure " + expected + " but was " + r);
        assertEquals(expected, r.failure());
    }

    // ---- domain helpers ----

    public EndSpace newSolo(UUID owner) {
        return ok(svc.spaces.create(owner, "p" + owner.getLeastSignificantBits(), SpaceType.SOLO, "Solo Space"));
    }

    public EndSpace newGroup(UUID owner) { return newGroup(owner, "Group Space"); }

    public EndSpace newGroup(UUID owner, String name) {
        return ok(svc.spaces.create(owner, "p" + owner.getLeastSignificantBits(), SpaceType.GROUP, name));
    }

    /** Invite + accept through the real flow. */
    public void join(EndSpace space, UUID inviter, UUID player) {
        var inv = ok(svc.members.invite(inviter, space.id(), player, "p" + player.getLeastSignificantBits()));
        ok(svc.members.acceptInvite(player, "p" + player.getLeastSignificantBits(), inv.id()));
    }

    public long count(String sql, Object... params) {
        return db.txBlocking(tx -> tx.count(sql, params));
    }

    public void exec(String sql, Object... params) {
        db.txBlocking(tx -> tx.update(sql, params));
    }

    public EndSpace dbSpace(UUID id) {
        return db.txBlocking(tx -> svc.ctx.repo.spaces().find(tx, id).orElseThrow());
    }

    @Override public void close() { db.close(); }
}
