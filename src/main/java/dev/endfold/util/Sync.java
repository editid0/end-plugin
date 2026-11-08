package dev.endfold.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Helpers for hopping from database futures back onto the server thread. */
public final class Sync {
    private static volatile Plugin plugin;

    private Sync() {}

    public static void init(Plugin p) { plugin = p; }

    /** Executor that runs tasks on the server thread (immediately if already there). */
    public static Executor main() {
        return r -> {
            Plugin p = plugin;
            if (p == null || !p.isEnabled()) return; // plugin shutting down: drop UI work safely
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(p, r);
        };
    }

    /** Continues {@code future} on the server thread. Exceptions are logged by the services already. */
    public static <T> CompletableFuture<Void> then(CompletableFuture<T> future, Consumer<T> onMain) {
        return future.thenAcceptAsync(onMain, main());
    }

    public static void run(Runnable r) { main().execute(r); }

    public static void later(long ticks, Runnable r) {
        Plugin p = plugin;
        if (p != null && p.isEnabled()) Bukkit.getScheduler().runTaskLater(p, r, ticks);
    }
}
