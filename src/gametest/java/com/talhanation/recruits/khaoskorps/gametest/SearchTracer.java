package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.khaoskorps.EdgeCost;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Target;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording behind a tracing evaluator ({@link TracingEvaluator}, {@link TracingDetourEvaluator}):
 * every point the A* takes off its open set (g, h, f, costMalus, the edge ring around it), every step
 * it asks the evaluator about (accepted, refused or blocked, and how many spots the dead-end check
 * walked), and each neighbour's state before and after the search's update. The evaluators call it
 * around their real methods; it only observes.
 *
 * Each search gets a {@link SearchRecord}, begun in prepare() on the server thread and handed to its
 * {@link Sink} from done(), on whichever thread ran the search. A sink can ask for full detail on
 * some searches only; the rest record just the points taken off the open set.
 */
final class SearchTracer {

    /** Where a search's record goes; called from the server thread and from path workers. */
    interface Sink {
        void prepared(SearchRecord record);

        /** Whether this search, now that its start and target are known, records full detail. */
        boolean detail(SearchRecord record);

        /** Whether this search keeps its points at all; without them it keeps only its counts. Asked with detail. */
        default boolean keepPoints(SearchRecord record) {
            return true;
        }

        /** Every point the search took off its open set, kept or not: for counts over many searches. Any thread. */
        default void point(SearchRecord record, int x, int y, int z, boolean expanded) {
        }

        void finished(SearchRecord record);
    }

    /** One search. */
    static final class SearchRecord {
        String kind;
        long tick;
        int[] start;
        final List<int[]> targets = new ArrayList<>();
        String dirtPath;
        int margin;
        int maxFall;
        /** Whether pops carry candidates, neighbours and the edge ring; else positions only. */
        boolean detail;
        /** Whether pops are kept at all; else only the counts below. */
        boolean keepPoints = true;
        /** Points taken off the open set, of them expanded, and of those behind the start (more than 90 degrees off the line to the target). */
        int popCount, expandedCount, behindCount;
        /** Whether the search ran on the server thread: with async searches on, the path worker's queue was full. */
        volatile boolean onServerThread;
        final List<Pop> pops = new ArrayList<>();
        /** The path the search returned, when the path finder that ran it could note it. */
        volatile Path path;
        volatile boolean noStart;
        volatile boolean finished;
        /** Whether a point within a block of a target was taken off the open set: the search's own test for reaching it. */
        volatile boolean reachedByPop;
    }

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
        /** False for a point closed but never expanded: the one that reached the target, or one beyond the search's range. */
        boolean expanded = true;
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

    @Nullable private Sink sink;
    @Nullable private SearchRecord record;
    @Nullable private Pop current;
    private boolean decided;
    private int depth;
    private int deadEndSteps;

    /** In prepare(), after the real one: begins a record, or none when there is no sink. */
    void begin(@Nullable Sink sink, String kind, Mob mob, boolean edgeCare) {
        this.sink = sink;
        this.record = null;
        this.current = null;
        this.decided = false;
        this.expandedHere.clear();
        if (sink == null) return;
        SearchRecord next = new SearchRecord();
        next.kind = kind;
        next.tick = mob.level().getGameTime();
        FollowSettings settings = FollowSettings.current();
        next.dirtPath = settings.dirtPathPreference().name();
        next.margin = edgeCare && settings.edgeMargin() ? settings.edgeMarginBlocks() : 0;
        next.maxFall = mob.getMaxFallDistance();
        this.record = next;
        sink.prepared(next);
    }

    void start(@Nullable Node start) {
        if (this.record != null && start != null) this.record.start = new int[]{start.x, start.y, start.z};
    }

    void goal(Target target) {
        if (this.record != null) this.record.targets.add(new int[]{target.x, target.y, target.z});
    }

    /** Before the real getNeighbors: records the point taken off the open set. Returns whether to record its neighbours. */
    boolean beforeNeighbours(BlockGetter level, Node node) {
        if (this.record == null) return false;
        if (!this.decided) {
            this.record.keepPoints = this.sink.keepPoints(this.record);
            this.record.detail = this.record.keepPoints && this.sink.detail(this.record);
            this.decided = true;
        }
        this.settlePrevious();
        for (int[] target : this.record.targets) {
            if (Math.abs(node.x - target[0]) + Math.abs(node.y - target[1]) + Math.abs(node.z - target[2]) <= 1) this.record.reachedByPop = true;
        }
        this.count(node, true);
        if (!this.record.detail) {
            this.current = null;
            if (this.record.keepPoints) this.record.pops.add(new Pop(node, "", 0, 0));
            return false;
        }
        int margin = this.record.margin;
        StringBuilder ring = new StringBuilder();
        for (int r = 1; r <= margin; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ring.append(EdgeProbe.isDangerousDropFrom(level, node.x, node.y, node.z, dx, dz, this.record.maxFall) ? 'D' : '.');
                }
            }
        }
        int distance = margin > 0 ? EdgeProbe.distanceToDangerousDrop(level, node.x, node.y, node.z, this.record.maxFall, margin, null) : 0;
        this.current = new Pop(node, ring.toString(), distance, EdgeCost.malus(distance, margin));
        this.record.pops.add(this.current);
        return true;
    }

    /** After the real getNeighbors: the neighbours it returned. */
    void afterNeighbours(Node[] neighbours, int count) {
        if (this.current == null) return;
        for (int i = 0; i < count; i++) this.current.neighbours.add(new Neighbour(neighbours[i]));
    }

    /** Before the real findAcceptedNode; calls inside it (the dead-end check's) are counted, not recorded. */
    boolean enterAccepted() {
        boolean outer = this.depth == 0;
        if (outer) this.deadEndSteps = 0;
        else this.deadEndSteps++;
        this.depth++;
        return outer;
    }

    /** After the real findAcceptedNode. */
    void exitAccepted(boolean outer, int x, int y, int z, Direction direction, @Nullable Node node) {
        this.depth--;
        if (!outer || this.current == null) return;
        String result = node == null ? "refused" : node.costMalus < 0 ? "blocked" : "accepted";
        this.current.candidates.add(new Candidate(direction.getName(), x, y, z, result,
                node == null ? x : node.x, node == null ? y : node.y, node == null ? z : node.z,
                node == null ? 0 : node.costMalus, this.deadEndSteps));
    }

    /**
     * In done(), before the real one: hands the finished record to its sink. {@code nodes} is every
     * point the search created; the ones it closed without asking for their neighbours are added at
     * the end, in order of f: the search checks whether a point reaches the target before expanding
     * it, so the point that reached it is among them, as are any beyond the search's range.
     */
    void done(Iterable<Node> nodes) {
        this.settlePrevious();
        if (this.record != null) {
            // The path workers inherit the server's thread group, so ask the server whether this is its own thread.
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            this.record.onServerThread = server != null && server.isSameThread();
            // A point is closed when taken off the open set; one closed and never expanded is the one
            // that reached the target or one beyond the range, as the class comment says.
            List<Node> unexpanded = new ArrayList<>();
            for (Node node : nodes) {
                if (node.closed && !this.expandedHere.contains(net.minecraft.core.BlockPos.asLong(node.x, node.y, node.z))) unexpanded.add(node);
            }
            unexpanded.sort((a, b) -> Float.compare(a.f, b.f));
            for (Node node : unexpanded) {
                this.count(node, false);
                if (this.record.keepPoints) {
                    Pop pop = new Pop(node, "", 0, 0);
                    pop.expanded = false;
                    this.record.pops.add(pop);
                }
                for (int[] target : this.record.targets) {
                    if (Math.abs(node.x - target[0]) + Math.abs(node.y - target[1]) + Math.abs(node.z - target[2]) <= 1) this.record.reachedByPop = true;
                }
            }
        }
        this.expandedHere.clear();
        SearchRecord finished = this.record;
        Sink to = this.sink;
        this.record = null;
        this.current = null;
        if (finished != null && to != null) {
            finished.finished = true;
            to.finished(finished);
        }
    }

    /** The points this search has expanded, to tell the closed-but-unexpanded ones apart in done(). */
    private final java.util.Set<Long> expandedHere = new java.util.HashSet<>();

    /** Counts a point taken off the open set and hands it to the sink. */
    private void count(Node node, boolean expanded) {
        SearchRecord r = this.record;
        r.popCount++;
        if (expanded) {
            r.expandedCount++;
            this.expandedHere.add(net.minecraft.core.BlockPos.asLong(node.x, node.y, node.z));
        }
        if (r.start != null && !r.targets.isEmpty()) {
            int[] t = r.targets.get(0);
            long towardTarget = (long) (node.x - r.start[0]) * (t[0] - r.start[0]) + (long) (node.z - r.start[2]) * (t[2] - r.start[2]);
            if (towardTarget < 0) r.behindCount++;
        }
        this.sink.point(r, node.x, node.y, node.z, expanded);
    }

    /** The search has moved on (or ended): how did it update the last point's neighbours? */
    private void settlePrevious() {
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
}
