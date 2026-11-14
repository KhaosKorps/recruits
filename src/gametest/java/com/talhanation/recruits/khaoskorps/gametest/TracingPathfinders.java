package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.pathfinding.AsyncPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import java.util.Set;

/**
 * The two path finders a recruit's navigation can hold, each with a {@link TracingEvaluator}, so a
 * recording can tie every search to the path it returned. {@link Async} is the fork's
 * AsyncPathfinder (UseAsyncPathfinding on), {@link Sync} vanilla's PathFinder (off); both run their
 * real code, and only note the returned path.
 */
final class TracingPathfinders {

    /** The sink side that pairs a search's record with its path; server thread. */
    interface Pairing {
        /** The record of the search the last prepare() began, taken once. */
        SearchTracer.SearchRecord takePrepared();

        void returned(SearchTracer.SearchRecord record, Path path);
    }

    private TracingPathfinders() {
    }

    static final class Async extends AsyncPathfinder {
        private final Pairing pairing;

        Async(int budget, SearchTracer.Sink sink, Pairing pairing, Level level) {
            super(new TracingEvaluator(false, sink), budget, () -> new TracingEvaluator(false, sink), level);
            this.pairing = pairing;
        }

        @Override
        public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float range, int reach, float budgetMultiplier) {
            Path path = super.findPath(region, mob, targets, range, reach, budgetMultiplier);
            SearchTracer.SearchRecord record = this.pairing.takePrepared();
            if (record != null) this.pairing.returned(record, path);
            return path;
        }
    }

    static final class Sync extends PathFinder {
        private final Pairing pairing;

        Sync(int budget, SearchTracer.Sink sink, Pairing pairing) {
            super(new TracingEvaluator(false, sink), budget);
            this.pairing = pairing;
        }

        @Override
        public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float range, int reach, float budgetMultiplier) {
            Path path = super.findPath(region, mob, targets, range, reach, budgetMultiplier);
            SearchTracer.SearchRecord record = this.pairing.takePrepared();
            if (record != null) this.pairing.returned(record, path);
            return path;
        }
    }
}
