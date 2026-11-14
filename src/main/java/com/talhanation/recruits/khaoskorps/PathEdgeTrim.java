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

    /** Whether either of the two columns from {@code end} toward {@code target} is a dangerous drop. */
    static boolean dropAhead(BlockGetter level, Node end, BlockPos target, int maxFall) {
        int stepX = Integer.signum(target.getX() - end.x);
        int stepZ = Integer.signum(target.getZ() - end.z);
        if (stepX == 0 && stepZ == 0) return false;
        for (int step = 1; step <= 2; step++) {
            if (EdgeProbe.isDangerousDrop(level, end.x + stepX * step, end.y, end.z + stepZ * step, maxFall)) return true;
        }
        return false;
    }
}
