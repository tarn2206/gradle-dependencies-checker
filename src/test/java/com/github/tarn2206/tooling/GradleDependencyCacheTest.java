package com.github.tarn2206.tooling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GradleDependencyCacheTest {
    @Test
    public void cachesSuccessfulLoadsAndRetriesFailures() {
        var loads = new AtomicInteger();
        var cache = new GradleDependencyCache(() -> {
            if (loads.incrementAndGet() == 2) throw new IllegalStateException("failure");
            return snapshot();
        });
        var first = cache.get();
        assertSame(first, cache.get());
        assertEquals(1, loads.get());
        cache.invalidate();
        assertThrows(IllegalStateException.class, cache::get);
        var replacement = cache.get();
        assertNotSame(first, replacement);
        assertSame(replacement, cache.get());
        assertEquals(3, loads.get());
        cache.dispose();
        assertNotSame(replacement, cache.get());
    }

    @Test
    public void concurrentReadersShareReplacementAfterInvalidationDuringLoad() throws Exception {
        var loads = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stale = snapshot();
        var replacement = snapshot();
        var cache = new GradleDependencyCache(() -> {
            if (loads.incrementAndGet() == 1) {
                started.countDown();
                await(release);
                return stale;
            }
            return replacement;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(cache::get);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var second = executor.submit(cache::get);
                cache.invalidate();
                release.countDown();
                assertSame(replacement, first.get(5, TimeUnit.SECONDS));
                assertSame(replacement, second.get(5, TimeUnit.SECONDS));
                assertEquals(2, loads.get());
            } finally {
                release.countDown();
            }
        }
    }

    private static BuildSnapshot snapshot() {
        return new BuildSnapshot(new BuildSnapshot.ModuleEntry[0]);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
