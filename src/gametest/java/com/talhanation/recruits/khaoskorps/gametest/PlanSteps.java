package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.khaoskorps.DetourPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The detour planner's steps (every {@link DetourPlan.Observer} event) for one recording, kept as rows
 * for the run file: [tick, plan, side, event, from, spot], positions relative to the test's arena. One
 * observer serves every recording at once: each plan's events go, by the recruit the plan was started
 * for, to the recording watching that recruit; plans nobody records are ignored. Server thread.
 */
final class PlanSteps {

    private static final Map<UUID, PlanSteps> BY_WALKER = new ConcurrentHashMap<>();

    private final GameTestHelper helper;
    private final BlockPos origin;
    private final IdentityHashMap<DetourPlan, Integer> planIds = new IdentityHashMap<>();
    private final List<String> rows = new ArrayList<>();

    PlanSteps(GameTestHelper helper) {
        this.helper = helper;
        this.origin = helper.absolutePos(BlockPos.ZERO);
    }

    /** Sends the plans started for {@code walker} to this recording, from now until {@link #unwatch}. */
    void watch(Mob walker) {
        DetourPlan.observer = PlanSteps::route;
        BY_WALKER.put(walker.getUUID(), this);
    }

    static void unwatch(Mob walker) {
        BY_WALKER.remove(walker.getUUID());
    }

    private static void route(DetourPlan plan, int side, String event, BlockPos from, @Nullable BlockPos spot) {
        PlanSteps steps = BY_WALKER.get(plan.walker().getUUID());
        if (steps != null) steps.add(plan, side, event, from, spot);
    }

    private synchronized void add(DetourPlan plan, int side, String event, BlockPos from, @Nullable BlockPos spot) {
        int id = this.planIds.computeIfAbsent(plan, p -> this.planIds.size());
        this.rows.add("[" + this.helper.getTick() + "," + id + "," + side + "," + Recordings.quote(event) + "," + this.rel(from) + ","
                + (spot == null ? "null" : this.rel(spot)) + "]");
    }

    /** How many steps of each event the recording has, for a test to check. */
    synchronized Map<String, Integer> counts() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (String row : this.rows) {
            int start = row.indexOf('"') + 1;
            counts.merge(row.substring(start, row.indexOf('"', start)), 1, Integer::sum);
        }
        return counts;
    }

    synchronized String json() {
        return "[" + String.join(",", this.rows) + "]";
    }

    private String rel(BlockPos p) {
        return Recordings.xyz(p.getX() - this.origin.getX(), p.getY() - this.origin.getY(), p.getZ() - this.origin.getZ());
    }
}
