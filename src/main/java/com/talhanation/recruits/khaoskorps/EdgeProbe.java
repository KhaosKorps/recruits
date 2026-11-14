package com.talhanation.recruits.khaoskorps;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Finds drops a mob must not walk off: an open column whose floor lies further below than the
 * mob's fall distance.
 *
 * Reads blocks only through the BlockGetter it is given, so the path worker can use it on its
 * region snapshot and the server thread on the level. On a level, a column in an unloaded chunk
 * counts as no drop, so a probe never loads a chunk.
 */
public final class EdgeProbe {

    /** {@link #distanceToDangerousDrop} result when no dangerous drop is within the radius. */
    public static final int NONE = 0;

    private EdgeProbe() {
    }

    /**
     * Whether stepping into column (x, z) at feet height {@code feetY} means falling more than
     * {@code maxFall} blocks: the column is open at feet height, and nothing below it within
     * {@code maxFall + 1} blocks can be stood on or swum in. A column that is solid at feet height
     * is a wall or a step up, not a drop.
     */
    public static boolean isDangerousDrop(BlockGetter level, int x, int feetY, int z, int maxFall) {
        if (level instanceof LevelReader reader && !reader.hasChunkAt(x, z)) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, feetY, z);
        if (!isOpen(level, pos)) return false;
        for (int below = 1; below <= maxFall + 1; below++) {
            pos.setY(feetY - below);
            if (pos.getY() < level.getMinBuildHeight()) return true;
            if (!isOpen(level, pos)) return false;
        }
        return true;
    }

    /**
     * How far, in blocks, the nearest dangerous drop is from a mob standing at (x, feetY, z),
     * searching square rings out to {@code radius}; {@link #NONE} when there is none that close.
     * A drop behind a wall doesn't count: every column on the straight line to it must be open at
     * feet height. {@code cache}, when given, remembers each column's answer for the rest of one
     * search; its default return value must be -1.
     */
    public static int distanceToDangerousDrop(BlockGetter level, int x, int feetY, int z, int maxFall, int radius,
                                              @Nullable Long2ByteMap cache) {
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (isDangerousDropCached(level, x + dx, feetY, z + dz, maxFall, cache)
                            && !behindAWall(level, x, feetY, z, dx, dz, ring)) {
                        return ring;
                    }
                }
            }
        }
        return NONE;
    }

    /** Whether a column on the straight line from (x, z) to (x + dx, z + dz) is closed at feet height. */
    private static boolean behindAWall(BlockGetter level, int x, int feetY, int z, int dx, int dz, int ring) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int step = 1; step < ring; step++) {
            int cx = x + Math.round((float) (step * dx) / ring);
            int cz = z + Math.round((float) (step * dz) / ring);
            if (level instanceof LevelReader reader && !reader.hasChunkAt(cx, cz)) return true;
            if (!isOpen(level, pos.set(cx, feetY, cz))) return true;
        }
        return false;
    }

    private static boolean isDangerousDropCached(BlockGetter level, int x, int feetY, int z, int maxFall,
                                                 @Nullable Long2ByteMap cache) {
        if (cache == null) return isDangerousDrop(level, x, feetY, z, maxFall);
        long key = BlockPos.asLong(x, feetY, z);
        byte known = cache.get(key);
        if (known >= 0) return known == 1;
        boolean dangerous = isDangerousDrop(level, x, feetY, z, maxFall);
        cache.put(key, (byte) (dangerous ? 1 : 0));
        return dangerous;
    }

    /** Open: nothing to collide with and no fluid, so a mob would fall through it. */
    private static boolean isOpen(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }
}
