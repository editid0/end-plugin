package dev.endfold.service;

import dev.endfold.db.Database;
import dev.endfold.db.Tx;
import dev.endfold.db.sql.Repositories;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared dependencies of all services plus the transaction-to-Result adapter. */
public final class ServiceContext {
    public final Database db;
    public final Repositories repo;
    public final SpaceDirectory dir;
    public final Clock clock;
    public final Logger logger;
    private final Supplier<ServiceSettings> settings;

    public ServiceContext(Database db, Repositories repo, SpaceDirectory dir, Clock clock, Logger logger, Supplier<ServiceSettings> settings) {
        this.db = db;
        this.repo = repo;
        this.dir = dir;
        this.clock = clock;
        this.logger = logger;
        this.settings = settings;
    }

    public ServiceSettings settings() { return settings.get(); }

    public long now() { return clock.millis(); }

    @FunctionalInterface
    public interface Work<T> { T run(Tx tx) throws Exception; }

    /** Runs work in a transaction; {@link ServiceException} becomes a failed Result and rolls back. */
    public <T> CompletableFuture<Result<T>> tx(String op, Work<T> work) {
        return db.<Result<T>>tx(tx -> {
            try {
                return Result.ok(work.run(tx));
            } catch (ServiceException e) {
                // Roll back by rethrowing, then convert below.
                throw e;
            }
        }).exceptionally(t -> {
            Throwable c = t;
            while ((c instanceof java.util.concurrent.CompletionException || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
                c = c.getCause();
            }
            if (c instanceof ServiceException se) return Result.fail(se.failure);
            logger.log(Level.SEVERE, "Endfold operation '" + op + "' failed", c);
            return Result.fail(Failure.INTERNAL_ERROR);
        });
    }

    public static ServiceException fail(Failure f) { return new ServiceException(f); }

    public void audit(Tx tx, UUID space, UUID actor, String action, String detail) throws Exception {
        repo.audit().log(tx, space, actor, action, detail, now());
    }
}
