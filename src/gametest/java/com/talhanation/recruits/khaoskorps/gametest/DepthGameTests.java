package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Going deep, at fall distance 3, where 3-block steps are ordinary walking.
 *
 * The deep ravine (48 x 12 x 48 arena): barrier walls on its edges; the north ground at stand height
 * 10 (z = 1..12); three 3-block steps down, at z = 13 (stand 7), z = 14 (stand 4) and the floor
 * (stand 1) from z = 15 to 28, wide enough that it isn't a dead end; the south ground at stand
 * height 10 again from z = 29, a 9-block wall from the floor that nothing climbs. The recruit starts
 * north at (24, 10, 6).
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DepthGameTests {

    private static final String FALL_3 = "khaoskorps_depth_fall_3";
    private static final String DROP_COST = "khaoskorps_depth_drop_cost";

    @BeforeBatch(batch = FALL_3)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = FALL_3)
    public static void afterFallThree(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = DROP_COST)
    public static void dropCost(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MaxFallDistance.set(3);
            KhaosKorpsServerConfig.DropCostPerBlock.set(4.0);
            // ORIGINAL's dirt-path cost grows each time a node is looked at, which can outweigh any one cost; OFF shows this one alone.
            KhaosKorpsServerConfig.DirtPathPreference.set(com.talhanation.recruits.khaoskorps.FollowSettings.DirtPathPreference.OFF);
        });
    }

    @AfterBatch(batch = DROP_COST)
    public static void afterDropCost(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * The owner stands on the south ground, out of reach. The ravine floor is nearer the owner in a
     * straight line than the north rim, but each block below its start counts against an unreachable
     * path's end, so the recruit stays up on the north side instead of stepping down into the ravine.
     */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 320, batch = FALL_3)
    public static void anOwnerOutOfReachAcrossADeepRavineDoesNotDrawARecruitIntoIt(GameTestHelper helper) {
        deepRavine(helper);
        ServerPlayer owner = owner(helper, 24.5, 10, 31.5);
        pin(helper, owner, 24.5, 10, 31.5);
        DetourGameTests.standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 10, 6);
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 9.99) {
                throw new GameTestAssertException("the recruit went down into the ravine toward an owner it can't reach: at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
        });
        helper.runAtTickTime(300, () -> {
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** With the owner down on the ravine floor, in reach, the recruit still goes down the steps to it. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 400, batch = FALL_3)
    public static void aRecruitStillGoesDownStepsToAnOwnerItCanReach(GameTestHelper helper) {
        deepRavine(helper);
        ServerPlayer owner = owner(helper, 24.5, 1, 20.5);
        pin(helper, owner, 24.5, 1, 20.5);
        DetourGameTests.standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 10, 6);
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("the recruit hasn't reached the owner on the ravine floor: at " + describe(helper, recruit)
                        + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * DropCostPerBlock 4: in the corridor, lanes z = 1 and 2 drop 3 blocks at x = 7; lane z = 3 stays up
     * to x = 9 and then has stairs down (stand 5, 4, then 3 from x = 12). The owner stands just below
     * the drop, so the stairs are twice as far; the recruit's path still takes them, not the drop.
     * Async searches only; with them off the setting does nothing, and the test passes without checking.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100, batch = DROP_COST)
    public static void withADropCostAPathTakesTheStairsInsteadOfTheDrop(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 6);
        for (int x = 7; x <= CORRIDOR_X_MAX; x++) {
            for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                int stand = z != 3 ? 3 : x <= 9 ? 6 : x == 10 ? 5 : x == 11 ? 4 : 3;
                for (int y = 0; y < stand; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
        ServerPlayer owner = owner(helper, 8.5, 3, 1.5);
        pin(helper, owner, 8.5, 3, 1.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 6, 2);
        Path[] first = {null};
        helper.onEachTick(() -> {
            Path now = recruit.getNavigation().getPath();
            if (first[0] == null && now != null && !(now instanceof AsyncPath async && !async.isProcessed()) && now.canReach()) {
                first[0] = now;
            }
        });
        helper.succeedWhen(() -> {
            if (!RecruitsServerConfig.UseAsyncPathfinding.get()) {
                removeOwner(helper, owner);
                return;
            }
            Path path = first[0];
            if (path == null) throw new GameTestAssertException("no finished path to the owner yet");
            for (int i = 1; i < path.getNodeCount(); i++) {
                if (path.getNode(i - 1).y - path.getNode(i).y >= 2) {
                    throw new GameTestAssertException("with a drop cost the path still drops " + (path.getNode(i - 1).y - path.getNode(i).y)
                            + " blocks: " + pathPoints(helper, path));
                }
            }
            removeOwner(helper, owner);
        });
    }

    private static void deepRavine(GameTestHelper helper) {
        for (int y = 0; y <= 11; y++) {
            for (int i = 0; i <= 47; i++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, 47, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(47, y, i, Blocks.BARRIER);
            }
        }
        for (int x = 1; x <= 46; x++) {
            for (int z = 1; z <= 46; z++) {
                int stand = z <= 12 ? 10 : z == 13 ? 7 : z == 14 ? 4 : z <= 28 ? 1 : 10;
                for (int y = 0; y < stand; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
    }
}
