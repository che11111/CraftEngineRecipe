package dev.che1s.craftenginerecipe.debug;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 固定大小的聚合计时器。不保留玩家标识、采样明细或磁盘写入。 */
public final class Diagnostics {
    public enum Operation {
        MENU_ACTION, BROWSER_OPEN, RECIPE_OPEN, RECIPE_QUERY, VISIBLE_ITEMS, WORKSTATION_OPEN,
        HOTKEY, SOURCE_REFRESH, EXPORT_SNAPSHOT, EXPORT_WRITE
    }
    public enum Counter { MENU_CLICKS, CLICK_THROTTLED }

    private volatile State state = new State();

    public Scope measure(Operation operation) {
        return new Scope(this.state.timings.get(operation), System.nanoTime());
    }

    public void record(Operation operation, long nanos) {
        this.state.timings.get(operation).record(nanos);
    }

    public void increment(Counter counter) { this.state.counters.get(counter).increment(); }

    /** 整体切换到新数据版本；正在执行的计时范围仍会在旧版本中结束。 */
    public void reset() { this.state = new State(); }

    public Snapshot snapshot() {
        State current = this.state;
        List<Timing> timings = new ArrayList<>();
        current.timings.forEach((operation, aggregate) -> {
            long count = aggregate.count.sum();
            if (count > 0) timings.add(new Timing(operation, count, aggregate.nanos.sum(), aggregate.max.get()));
        });
        Map<Counter, Long> counters = new EnumMap<>(Counter.class);
        current.counters.forEach((counter, value) -> counters.put(counter, value.sum()));
        return new Snapshot(Math.max(0, System.nanoTime() - current.started), List.copyOf(timings), Map.copyOf(counters));
    }

    public record Timing(Operation operation, long count, long totalNanos, long maxNanos) {
        public double totalMillis() { return this.totalNanos / 1_000_000.0; }
        public double averageMillis() { return this.count == 0 ? 0 : this.totalMillis() / this.count; }
        public double maximumMillis() { return this.maxNanos / 1_000_000.0; }
    }
    public record Snapshot(long elapsedNanos, List<Timing> timings, Map<Counter, Long> counters) {}

    public static final class Scope implements AutoCloseable {
        private final Aggregate aggregate;
        private final long started;
        private boolean closed;

        private Scope(Aggregate aggregate, long started) { this.aggregate = aggregate; this.started = started; }

        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            this.aggregate.record(System.nanoTime() - this.started);
        }
    }

    private static final class Aggregate {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private void record(long duration) {
            long nanos = Math.max(0, duration);
            this.count.increment();
            this.nanos.add(nanos);
            this.max.accumulateAndGet(nanos, Math::max);
        }
    }

    private static final class State {
        private final long started = System.nanoTime();
        private final Map<Operation, Aggregate> timings = new EnumMap<>(Operation.class);
        private final Map<Counter, LongAdder> counters = new EnumMap<>(Counter.class);
        private State() {
            for (Operation operation : Operation.values()) this.timings.put(operation, new Aggregate());
            for (Counter counter : Counter.values()) this.counters.put(counter, new LongAdder());
        }
    }
}
