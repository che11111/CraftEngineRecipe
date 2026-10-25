package dev.che1s.craftenginerecipe.gui;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 将延迟点击合并到查看者下一次调度器执行时机。 */
final class MenuActionGate<K> {
    private final Map<K, Object> pending = new ConcurrentHashMap<>();

    /**
     * 每个查看者最多只保留一个待执行动作。调度器拒绝任务时会返回 false；
     * 回调必须在该查看者的所属线程运行。窗口按对象身份比较，避免旧窗口影响替代窗口。
     */
    boolean offer(K viewer, Object expectedWindow, long generation, Runnable action,
                  Predicate<Runnable> scheduler, Supplier<?> currentWindow,
                  LongSupplier generationNow, BooleanSupplier ready) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(currentWindow, "currentWindow");
        Objects.requireNonNull(generationNow, "generationNow");
        Objects.requireNonNull(ready, "ready");
        Object ticket = new Object();
        if (this.pending.putIfAbsent(viewer, ticket) != null) return false;
        try {
            boolean accepted = scheduler.test(() -> {
                // 在检查状态或调用用户代码前先释放标记。异常或重入动作不能让查看者永久阻塞。
                if (!this.pending.remove(viewer, ticket)) return;
                if (!ready.getAsBoolean() || currentWindow.get() != expectedWindow
                        || generationNow.getAsLong() != generation) return;
                action.run();
            });
            if (!accepted) this.pending.remove(viewer, ticket);
            return accepted;
        } catch (RuntimeException | Error error) {
            this.pending.remove(viewer, ticket);
            throw error;
        }
    }

    void cancel(K viewer) { this.pending.remove(viewer); }

    void clear() { this.pending.clear(); }

    int pendingCount() { return this.pending.size(); }
}
