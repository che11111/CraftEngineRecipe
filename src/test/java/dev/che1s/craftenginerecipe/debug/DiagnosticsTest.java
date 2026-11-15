package dev.che1s.craftenginerecipe.debug;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {
    @Test void aggregatesConcurrentWorkWithBoundedMetrics() throws Exception {
        Diagnostics diagnostics = new Diagnostics();
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 4).mapToObj(index -> workers.submit(() -> {
                for (int i = 0; i < 1_000; ++i) {
                    diagnostics.record(Diagnostics.Operation.RECIPE_QUERY, 2_000_000);
                    diagnostics.increment(Diagnostics.Counter.MENU_CLICKS);
                }
            })).toList();
            for (var task : tasks) task.get();
        }
        var snapshot = diagnostics.snapshot();
        var timing = snapshot.timings().getFirst();
        assertEquals(4_000, timing.count());
        assertEquals(8_000, timing.totalMillis());
        assertEquals(2, timing.averageMillis());
        assertEquals(2, timing.maximumMillis());
        assertEquals(4_000, snapshot.counters().get(Diagnostics.Counter.MENU_CLICKS));
        assertEquals(Diagnostics.Counter.values().length, snapshot.counters().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.timings().clear());
    }

    @Test void resettingDoesNotLetInflightScopesPolluteNewGeneration() {
        Diagnostics diagnostics = new Diagnostics();
        var oldScope = diagnostics.measure(Diagnostics.Operation.RECIPE_OPEN);
        diagnostics.increment(Diagnostics.Counter.CLICK_THROTTLED);
        diagnostics.reset();
        oldScope.close();
        assertTrue(diagnostics.snapshot().timings().isEmpty());
        assertEquals(0, diagnostics.snapshot().counters().get(Diagnostics.Counter.CLICK_THROTTLED));
        var current = diagnostics.measure(Diagnostics.Operation.BROWSER_OPEN);
        current.close();
        current.close();
        assertEquals(1, diagnostics.snapshot().timings().getFirst().count());
    }

    @Test void negativeRecordedDurationsDoNotBreakAggregateStatistics() {
        Diagnostics diagnostics = new Diagnostics();
        diagnostics.record(Diagnostics.Operation.HOTKEY, -1);
        assertEquals(0, diagnostics.snapshot().timings().getFirst().totalNanos());
        assertEquals(0, diagnostics.snapshot().timings().getFirst().maxNanos());
    }
}
