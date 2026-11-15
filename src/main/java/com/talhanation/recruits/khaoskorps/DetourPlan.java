package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans a way round an obstacle too large for one search, in steps, before anyone walks it.
 *
 * Both sides of the obstacle are searched in lockstep. At each waypoint a side first checks for a
 * walkable path from there to the goal within the follow path length; if there is none, it tries
 * the spots {@link DetourGeometry} gives, in order, until a search reaches one and a search from it
 * back to the waypoint reaches too: that spot becomes its next waypoint. A spot without a way back is
 * a trap (a ledge or hollow reached by a drop that can't be climbed back up) and is passed over. A spot
 * must be standable, loaded, with no dangerous drop within the margin, away from spots already visited,
 * and within the detour's limit. The first side whose direct check succeeds gives the route; when both
 * sides run out, the plan fails.
 *
 * Server thread only. Each search is a {@link DetourSearch}, so with UseAsyncPathfinding on the
 * plan waits for the path worker between steps.
 */
public final class DetourPlan {

    public enum Status { PLANNING, FOUND, FAILED }

    /**
     * Watches every plan's steps, for the GameTest recorder and for diagnostics. Server thread. The events,
     * with {@code from} the waypoint the side stands on and {@code spot} the spot concerned, or null:
     * direct, direct-reached, direct-failed, direct-no-start: the check for a walkable path to the goal;
     * unloaded, no-ground, near-drop: a candidate column with no spot to use, because its chunk isn't
     * loaded, there is nothing to stand on within HEIGHT_RANGE of the waypoint's height, or every spot
     * there is near a dangerous drop; visited, over-budget: a spot too near one already visited, or past
     * the detour's limit; trying, failed, no-start: the search to a candidate spot; checking-back,
     * no-way-back: the search from a spot that search reached back to the waypoint, and a spot passed over
     * because it found none; reached: the spot became a waypoint; side-done: the side has no candidates or
     * searches left.
     */
    public interface Observer {
        void step(DetourPlan plan, int side, String event, BlockPos from, @Nullable BlockPos spot);
    }

    /** The observer every plan reports to; null, the default, and then reporting costs nothing. */
    @Nullable
    public static volatile Observer observer;

    /** Why the last {@link #safeSpot} call found no spot: unloaded, no-ground or near-drop. */
    private String spotProblem = "";

    private void note(int side, String event, BlockPos from, @Nullable BlockPos spot) {
        Observer watching = observer;
        if (watching != null) watching.step(this, side, event, from, spot);
    }

    /** No plan examines more searches than this, so a hopeless one ends. */
    static final int MAX_SEARCHES = 120;
    /** How far above and below a waypoint's height a candidate spot's ground is looked for. */
    private static final int HEIGHT_RANGE = 8;

    private final ServerLevel level;
    private final Mob walker;
    private final BlockPos start;
    private BlockPos goal;
    private final int step;
    private final int directRange;
    private final int clearance;
    private final int maxFall;
    private final double budget;
    private final List<int[]> visited = new ArrayList<>();
    private final Side[] sides;
    private Status status = Status.PLANNING;
    /** The found plan this one replaces, whose walkers keep to it while this one plans; null for a first plan. */
    @Nullable private DetourPlan replaces;
    private List<BlockPos> route = List.of();
    private int searches;
    private final long startedTick;
    private long finishedTick = -1;

    /**
     * @param walker      the recruit whose walking rules every search uses
     * @param start       where the plan starts: where the walker is stuck
     * @param goal        where it should get to: the owner's position, on the ground
     * @param step        the length of a step between waypoints, in blocks
     * @param directRange the longest path a waypoint's direct check may take: the follow path length
     * @param keepBack    the edge margin: waypoints keep this far plus a block from a dangerous drop
     * @param budgetFactor the detour's limit, as a multiple of the straight-line distance to the goal
     */
    public DetourPlan(ServerLevel level, Mob walker, BlockPos start, BlockPos goal, int step, int directRange,
                      int keepBack, double budgetFactor) {
        this.level = level;
        this.walker = walker;
        this.start = start.immutable();
        this.goal = goal.immutable();
        this.step = step;
        this.directRange = directRange;
        this.clearance = keepBack + 1;
        this.maxFall = walker.getMaxFallDistance();
        this.budget = DetourGeometry.budget(Math.sqrt(horizontalDistanceSqr(start, goal)), budgetFactor);
        this.visited.add(new int[]{start.getX(), start.getZ()});
        this.sides = new Side[]{new Side(DetourGeometry.LEFT), new Side(DetourGeometry.RIGHT)};
        this.startedTick = level.getGameTime();
    }

    public Status status() {
        return this.status;
    }

    /** The waypoints to walk, in order, once the plan is FOUND; empty otherwise. The goal is not among them. */
    public List<BlockPos> route() {
        return this.route;
    }

    public BlockPos goal() {
        return this.goal;
    }

    public BlockPos start() {
        return this.start;
    }

    /** How many searches the plan has started. */
    public int searches() {
        return this.searches;
    }

    /** Game ticks from the plan's start to its end, or so far. */
    public long ticks() {
        return (this.finishedTick < 0 ? this.level.getGameTime() : this.finishedTick) - this.startedTick;
    }

    public Mob walker() {
        return this.walker;
    }

    /** The game tick the plan was found or failed on; -1 while planning. */
    long endedTick() {
        return this.finishedTick;
    }

    @Nullable
    public DetourPlan replaces() {
        return this.replaces;
    }

    void replacing(DetourPlan old) {
        this.replaces = old;
    }

    /** Moves the goal, for an owner who has moved since the plan began. */
    void setGoal(BlockPos goal) {
        this.goal = goal.immutable();
    }

    /** How many searches the plan is waiting on. */
    int pendingSearches() {
        int pending = 0;
        for (Side side : this.sides) if (side.pending != null) pending++;
        return pending;
    }

    /**
     * Collects finished searches and starts at most {@code allowance} new ones, one per side at most.
     * Returns how many it started.
     */
    int advance(int allowance) {
        int started = 0;
        for (Side side : this.sides) {
            if (this.status != Status.PLANNING) break;
            started += side.advance(allowance - started);
        }
        if (this.status == Status.PLANNING && !this.sides[0].alive && !this.sides[1].alive) {
            this.finish(Status.FAILED, List.of());
        }
        return started;
    }

    private void finish(Status status, List<BlockPos> route) {
        this.status = status;
        this.route = List.copyOf(route);
        this.finishedTick = this.level.getGameTime();
    }

    /** What a side's pending search is for. */
    private enum Pending { DIRECT, TO_SPOT, BACK }

    private final class Side {
        final int sign;
        final List<BlockPos> waypoints = new ArrayList<>();
        double travelled;
        /** -1: the direct check from the last waypoint is due; otherwise the next candidate to try. */
        int cursor = -1;
        List<DetourGeometry.Offset> candidates = List.of();
        @Nullable Path pending;
        Pending pendingKind = Pending.DIRECT;
        @Nullable BlockPos pendingTarget;
        /** A spot a search reached whose way back to the last waypoint is still to be searched; null if none. */
        @Nullable BlockPos backDue;
        boolean alive = true;

        Side(int sign) {
            this.sign = sign;
            this.waypoints.add(DetourPlan.this.start);
        }

        int advance(int allowance) {
            if (!this.alive) return 0;
            if (this.pending != null) {
                if (this.pending instanceof AsyncPath async && !async.isProcessed()) return 0;
                Path done = this.pending;
                this.pending = null;
                if (this.pendingKind == Pending.DIRECT) {
                    if (done.canReach()) {
                        DetourPlan.this.note(this.sign, "direct-reached", this.last(), DetourPlan.this.goal);
                        List<BlockPos> found = new ArrayList<>(this.waypoints);
                        found.remove(0);
                        DetourPlan.this.finish(Status.FOUND, found);
                        return 0;
                    }
                    DetourPlan.this.note(this.sign, "direct-failed", this.last(), DetourPlan.this.goal);
                    this.beginCandidates();
                } else if (this.pendingKind == Pending.TO_SPOT && done.canReach()) {
                    // Reached; it becomes a waypoint once the way back from it is found too.
                    this.backDue = this.pendingTarget;
                } else if (this.pendingKind == Pending.BACK && done.canReach()) {
                    BlockPos reached = this.pendingTarget;
                    DetourPlan.this.note(this.sign, "reached", this.last(), reached);
                    this.travelled += Math.sqrt(horizontalDistanceSqr(this.last(), reached));
                    this.waypoints.add(reached);
                    DetourPlan.this.visited.add(new int[]{reached.getX(), reached.getZ()});
                    this.cursor = -1;
                } else if (this.pendingKind == Pending.BACK) {
                    DetourPlan.this.note(this.sign, "no-way-back", this.last(), this.pendingTarget);
                    this.cursor++;
                } else {
                    DetourPlan.this.note(this.sign, "failed", this.last(), this.pendingTarget);
                    this.cursor++;
                }
            }
            if (allowance <= 0) return 0;
            if (DetourPlan.this.searches >= MAX_SEARCHES) {
                DetourPlan.this.note(this.sign, "side-done", this.last(), null);
                this.alive = false;
                return 0;
            }
            BlockPos from = this.last();
            if (this.backDue != null) {
                BlockPos spot = this.backDue;
                this.backDue = null;
                Path back = this.search(spot, from, DetourPlan.this.step + 8);
                if (back != null) {
                    DetourPlan.this.note(this.sign, "checking-back", from, spot);
                    this.pending = back;
                    this.pendingKind = Pending.BACK;
                    this.pendingTarget = spot;
                    return 1;
                }
                DetourPlan.this.note(this.sign, "no-way-back", from, spot);
                this.cursor++;
            }
            if (this.cursor < 0) {
                Path direct = this.search(from, DetourPlan.this.goal, DetourPlan.this.directRange);
                if (direct != null) {
                    DetourPlan.this.note(this.sign, "direct", from, DetourPlan.this.goal);
                    this.pending = direct;
                    this.pendingKind = Pending.DIRECT;
                    return 1;
                }
                DetourPlan.this.note(this.sign, "direct-no-start", from, DetourPlan.this.goal);
                this.beginCandidates();
            }
            while (this.cursor < this.candidates.size()) {
                DetourGeometry.Offset offset = this.candidates.get(this.cursor);
                BlockPos spot = DetourPlan.this.safeSpot(from.getX() + offset.dx(), from.getY(), from.getZ() + offset.dz());
                if (spot == null) {
                    DetourPlan.this.note(this.sign, DetourPlan.this.spotProblem, from,
                            new BlockPos(from.getX() + offset.dx(), from.getY(), from.getZ() + offset.dz()));
                    this.cursor++;
                    continue;
                }
                if (DetourGeometry.nearAny(spot.getX(), spot.getZ(), DetourPlan.this.visited, DetourPlan.this.step * 3 / 8)) {
                    DetourPlan.this.note(this.sign, "visited", from, spot);
                    this.cursor++;
                    continue;
                }
                if (this.travelled + Math.sqrt(horizontalDistanceSqr(from, spot)) > DetourPlan.this.budget) {
                    DetourPlan.this.note(this.sign, "over-budget", from, spot);
                    this.cursor++;
                    continue;
                }
                Path toSpot = this.search(from, spot, DetourPlan.this.step + 8);
                if (toSpot == null) {
                    DetourPlan.this.note(this.sign, "no-start", from, spot);
                    this.cursor++;
                    continue;
                }
                DetourPlan.this.note(this.sign, "trying", from, spot);
                this.pending = toSpot;
                this.pendingKind = Pending.TO_SPOT;
                this.pendingTarget = spot;
                return 1;
            }
            DetourPlan.this.note(this.sign, "side-done", from, null);
            this.alive = false;
            return 0;
        }

        private void beginCandidates() {
            BlockPos from = this.last();
            this.candidates = DetourGeometry.candidates(DetourPlan.this.goal.getX() - from.getX(),
                    DetourPlan.this.goal.getZ() - from.getZ(), this.sign, DetourPlan.this.step);
            this.cursor = 0;
        }

        @Nullable
        private Path search(BlockPos from, BlockPos to, int range) {
            DetourPlan.this.searches++;
            return DetourSearch.find(DetourPlan.this.level, DetourPlan.this.walker, from, to, range, 1);
        }

        private BlockPos last() {
            return this.waypoints.get(this.waypoints.size() - 1);
        }
    }

    /**
     * A spot a recruit can stand at in column (x, z), within HEIGHT_RANGE of {@code nearY}, nearest
     * that height first, in a loaded chunk, with no dangerous drop within the margin; null if none, with the
     * reason kept for the observer.
     */
    @Nullable
    BlockPos safeSpot(int x, int nearY, int z) {
        if (!this.level.hasChunkAt(new BlockPos(x, nearY, z))) {
            this.spotProblem = "unloaded";
            return null;
        }
        boolean nearDrop = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int offset = 0; offset <= HEIGHT_RANGE; offset++) {
            for (int sign : offset == 0 ? new int[]{1} : new int[]{1, -1}) {
                int y = nearY + sign * offset;
                if (!this.standable(pos.set(x, y, z))) continue;
                if (EdgeProbe.distanceToDangerousDrop(this.level, x, y, z, this.maxFall, this.clearance, null) != EdgeProbe.NONE) {
                    nearDrop = true;
                    continue;
                }
                return pos.immutable();
            }
        }
        this.spotProblem = nearDrop ? "near-drop" : "no-ground";
        return null;
    }

    /** Solid ground below, and room to stand: no collision and no fluid at the feet and the head. */
    private boolean standable(BlockPos feet) {
        BlockPos below = feet.below();
        BlockState ground = this.level.getBlockState(below);
        if (ground.getCollisionShape(this.level, below).isEmpty() || !ground.getFluidState().isEmpty()) return false;
        for (BlockPos room : new BlockPos[]{feet, feet.above()}) {
            BlockState state = this.level.getBlockState(room);
            if (!state.getCollisionShape(this.level, room).isEmpty() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
