package dev.che1s.craftenginerecipe.gui;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetBlockQueryTest {
    @Test
    void rayBoundsIncludeLoadedChunkBufferAroundPositiveAndNegativeCoordinates() {
        TargetBlockQuery.ChunkBounds positive = TargetBlockQuery.bounds(
                new Location(null, 15.5, 64, -0.5), new Vector(1, 0, 0), 6.0);
        TargetBlockQuery.ChunkBounds negative = TargetBlockQuery.bounds(
                new Location(null, -16.2, 64, 1.5), new Vector(-1, 0, 0), 6.0);

        assertEquals(new TargetBlockQuery.ChunkBounds(0, 1, -1, 0), positive);
        assertEquals(new TargetBlockQuery.ChunkBounds(-2, -1, 0, 0), negative);
    }

    @Test
    void rayBoundsNormalizeDirectionBeforeApplyingRange() {
        TargetBlockQuery.ChunkBounds bounds = TargetBlockQuery.bounds(
                new Location(null, 8.0, 64, 8.0), new Vector(10, 0, 0), 6.0);

        assertEquals(new TargetBlockQuery.ChunkBounds(0, 0, 0, 0), bounds);
    }
}
