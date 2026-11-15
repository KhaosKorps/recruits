package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Groups on a ravine captured from the user's test world: the box from (-207, -4, -399) to
 * (-112, 72, -352), 96 x 77 x 48, as the template khaoskorps_ravine_1 (leaves made persistent;
 * entities and block entity data left out).
 *
 * In template coordinates: ground at stand height 66 to 67 north and south of a sunken basin; the
 * basin's floor 55 to 65 and lower to the east; in its middle, around x = 31..48, z = 23..32, a pit
 * whose floor stands at 13 to 26, with lava at the bottom of its west end. Below stand height 48 is
 * inside the pit.
 *
 * Optional for now: they measure what a group does there, at fall distance 3 as on the servers.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CapturedRavineGameTests {

    static final String RAVINE_TEMPLATE = "khaoskorps_ravine_1";
    private static final String RAVINE = RAVINE_TEMPLATE;
    private static final int SIZE_X = 96;
    private static final int SIZE_Z = 48;
    private static final int TOP = 76;
    private static final double IN_THE_PIT_BELOW = 48.0;
    private static final String FALL_THREE = "khaoskorps_captured_ravine_fall_3";
    private static final int MEASURE_AT = 1200;

    @BeforeBatch(batch = FALL_THREE)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(ServerLevel level) {
        resetForkSettings();
    }

    private static final String FALL_THREE_DIRT_ONCE = "khaoskorps_captured_ravine_fall_3_dirt_once";
    private static final String FALL_THREE_DIRT_OFF = "khaoskorps_captured_ravine_fall_3_dirt_off";

    /**
     * Fall distance 3 with the dirt-path cost added once per point (DirtPathPreference ONCE).
     *
     * @param level the test level
     */
    @BeforeBatch(batch = FALL_THREE_DIRT_ONCE)
    public static void fallThreeDirtOnce(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MaxFallDistance.set(3);
            KhaosKorpsServerConfig.DirtPathPreference.set(com.talhanation.recruits.khaoskorps.FollowSettings.DirtPathPreference.ONCE);
        });
    }

    /**
     * Puts the fork's settings back after the ONCE batch.
     *
     * @param level the test level
     */
    @AfterBatch(batch = FALL_THREE_DIRT_ONCE)
    public static void afterFallThreeDirtOnce(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Fall distance 3 with no dirt-path cost (DirtPathPreference OFF).
     *
     * @param level the test level
     */
    @BeforeBatch(batch = FALL_THREE_DIRT_OFF)
    public static void fallThreeDirtOff(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MaxFallDistance.set(3);
            KhaosKorpsServerConfig.DirtPathPreference.set(com.talhanation.recruits.khaoskorps.FollowSettings.DirtPathPreference.OFF);
        });
    }

    /**
     * Puts the fork's settings back after the OFF batch.
     *
     * @param level the test level
     */
    @AfterBatch(batch = FALL_THREE_DIRT_OFF)
    public static void afterFallThreeDirtOff(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * The detour planner's step search reaches a stepping stone it can walk to within its range, from
     * (38, 66, 18) to (53, 68, 11): 18.5 blocks of walking over the captured blocks, counting steps up
     * of one block and drops of up to three, within the planner's step range of 24. Recorded plans
     * failed to reach it while a point's walked distance could be overwritten by a longer route. With
     * UseAsyncPathfinding off the search is vanilla's, which this doesn't cover, and the test says so.
     */
    @GameTest(template = RAVINE, timeoutTicks = 200, batch = FALL_THREE)
    public static void aStepSearchReachesASpotWithinItsWalkingRange(GameTestHelper helper) {
        stepSearches(helper, new int[][]{{38, 66, 18, 53, 68, 11}});
    }

    /**
     * A step search from (31, 66, 21) to (15, 68, 20), 17.1 blocks of walking, with the default
     * dirt-path setting. Optional, and failing while the dirt-path cost spends the search's whole
     * allowance before it gets there; with the cost off it reaches (the OFF test below).
     */
    @GameTest(template = RAVINE, timeoutTicks = 200, batch = FALL_THREE, required = false)
    public static void aLongerStepSearchWithTheDefaultDirtPathCost(GameTestHelper helper) {
        stepSearches(helper, new int[][]{{31, 66, 21, 15, 68, 20}});
    }

    /** The same longer step search with the dirt-path cost added once per point. Optional, as above. */
    @GameTest(template = RAVINE, timeoutTicks = 200, batch = FALL_THREE_DIRT_ONCE, required = false)
    public static void aLongerStepSearchWithDirtPathOnce(GameTestHelper helper) {
        stepSearches(helper, new int[][]{{31, 66, 21, 15, 68, 20}});
    }

    /** The same longer step search with no dirt-path cost. Optional, as above. */
    @GameTest(template = RAVINE, timeoutTicks = 200, batch = FALL_THREE_DIRT_OFF, required = false)
    public static void aLongerStepSearchWithDirtPathOff(GameTestHelper helper) {
        stepSearches(helper, new int[][]{{31, 66, 21, 15, 68, 20}});
    }

    /**
     * Runs the detour planner's step search (range 24, its allowance 24 x 16 points) between each pair
     * of spots, logs whether each reached and how many points it took, and fails if one didn't reach.
     *
     * @param helper the test
     * @param pairs each row a start then an end, x, y, z relative to the arena
     */
    private static void stepSearches(GameTestHelper helper, int[][] pairs) {
        String label = "dirt path " + KhaosKorpsServerConfig.DirtPathPreference.get();
        fenceIn(helper);
        if (!com.talhanation.recruits.config.RecruitsServerConfig.UseAsyncPathfinding.get()) {
            Main.LOGGER.info("[KhaosKorps measure] step search reach: UseAsyncPathfinding is off, so vanilla's search runs; not checked");
            helper.succeed();
            return;
        }
        ServerPlayer owner = owner(helper, 48.5, 66, 40.5);
        pin(helper, owner, 48.5, 66, 40.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 38, 66, 18);
        // Recorded, so the log can say how many points each search took against its allowance.
        GroupRecorder recorder = new GroupRecorder("step-search-check", helper, owner, SIZE_X, 77, SIZE_Z, "{}", 0, 0, 0);
        GroupRecorder.Member member = recorder.add(recruit);
        net.minecraft.world.level.pathfinder.Path[] paths = new net.minecraft.world.level.pathfinder.Path[pairs.length];
        helper.runAfterDelay(2, () -> {
            for (int i = 0; i < pairs.length; i++) {
                int[] p = pairs[i];
                paths[i] = com.talhanation.recruits.khaoskorps.DetourSearch.find(helper.getLevel(), recruit,
                        helper.absolutePos(new BlockPos(p[0], p[1], p[2])), helper.absolutePos(new BlockPos(p[3], p[4], p[5])), 24, 1);
                if (paths[i] == null) throw new GameTestAssertException("setup: the step search from " + java.util.Arrays.toString(p) + " could not start");
            }
        });
        helper.onEachTick(() -> {
            if (paths[pairs.length - 1] == null) return;
            for (net.minecraft.world.level.pathfinder.Path path : paths) {
                if (path instanceof com.talhanation.recruits.pathfinding.AsyncPath async && !async.isProcessed()) return;
            }
            List<Integer> points = new ArrayList<>();
            synchronized (member) {
                for (SearchTracer.SearchRecord record : member.records) if ("detour".equals(record.kind)) points.add(record.popCount);
            }
            List<String> missed = new ArrayList<>();
            for (int i = 0; i < pairs.length; i++) {
                BlockPos end = paths[i].getEndNode() == null ? null : paths[i].getEndNode().asBlockPos().subtract(helper.absolutePos(BlockPos.ZERO));
                String what = java.util.Arrays.toString(pairs[i]) + (paths[i].canReach() ? " reached" : " ended at " + (end == null ? "nothing" : end.toShortString()))
                        + (i < points.size() ? " after taking " + points.get(i) + " of its 384 points" : "");
                Main.LOGGER.info("[KhaosKorps measure] step search, {}: {}", label, what);
                if (!paths[i].canReach()) missed.add(what);
            }
            recorder.discard();
            removeOwner(helper, owner);
            if (!missed.isEmpty()) throw new GameTestAssertException(label + ": step searches that didn't reach a stone within walking range: " + missed);
            helper.succeed();
        });
    }

    /** Barriers on the arena's border columns, from the ground up, so nobody walks off the captured box. */
    static void fenceIn(GameTestHelper helper) {
        for (int i = 0; i < Math.max(SIZE_X, SIZE_Z); i++) {
            if (i < SIZE_X) {
                wall(helper, i, 0);
                wall(helper, i, SIZE_Z - 1);
            }
            if (i < SIZE_Z) {
                wall(helper, 0, i);
                wall(helper, SIZE_X - 1, i);
            }
        }
        daytime(helper);
    }

    private static void wall(GameTestHelper helper, int x, int z) {
        int y = TOP;
        while (y > 0 && helper.getBlockState(new BlockPos(x, y, z)).isAir()) y--;
        for (int up = y + 1; up <= TOP; up++) helper.setBlock(x, up, z, Blocks.BARRIER);
    }

    /**
     * 24 recruits on the north ground (x = 34..45, z = 9 and 10) follow their owner, who stands on
     * the south side at (40.5, 66, 38.5), across the pit. Measured at tick 1200: how many reached the
     * owner (within 4 blocks), how many are in the pit or dead, and where the rest are.
     */
    @GameTest(template = RAVINE, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdFollowsAcrossTheCapturedRavineWithoutGoingIntoThePit(GameTestHelper helper) {
        fenceIn(helper);
        ServerPlayer owner = owner(helper, 40.5, 66, 38.5);
        pin(helper, owner, 40.5, 66, 38.5);
        DetourGameTests.standing(helper, owner);
        List<AbstractRecruitEntity> crowd = new ArrayList<>();
        for (int i = 0; i < 24; i++) crowd.add(followingRecruit(helper, owner, 34 + i % 12, 67, 9 + i / 12));
        int[] firstInThePit = {-1};
        helper.runAfterDelay(1, () -> assertFollowPreconditions(crowd.get(0)));
        helper.onEachTick(() -> {
            if (firstInThePit[0] >= 0) return;
            for (AbstractRecruitEntity recruit : crowd) {
                if (recruit.isAlive() && relative(helper, recruit).y < IN_THE_PIT_BELOW) {
                    firstInThePit[0] = (int) helper.getTick();
                    Main.LOGGER.info("[KhaosKorps measure] captured ravine: first recruit in the pit at tick {}, at {}",
                            helper.getTick(), describe(helper, recruit));
                    return;
                }
            }
        });
        helper.runAtTickTime(MEASURE_AT, () -> {
            int reached = 0, inPit = 0, dead = 0;
            List<String> elsewhere = new ArrayList<>();
            for (AbstractRecruitEntity recruit : crowd) {
                if (!recruit.isAlive()) dead++;
                else if (relative(helper, recruit).y < IN_THE_PIT_BELOW) inPit++;
                else if (recruit.distanceToSqr(owner) <= 4.0 * 4.0) reached++;
                else {
                    var at = relative(helper, recruit);
                    elsewhere.add(String.format("(%.0f, %.0f, %.0f)", at.x, at.y, at.z));
                }
            }
            String counts = "reached the owner " + reached + ", in the pit " + inPit + ", dead " + dead
                    + ", elsewhere " + elsewhere.size() + " " + elsewhere;
            Main.LOGGER.info("[KhaosKorps measure] captured ravine, 24 following across the pit: {}", counts);
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (inPit > 0 || dead > 0) throw new GameTestAssertException("24 recruits following across the captured ravine: " + counts);
            helper.succeed();
        });
    }
}
