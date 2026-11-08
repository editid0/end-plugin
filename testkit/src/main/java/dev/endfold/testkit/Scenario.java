package dev.endfold.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Minimal tick-driven test runner. Steps run sequentially on the server thread; waits are expressed in ticks or as
 * polled conditions with a timeout, so the server keeps ticking (worlds load, dragons fly) between steps.
 */
final class Scenario {
    private interface Step { boolean poll(); } // true = finished

    private final Plugin plugin;
    private final Logger log;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<String> results = new ArrayList<>();
    private int pass, fail, skip;
    private Runnable onDone = () -> { };

    Scenario(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    Scenario run(String name, Runnable r) {
        steps.add(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                record("FAIL", name, "exception: " + t);
                t.printStackTrace();
            }
            return true;
        });
        return this;
    }

    Scenario waitTicks(int ticks) {
        int[] left = {ticks};
        steps.add(() -> --left[0] <= 0);
        return this;
    }

    Scenario waitUntil(String name, BooleanSupplier cond, int timeoutTicks) {
        int[] left = {timeoutTicks};
        steps.add(() -> {
            boolean ok;
            try {
                ok = cond.getAsBoolean();
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) return true;
            if (--left[0] <= 0) {
                record("FAIL", name, "timed out after " + timeoutTicks + " ticks");
                return true;
            }
            return false;
        });
        return this;
    }

    void check(String name, boolean ok, String detail) { record(ok ? "PASS" : "FAIL", name, detail); }

    void skip(String name, String why) { record("SKIP", name, why); }

    private void record(String status, String name, String detail) {
        switch (status) {
            case "PASS" -> pass++;
            case "FAIL" -> fail++;
            default -> skip++;
        }
        String line = status + " | " + name + (detail == null || detail.isEmpty() ? "" : " | " + detail);
        results.add(line);
        log.info("[RESULT] " + line);
    }

    Scenario then(Runnable done) {
        this.onDone = done;
        return this;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            while (!steps.isEmpty()) {
                Step s = steps.peek();
                if (s.poll()) steps.poll();
                else return;
            }
            task.cancel();
            onDone.run();
        }, 1L, 1L);
    }

    void write(Path file, String header) {
        List<String> out = new ArrayList<>();
        out.add(header);
        out.add("pass=" + pass + " fail=" + fail + " skip=" + skip);
        out.addAll(results);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out);
        } catch (IOException e) {
            log.severe("Could not write results: " + e);
        }
        log.info("[RESULT] SUMMARY pass=" + pass + " fail=" + fail + " skip=" + skip);
    }
}
