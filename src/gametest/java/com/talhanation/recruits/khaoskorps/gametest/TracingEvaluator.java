package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.entities.ai.navigation.RecruitsPathNodeEvaluator;
import com.talhanation.recruits.khaoskorps.EdgeCost;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * The recruit evaluator with a recorder on it, for watching one real search: every point the A*
 * takes off its open set, the candidates it asks the evaluator for around that point (accepted,
 * refused or blocked), the neighbours it gets back with their costs, and how the search updated
 * each neighbour. It only observes: every rule is the real evaluator's, run by the real
 * {@code AsyncPathfinder}. Edge care applies, as for a following recruit.
 *
 * Recording happens on whichever thread runs the search; read {@link #pops} only after the search
 * has finished.
 */
final class TracingEvaluator extends RecruitsPathNodeEvaluator {

    /** One candidate step the evaluator was asked about. */
    record Candidate(String direction, int askX, int askY, int askZ, String result, int x, int y, int z, float costMalus,
                     int deadEndSteps) {
    }

    /** One neighbour the search got back, with its state before and after the search's update. */
    static final class Neighbour {
        final Node node;
        final int x, y, z;
        final float costMalus;
        final float gBefore;
        final boolean openBefore;
        float gAfter, hAfter, fAfter;
        boolean updated;

        Neighbour(Node node) {
            this.node = node;
            this.x = node.x;
            this.y = node.y;
            this.z = node.z;
            this.costMalus = node.costMalus;
            this.gBefore = node.g;
            this.openBefore = node.inOpenSet();
        }
    }

    /** One point taken off the open set. */
    static final class Pop {
        final int x, y, z;
        final float g, h, f;
        final int[] from;
        final String ring;
        final int edgeDistance;
        final float edgeCost;
        final float costMalus;
        final List<Candidate> candidates = new ArrayList<>();
        final List<Neighbour> neighbours = new ArrayList<>();

        Pop(Node node, String ring, int edgeDistance, float edgeCost) {
            this.x = node.x;
            this.y = node.y;
            this.z = node.z;
            this.g = node.g;
            this.h = node.h;
            this.f = node.f;
            this.from = node.cameFrom == null ? null : new int[]{node.cameFrom.x, node.cameFrom.y, node.cameFrom.z};
            this.ring = ring;
            this.edgeDistance = edgeDistance;
            this.edgeCost = edgeCost;
            this.costMalus = node.costMalus;
        }
    }

    final List<Pop> pops = new ArrayList<>();
    private Pop current;
    private int depth;
    private int deadEndSteps;
    private int margin;
    private int maxFall;

    @Override
    protected boolean edgeCareApplies(Mob mob) {
        return true;
    }

    @Override
    public void prepare(net.minecraft.world.level.PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.margin = com.talhanation.recruits.khaoskorps.FollowSettings.current().edgeMarginBlocks();
        this.maxFall = mob.getMaxFallDistance();
    }

    @Override
    public int getNeighbors(Node[] neighbours, Node node) {
        this.settlePrevious(node);
        StringBuilder ring = new StringBuilder();
        for (int r = 1; r <= this.margin; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ring.append(EdgeProbe.isDangerousDropFrom(this.level, node.x, node.y, node.z, dx, dz, this.maxFall) ? 'D' : '.');
                }
            }
        }
        int distance = EdgeProbe.distanceToDangerousDrop(this.level, node.x, node.y, node.z, this.maxFall, this.margin, null);
        this.current = new Pop(node, ring.toString(), distance, EdgeCost.malus(distance, this.margin));
        this.pops.add(this.current);
        int count = super.getNeighbors(neighbours, node);
        for (int i = 0; i < count; i++) this.current.neighbours.add(new Neighbour(neighbours[i]));
        return count;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int stepUp, double floor, Direction direction, BlockPathTypes type) {
        boolean outer = this.depth == 0;
        if (outer) this.deadEndSteps = 0;
        else this.deadEndSteps++;
        this.depth++;
        Node node;
        try {
            node = super.findAcceptedNode(x, y, z, stepUp, floor, direction, type);
        } finally {
            this.depth--;
        }
        if (outer && this.current != null) {
            String result = node == null ? "refused" : node.costMalus < 0 ? "blocked" : "accepted";
            this.current.candidates.add(new Candidate(direction.getName(), x, y, z, result,
                    node == null ? x : node.x, node == null ? y : node.y, node == null ? z : node.z,
                    node == null ? 0 : node.costMalus, this.deadEndSteps));
        }
        return node;
    }

    /** Reads how the search updated the previous point's neighbours, once it moves on to the next point. */
    private void settlePrevious(Node next) {
        if (this.current == null) return;
        for (Neighbour n : this.current.neighbours) {
            n.gAfter = n.node.g;
            n.hAfter = n.node.h;
            n.fAfter = n.node.f;
            Node from = n.node.cameFrom;
            n.updated = from != null && from.x == this.current.x && from.y == this.current.y && from.z == this.current.z
                    && (n.gAfter != n.gBefore || !n.openBefore);
        }
    }

    /** Settles the last point's neighbours; call once the search has finished. */
    void finish() {
        this.settlePrevious(null);
    }
}
