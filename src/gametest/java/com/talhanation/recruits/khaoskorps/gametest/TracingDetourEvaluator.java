package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.khaoskorps.DetourNodeEvaluator;
import com.talhanation.recruits.pathfinding.NodeEvaluatorCache;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Target;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Queue;

/**
 * The detour planner's evaluator with a {@link SearchTracer} on it, so the planner's own searches can
 * be recorded too. The planner takes its evaluators from a shared pool;
 * {@link #fillThePool} swaps in copies of this class, which behave exactly as the real one and record
 * a search only when the recruit it is run for has a {@link RunRecorder}.
 */
final class TracingDetourEvaluator extends DetourNodeEvaluator {

    private final SearchTracer tracer = new SearchTracer();

    private TracingDetourEvaluator() {
        this.setCanOpenDoors(true);
        this.setCanPassDoors(true);
        this.setCanFloat(true);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.tracer.begin(RunRecorder.recorderFor(mob), "detour", mob, true);
    }

    @Override
    public Node getStart() {
        Node start = super.getStart();
        this.tracer.start(start);
        return start;
    }

    @Override
    public Target getGoal(double x, double y, double z) {
        Target target = super.getGoal(x, y, z);
        this.tracer.goal(target);
        return target;
    }

    @Override
    public int getNeighbors(Node[] neighbours, Node node) {
        boolean record = this.tracer.beforeNeighbours(this.level, node);
        int count = super.getNeighbors(neighbours, node);
        if (record) this.tracer.afterNeighbours(neighbours, count);
        return count;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int stepUp, double floor, Direction direction, BlockPathTypes type) {
        boolean outer = this.tracer.enterAccepted();
        Node node = null;
        try {
            node = super.findAcceptedNode(x, y, z, stepUp, floor, direction, type);
        } finally {
            this.tracer.exitAccepted(outer, x, y, z, direction, node);
        }
        return node;
    }

    @Override
    public void done() {
        this.tracer.done(this.nodes.values());
        super.done();
    }

    private static boolean filled;

    /**
     * Replaces the evaluators waiting in the detour planner's pool with tracing ones, once per test
     * server. With async searches off, the planner builds its evaluator directly, and its searches
     * aren't recorded.
     */
    @SuppressWarnings("unchecked")
    static synchronized void fillThePool() {
        if (filled) return;
        filled = true;
        try {
            Field pools = NodeEvaluatorCache.class.getDeclaredField("threadLocalNodeEvaluators");
            pools.setAccessible(true);
            Map<Object, Queue<NodeEvaluator>> byGenerator = (Map<Object, Queue<NodeEvaluator>>) pools.get(null);
            Queue<NodeEvaluator> pool = byGenerator.computeIfAbsent(DetourNodeEvaluator.GENERATOR, key -> new java.util.concurrent.ConcurrentLinkedQueue<>());
            pool.clear();
            for (int i = 0; i < 8; i++) pool.offer(new TracingDetourEvaluator());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't reach the evaluator pool", e);
        }
    }
}
