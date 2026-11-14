package com.talhanation.recruits.khaoskorps;

import com.google.common.collect.ImmutableSet;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;

import javax.annotation.Nullable;

/**
 * One walking search between two chosen positions, with a mob's walking rules: the building block
 * of a detour plan, which checks steps that start where the mob isn't standing.
 *
 * With UseAsyncPathfinding on, the search runs on the path worker and the returned path fills in
 * later (AsyncPath.isProcessed); with it off, it runs at once. Server thread only.
 */
public final class DetourSearch {

    /** Path points a search may examine per block of its range, as for following. */
    private static final int NODES_PER_BLOCK = 16;

    private DetourSearch() {
    }

    /**
     * Searches from {@code from} toward {@code to} for {@code mob}, reaching at most {@code range}
     * blocks of walking; the path counts as reaching {@code to} within {@code reach} blocks.
     * Returns null when the search can't start, for example from a position the mob can't stand at.
     */
    @Nullable
    public static Path find(ServerLevel level, Mob mob, BlockPos from, BlockPos to, int range, int reach) {
        if (Thread.currentThread().getThreadGroup() != SidedThreadGroups.SERVER) {
            throw new IllegalStateException("a detour search from " + from + " to " + to + " was started off the server thread");
        }
        int margin = range + 8;
        PathNavigationRegion region = new PathNavigationRegion(level, from.offset(-margin, -margin, -margin),
                from.offset(margin, margin, margin));
        int budget = range * NODES_PER_BLOCK;
        PathFinder finder = RecruitsServerConfig.UseAsyncPathfinding.get()
                ? new AsyncPathfinder(DetourNodeEvaluator.create(), budget, DetourNodeEvaluator.GENERATOR, level)
                : new PathFinder(DetourNodeEvaluator.create(), budget);
        DetourNodeEvaluator.setNextStart(from);
        try {
            return finder.findPath(region, mob, ImmutableSet.of(to), range, reach, 1.0F);
        } finally {
            DetourNodeEvaluator.setNextStart(null);
        }
    }
}
