package dev.leon.cer.gui;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared registry; each viewer's lifecycle runs on that viewer's entity scheduler. */
final class MenuLifecycle {
    private final Map<UUID, Object> windows = new ConcurrentHashMap<>();
    private final Set<UUID> entries = ConcurrentHashMap.newKeySet();

    void requestEntry(UUID player) { this.entries.add(player); }
    void cancelEntry(UUID player) { this.entries.remove(player); }

    boolean opened(UUID player, Object window) {
        this.windows.put(player, window);
        return this.entries.remove(player);
    }

    boolean isCurrent(UUID player, Object window) { return this.windows.get(player) == window; }
    boolean contains(UUID player) { return this.windows.containsKey(player); }

    boolean closed(UUID player, Object window) {
        return this.windows.remove(player, window);
    }

    void forget(UUID player) {
        this.windows.remove(player);
        this.entries.remove(player);
    }

    void clear() {
        this.windows.clear();
        this.entries.clear();
    }
}
