package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.entities.ai.navigation.RecruitsPathNodeEvaluator;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Target;

/**
 * The recruit evaluator with a {@link SearchTracer} on it: every rule is the real evaluator's, run by
 * the real path finder; the tracer only records what they compute.
 */
final class TracingEvaluator extends RecruitsPathNodeEvaluator {

    private final boolean forceEdgeCare;
    private final SearchTracer.Sink sink;
    private final SearchTracer tracer = new SearchTracer();

    /**
     * @param forceEdgeCare true to search as a following recruit does whatever the mob is doing (a
     *                      standalone search); false to keep the real rule, so a recruit's own searches
     *                      get edge care only while it follows
     * @param sink          where each search's record goes
     */
    TracingEvaluator(boolean forceEdgeCare, SearchTracer.Sink sink) {
        this.forceEdgeCare = forceEdgeCare;
        this.sink = sink;
        this.setCanOpenDoors(true);
        this.setCanPassDoors(true);
        this.setCanFloat(true);
    }

    @Override
    protected boolean edgeCareApplies(Mob mob) {
        return this.forceEdgeCare || super.edgeCareApplies(mob);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.tracer.begin(this.sink, "follow", mob, this.edgeCareApplies(mob));
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
}
