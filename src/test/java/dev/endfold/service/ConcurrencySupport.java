package dev.endfold.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

final class ConcurrencySupport {
    private ConcurrencySupport() {}

    /** Runs n tasks released together by a start gate; returns their results in order. */
    static <T> List<T> race(int n, IntFunction<T> task) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                fs.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(idx);
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(20, TimeUnit.SECONDS));
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    static <T> long countOk(List<Result<T>> rs) { return rs.stream().filter(Result::isOk).count(); }
}
