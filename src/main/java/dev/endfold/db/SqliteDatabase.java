package dev.endfold.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite implementation: one connection owned by one thread. SQLite serialises writers anyway, so a pool would only
 * add SQLITE_BUSY contention. WAL + synchronous=FULL makes every committed reward claim durable before the item is
 * handed out (see LootService / ElytraService for ordering).
 */
public final class SqliteDatabase implements Database {
    private final ExecutorService executor;
    private final Connection connection;
    private final Logger logger;
    private volatile Thread dbThread;

    public SqliteDatabase(Path file, Logger logger) throws SQLException {
        this.logger = logger;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on the server classpath", e);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("Cannot create database directory", e);
        }
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Endfold-DB");
            t.setDaemon(true);
            dbThread = t;
            return t;
        });
        try {
            this.connection = executor.submit(() -> {
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=FULL");
                    st.execute("PRAGMA foreign_keys=ON");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                c.setAutoCommit(false);
                return c;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while opening database", e);
        } catch (ExecutionException e) {
            executor.shutdownNow();
            throw e.getCause() instanceof SQLException se ? se : new SQLException(e.getCause());
        }
    }

    @Override public Dialect dialect() { return Dialect.SQLITE; }

    @Override
    public <T> CompletableFuture<T> tx(TxWork<T> work) {
        // Queuing from the DB thread itself (e.g. in a thenCompose continuation) is fine: the work simply runs after the
        // current task. Only blocking on it from the DB thread would deadlock - see txBlocking.
        return CompletableFuture.supplyAsync(() -> runTx(work), executor);
    }

    @Override
    public <T> T txBlocking(TxWork<T> work) {
        if (Thread.currentThread() == dbThread) throw new IllegalStateException("txBlocking called on the DB thread (would deadlock)");
        try {
            return tx(work).join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private <T> T runTx(TxWork<T> work) {
        Tx tx = new Tx(connection, Dialect.SQLITE);
        try {
            T result = work.run(tx);
            connection.commit();
            for (Runnable r : tx.afterCommitHooks()) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "After-commit hook failed", e);
                }
            }
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException re) {
                logger.log(Level.SEVERE, "Rollback failed", re);
            }
            if (e instanceof RuntimeException re) throw re;
            throw new CompletionException(e);
        }
    }

    @Override
    public CompletableFuture<Void> backupTo(Path target) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                connection.setAutoCommit(true);
                try (Statement st = connection.createStatement()) {
                    st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
                } finally {
                    connection.setAutoCommit(false);
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.submit(() -> {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error closing database", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Database thread did not finish within 30s; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
