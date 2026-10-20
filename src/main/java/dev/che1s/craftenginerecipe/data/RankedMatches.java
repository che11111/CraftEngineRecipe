package dev.che1s.craftenginerecipe.data;

import net.momirealms.craftengine.core.util.Key;
import java.util.ArrayList;
import java.util.List;


final class RankedMatches {
    private final int limit;
    private final List<List<Key>> ranks = new ArrayList<>(7);

    RankedMatches(int limit) {
        this.limit = Math.max(0, limit);
        for (int i = 0; i < 7; i++) this.ranks.add(new ArrayList<>());
    }

    void add(int rank, Key id) {
        List<Key> bucket = this.ranks.get(rank);
        if (bucket.size() < this.limit) bucket.add(id);
    }

    List<Key> result() {
        List<Key> result = new ArrayList<>();
        for (List<Key> bucket : this.ranks) {
            int count = Math.min(bucket.size(), this.limit - result.size());
            result.addAll(bucket.subList(0, count));
            if (result.size() == this.limit) break;
        }
        return List.copyOf(result);
    }
}
