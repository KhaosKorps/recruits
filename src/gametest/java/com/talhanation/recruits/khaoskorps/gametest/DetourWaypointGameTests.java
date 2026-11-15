package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.DetourPlan;
import com.talhanation.recruits.khaoskorps.DetourRoutes;
import com.talhanation.recruits.khaoskorps.DetourSearch;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The detour planner takes no waypoint a recruit couldn't walk back from: for every spot it takes as a
 * waypoint, the recruit's own walking search finds a way from that spot back to the waypoint before it. A
 * waypoint without one is a trap, such as a ledge or a hollow reached by a drop it can't climb back up.
 * Planned on the rough ravines ({@link RavineTerrain}), recruit north, owner south, at fall distance 3 and 1.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DetourWaypointGameTests {

    private static final String FALL_THREE = "khaoskorps_detour_waypoints_fall_3";
    private static final String FALL_ONE = "khaoskorps_detour_waypoints_fall_1";
    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6};
    // Six plans run at once in a batch, sharing the planner's searches in flight, so each takes far longer than alone.
    private static final int TIMEOUT = 4000;
    /** How far a way back from a waypoint may walk: a step between waypoints and some slack, as the planner's own searches. */
    private static final int BACK_RANGE = 24;

    /** The waypoints each walker's plans took, as {from, spot}, by the walker's UUID. */
    private static final Map<UUID, List<BlockPos[]>> TAKEN = new ConcurrentHashMap<>();
    /** The observer that was set before this batch, which this one passes every event on to. */
    private static DetourPlan.Observer previous;

    private static void watchPlans() {
        previous = DetourPlan.observer;
        DetourPlan.Observer passOn = previous;
        DetourPlan.observer = (plan, side, event, from, spot) -> {
            if ("reached".equals(event) && spot != null) {
                List<BlockPos[]> taken = TAKEN.get(plan.walker().getUUID());
                if (taken != null) taken.add(new BlockPos[]{from.immutable(), spot.immutable()});
            }
            if (passOn != null) passOn.step(plan, side, event, from, spot);
        };
    }

    private static void unwatchPlans() {
        DetourPlan.observer = previous;
        TAKEN.clear();
    }

    @BeforeBatch(batch = FALL_THREE)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
        watchPlans();
    }

    @AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(ServerLevel level) {
        unwatchPlans();
        resetForkSettings();
    }

    @BeforeBatch(batch = FALL_ONE)
    public static void fallOne(ServerLevel level) {
        resetForkSettings();
        watchPlans();
    }

    @AfterBatch(batch = FALL_ONE)
    public static void afterFallOne(ServerLevel level) {
        unwatchPlans();
    }

    @GameTestGenerator
    public static List<TestFunction> waypointsWithAWayBackAtFallDistanceThree() {
        return tests(FALL_THREE, "aDetourPlanTakesOnlyWaypointsItCanWalkBackFromAtFallDistanceThree");
    }

    @GameTestGenerator
    public static List<TestFunction> waypointsWithAWayBackAtFallDistanceOne() {
        return tests(FALL_ONE, "aDetourPlanTakesOnlyWaypointsItCanWalkBackFromAtFallDistanceOne");
    }

    private static List<TestFunction> tests(String batch, String name) {
        List<TestFunction> tests = new ArrayList<>();
        for (long seed : SEEDS) {
            tests.add(new TestFunction(batch, name + "_seed" + seed, Main.MOD_ID + ":" + RavineGameTests.ARENA, TIMEOUT, 0L, true,
                    helper -> planAcross(helper, new RavineTerrain(seed))));
        }
        return tests;
    }

    private static void planAcross(GameTestHelper helper, RavineTerrain terrain) {
        terrain.build(helper);
        daytime(helper);
        int x = 14;
        int ownerZ = 56;
        ServerPlayer owner = owner(helper, x + 0.5, terrain.stand[x][ownerZ], ownerZ + 0.5);
        pin(helper, owner, x + 0.5, terrain.stand[x][ownerZ], ownerZ + 0.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, x, terrain.stand[x][8], 8);
        List<BlockPos[]> taken = Collections.synchronizedList(new ArrayList<>());
        TAKEN.put(recruit.getUUID(), taken);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        boolean[] requested = {false};
        DetourPlan[] plan = {null};
        List<Path> waysBack = new ArrayList<>();
        helper.onEachTick(() -> {
            if (!requested[0]) {
                if (helper.getTick() < 2) return;
                requested[0] = DetourRoutes.request(owner, recruit, FollowSettings.current());
                if (!requested[0]) throw new GameTestAssertException("setup: the plan didn't start");
                plan[0] = DetourRoutes.plan(owner.getUUID());
                return;
            }
            if (plan[0] == null || plan[0].status() == DetourPlan.Status.PLANNING) return;
            if (waysBack.isEmpty() && !taken.isEmpty()) {
                // Each taken waypoint, searched back to the waypoint it was taken from, with the recruit's rules.
                for (BlockPos[] step : taken) {
                    Path back = DetourSearch.find(helper.getLevel(), recruit, step[1], step[0], BACK_RANGE, 1);
                    waysBack.add(back);
                }
                return;
            }
            for (Path back : waysBack) if (back instanceof AsyncPath async && !async.isProcessed()) return;
            List<String> traps = new ArrayList<>();
            for (int i = 0; i < taken.size(); i++) {
                Path back = waysBack.get(i);
                if (back == null || !back.canReach()) {
                    traps.add(taken.get(i)[1].subtract(origin).toShortString() + " (from " + taken.get(i)[0].subtract(origin).toShortString() + ")");
                }
            }
            String report = terrain.describe() + ": plan " + plan[0].status() + " after " + plan[0].searches() + " searches; "
                    + taken.size() + " waypoints taken, " + traps.size() + " with no way back" + (traps.isEmpty() ? "" : ": " + traps);
            Main.LOGGER.info("[KhaosKorps measure] detour waypoints: {}", report);
            TAKEN.remove(recruit.getUUID());
            recruit.discard();
            removeOwner(helper, owner);
            if (!traps.isEmpty()) throw new GameTestAssertException(report);
            helper.succeed();
        });
    }
}
