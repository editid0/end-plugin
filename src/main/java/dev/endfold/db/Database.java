package dev.endfold.db;

import java.util.concurrent.CompletableFuture;

/**
 * Database facade. All work runs on a dedicated database thread; callers receive futures and must hop back to the
 * server thread themselves before touching Bukkit APIs.
 */
public interface Database extends AutoCloseable {

    @FunctionalInterface
    interface TxWork<T> { T run(Tx tx) throws Exception; }

    /** Runs {@code work} inside one transaction on the database thread. Rolls back on any exception. */
    <T> CompletableFuture<T> tx(TxWork<T> work);

    /** Runs a transaction and blocks for the result. Only for startup, shutdown and tests - never on a hot path. */
    <T> T txBlocking(TxWork<T> work);

    Dialect dialect();

    /** Creates a consistent online copy of the database at {@code target} (SQLite: VACUUM INTO). */
    CompletableFuture<Void> backupTo(java.nio.file.Path target);

    @Override void close();
}
