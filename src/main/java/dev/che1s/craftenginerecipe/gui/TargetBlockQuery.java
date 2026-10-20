package dev.che1s.craftenginerecipe.gui;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Region-safe, bounded ray query for the block a player is looking at. */
final class TargetBlockQuery {
    private static final double RANGE = 6.0;

    private TargetBlockQuery() {}

    static Block trace(Player player) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) return null;
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() == 0 || !Double.isFinite(direction.lengthSquared())) return null;
        direction.normalize();

        ChunkBounds bounds = bounds(eye, direction, RANGE);
        for (int chunkX = bounds.minX(); chunkX <= bounds.maxX(); chunkX++) {
            for (int chunkZ = bounds.minZ(); chunkZ <= bounds.maxZ(); chunkZ++) {
                if (!Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)
                        || !world.isChunkLoaded(chunkX, chunkZ)) {
                    return null;
                }
            }
        }

        RayTraceResult hit = world.rayTraceBlocks(eye, direction, RANGE, FluidCollisionMode.NEVER, false);
        return hit == null ? null : hit.getHitBlock();
    }

    static ChunkBounds bounds(Location origin, Vector direction, double distance) {
        Vector unit = direction.clone().normalize();
        double endX = origin.getX() + unit.getX() * distance;
        double endZ = origin.getZ() + unit.getZ() * distance;
        int minX = floorChunk(Math.floor(Math.min(origin.getX(), endX)) - 1.0);
        int maxX = floorChunk(Math.floor(Math.max(origin.getX(), endX)) + 1.0);
        int minZ = floorChunk(Math.floor(Math.min(origin.getZ(), endZ)) - 1.0);
        int maxZ = floorChunk(Math.floor(Math.max(origin.getZ(), endZ)) + 1.0);
        return new ChunkBounds(minX, maxX, minZ, maxZ);
    }

    private static int floorChunk(double coordinate) {
        return Math.floorDiv((int) coordinate, 16);
    }

    record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {}
}
