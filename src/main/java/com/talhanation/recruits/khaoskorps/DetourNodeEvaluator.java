package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.entities.ai.navigation.RecruitsPathNodeEvaluator;
import com.talhanation.recruits.pathfinding.NodeEvaluatorGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;

import javax.annotation.Nullable;

/**
 * A recruit's node evaluator whose search can start at a chosen position instead of where the
 * mob stands, so a detour can be planned from waypoints the recruit hasn't reached yet. Walking
 * costs, edge costs and fall limits are the recruit's own.
 *
 * Both path finders ask the evaluator for its start on the thread that calls findPath, the server
 * thread, before any work goes to the path worker. {@link DetourSearch} sets the start there, just
 * before the call, and clears it straight after.
 */
public class DetourNodeEvaluator extends RecruitsPathNodeEvaluator {

    /** Detour evaluators for the async path finder's pool. */
    public static final NodeEvaluatorGenerator GENERATOR = DetourNodeEvaluator::create;

    @Nullable
    private static BlockPos nextStart;

    static DetourNodeEvaluator create() {
        DetourNodeEvaluator evaluator = new DetourNodeEvaluator();
        evaluator.setCanOpenDoors(true);
        evaluator.setCanPassDoors(true);
        evaluator.setCanFloat(true);
        return evaluator;
    }

    /** The start for the next search. Server thread only. */
    static void setNextStart(@Nullable BlockPos start) {
        nextStart = start;
    }

    @Override
    public Node getStart() {
        BlockPos start = nextStart;
        return start == null ? super.getStart() : this.getStartNode(start);
    }
}
