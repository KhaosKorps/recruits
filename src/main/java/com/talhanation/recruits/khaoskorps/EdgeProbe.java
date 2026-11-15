package com.talhanation.recruits.khaoskorps;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
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
     * {@code maxFall} blocks, or into lava: the column is lava at feet height; or it is open there,
     * and below it, within {@code maxFall + 1} blocks, is lava or nothing that can be stood on or
     * swum in. A column that is solid at feet height is a wall or a step up, not a drop; water is
     * something to land in, except falling water, which counts as open.
     */
    public static boolean isDangerousDrop(BlockGetter level, int x, int feetY, int z, int maxFall) {
        if (level instanceof LevelReader reader && !reader.hasChunkAt(x, z)) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, feetY, z);
        if (isLava(level, pos)) return true;
        if (!isOpen(level, pos)) return false;
        for (int below = 1; below <= maxFall + 1; below++) {
            pos.setY(feetY - below);
            if (pos.getY() < level.getMinBuildHeight()) return true;
            if (isLava(level, pos)) return true;
            if (!isOpen(level, pos)) return false;
        }
        return true;
    }

    /**
     * How far, in blocks, the nearest dangerous drop is from a mob standing at (x, feetY, z),
     * searching square rings out to {@code radius}; {@link #NONE} when there is none that close.
     *
     * Each column is judged from the height a mob would reach it at, walking the straight line from
     * (x, z) one column at a time: down onto the ground when it lies within the fall distance, or up
     * a one-block step. So a walkable slope is no drop, while a cliff is one however far back it is
     * seen from. A column the walk can't reach, behind a wall or past a fall too deep, doesn't count:
     * a fall too deep on the way is itself counted, at its own nearer ring.
     * {@code cache}, when given, remembers each column's answer for the rest of one search; its
     * default return value must be -1.
     */
    public static int distanceToDangerousDrop(BlockGetter level, int x, int feetY, int z, int maxFall, int radius,
                                              @Nullable Long2ByteMap cache) {
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int arrival = feetOnTheWayTo(level, x, feetY, z, dx, dz, ring, maxFall);
                    if (arrival != UNREACHABLE && isDangerousDropCached(level, x + dx, arrival, z + dz, maxFall, cache)) {
                        return ring;
                    }
                }
            }
        }
        return NONE;
    }

    /**
     * Whether column (x + dx, z + dz) is a dangerous drop for a mob at (x, feetY, z) that walks the
     * straight line to it, judged from the height it would reach it at; false if it can't reach it.
     */
    public static boolean isDangerousDropFrom(BlockGetter level, int x, int feetY, int z, int dx, int dz, int maxFall) {
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        if (ring == 0) return false;
        int arrival = feetOnTheWayTo(level, x, feetY, z, dx, dz, ring, maxFall);
        return arrival != UNREACHABLE && isDangerousDrop(level, x + dx, arrival, z + dz, maxFall);
    }

    private static final int UNREACHABLE = Integer.MIN_VALUE;

    /**
     * The feet height a mob reaches the last column before (x + dx, z + dz) at, walking the straight
     * line from (x, feetY, z); {@link #UNREACHABLE} when a wall, an unloaded chunk or a fall deeper
     * than {@code maxFall} is in the way.
     */
    private static int feetOnTheWayTo(BlockGetter level, int x, int feetY, int z, int dx, int dz, int ring, int maxFall) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int feet = feetY;
        for (int step = 1; step < ring; step++) {
            int cx = x + Math.round((float) (step * dx) / ring);
            int cz = z + Math.round((float) (step * dz) / ring);
            if (level instanceof LevelReader reader && !reader.hasChunkAt(cx, cz)) return UNREACHABLE;
            if (isLava(level, pos.set(cx, feet, cz))) return UNREACHABLE;
            if (isOpen(level, pos)) {
                int fall = 0;
                while (isOpen(level, pos.set(cx, feet - fall - 1, cz))) {
                    if (++fall > maxFall || feet - fall - 1 < level.getMinBuildHeight()) return UNREACHABLE;
                }
                if (isLava(level, pos)) return UNREACHABLE;
                feet -= fall;
            } else if (isOpen(level, pos.set(cx, feet + 1, cz)) && isOpen(level, pos.set(cx, feet + 2, cz))) {
                feet += 1;
            } else {
                return UNREACHABLE;
            }
        }
        return feet;
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

    private static boolean isLava(BlockGetter level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA);
    }

    /**
     * Open: nothing to collide with, and no fluid or only falling water, so a mob would fall through
     * it. A waterfall's column is falling water all the way down; it is nothing to land in.
     */
    private static boolean isOpen(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        FluidState fluid = state.getFluidState();
        return fluid.isEmpty() || (fluid.is(FluidTags.WATER) && fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING));
    }
}
