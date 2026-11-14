package com.talhanation.recruits.khaoskorps;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Where a path that can't reach its target should end, so the mob waits back from the drop in
 * its way instead of walking to the rim, where it can slide or be pushed off.
 *
 * A path falls short for two reasons: a drop or a gap really blocks the way, or the search ran
 * out of range or budget. Only the first is cut back. On a trail along a cliff that the search
 * merely ran out on, the ground ahead toward the target is more trail, so the path is kept and
 * the mob keeps moving along it.
 */
public final class PathEdgeTrim {

    private PathEdgeTrim() {
    }

    /**
     * How many of the path's points to keep: all of them unless the path can't reach its target
     * and a dangerous drop lies between its end and the target. Then up to the last point with no
     * dangerous drop within {@code keepBack} + 1 blocks: the extra block is room for a walking mob,
     * which coasts about a block after its path ends. When no point is that far back, none is
     * kept, and the mob stays where it is rather than walking to the middle of its block.
     */
    public static int pointsToKeep(BlockGetter level, Path path, int maxFall, int keepBack) {
        int count = path.getNodeCount();
        if (count == 0 || path.canReach() || keepBack <= 0) return count;
        if (!dropAhead(level, path.getEndNode(), path.getTarget(), maxFall)) return count;
        int clearance = keepBack + 1;
        for (int i = count - 1; i >= 0; i--) {
            Node node = path.getNode(i);
            if (EdgeProbe.distanceToDangerousDrop(level, node.x, node.y, node.z, maxFall, clearance, null) == EdgeProbe.NONE) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * How many of the path's points to keep so it doesn't end closer to an edge than it must, near
     * its target, whether or not it reaches the target. Among the points within {@code nearTarget}
     * blocks of the target, counting the first one, where the mob stands, the path ends at the one
     * furthest from a dangerous drop (any with none within {@code keepBack} + 1 blocks counts as
     * furthest), and of equals, the one nearest the end. So the mob never walks somewhere nearer a
     * drop than the safest point it has, even when it already stands inside the margin, as after
     * coasting past a cut path's end; and on a cliff trail, all edge, it still follows to the end.
     */
    public static int pointsToKeepNearTarget(BlockGetter level, Path path, int maxFall, int keepBack, int nearTarget) {
        int count = path.getNodeCount();
        if (count == 0 || keepBack <= 0 || nearTarget <= 0) return count;
        BlockPos target = path.getTarget();
        long nearSqr = (long) nearTarget * nearTarget;
        int clearance = keepBack + 1;
        int best = -1;
        int bestClearance = -1;
        for (int i = count - 1; i >= 0; i--) {
            Node node = path.getNode(i);
            long dx = node.x - target.getX();
            long dz = node.z - target.getZ();
            if (dx * dx + dz * dz > nearSqr) break;
            int distance = EdgeProbe.distanceToDangerousDrop(level, node.x, node.y, node.z, maxFall, clearance, null);
            int clear = distance == EdgeProbe.NONE ? clearance + 1 : distance;
            if (clear > bestClearance) {
                best = i;
                bestClearance = clear;
            }
            if (distance == EdgeProbe.NONE) break;
        }
        return best < 0 ? count : best + 1;
    }

    /**
     * How many of the path's points to keep when it can't reach its target, so it doesn't end deep
     * below both where the mob started and where its target is just because that spot is nearer the
     * target in a straight line, such as the floor of a ravine its owner stands across. Each point
     * scores its distance to the target plus {@code depthWeight} for every block it lies below the
     * lower of the path's first point and the target; the path ends at the best, the earliest of
     * equals. Going down toward a target that is itself lower costs nothing extra, so a path that only
     * fell short of an owner below, such as down a long staircase, is kept. A path that reaches its
     * target, or a weight of 0, keeps every point.
     */
    public static int pointsToKeepForDepth(Path path, double depthWeight) {
        int count = path.getNodeCount();
        if (count == 0 || path.canReach() || depthWeight <= 0) return count;
        BlockPos target = path.getTarget();
        int floorY = Math.min(path.getNode(0).y, target.getY());
        int best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Node node = path.getNode(i);
            double dx = node.x - target.getX();
            double dy = node.y - target.getY();
            double dz = node.z - target.getZ();
            double score = Math.sqrt(dx * dx + dy * dy + dz * dz) + depthWeight * Math.max(0, floorY - node.y);
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best + 1;
    }

    /**
     * Whether either of the two columns from {@code end} along the straight line toward
     * {@code target} is a dangerous drop. The line is followed as it runs, so with the target ahead
     * and a little to one side the columns checked lie ahead, not diagonally beside a trail.
     */
    static boolean dropAhead(BlockGetter level, Node end, BlockPos target, int maxFall) {
        double dx = target.getX() - end.x;
        double dz = target.getZ() - end.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-6) return false;
        for (int step = 1; step <= 2; step++) {
            int cx = end.x + (int) Math.round(step * dx / length);
            int cz = end.z + (int) Math.round(step * dz / length);
            if (cx == end.x && cz == end.z) continue;
            if (EdgeProbe.isDangerousDropFrom(level, end.x, end.y, end.z, cx - end.x, cz - end.z, maxFall)) return true;
        }
        return false;
    }
}
