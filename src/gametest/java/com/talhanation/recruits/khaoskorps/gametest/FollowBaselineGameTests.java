package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Follow Me as the 1.15.2 release does it: finding the owner, the start and stop distances, a
 * one-block step down, and refusing drops deeper than the fall distance. They passed on the
 * untouched release. They run with edge care off, which the release didn't have, so they prove
 * every other setting's default keeps the release's behaviour.
 *
 * The pit tests at fall distance 2 and 3 are optional: a recruit allowed to drop two blocks
 * follows its path into a pit it can't climb out of. They fail until that is fixed.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class FollowBaselineGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final double START_DISTANCE_SQR = 9.0; // 1.15.2's start distance, 3 blocks, squared
    private static final double STOP_DISTANCE_SQR = 7.0;  // 1.15.2's stop distance, about 2.65 blocks, squared
    private static final String RELEASE = "khaoskorps_release_1_15_2";

    /** The release had no edge care, so this batch turns both edge-care settings off. */
    @net.minecraft.gametest.framework.BeforeBatch(batch = RELEASE)
    public static void releaseSettings(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> {
            com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.EdgeMargin.set(false);
            com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.WaitWhenOwnerUnreachable.set(false);
        });
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = RELEASE)
    public static void afterReleaseSettings(net.minecraft.server.level.ServerLevel level) { resetForkSettings(); }

    /** The owner is found, the path takes the configured route, and the recruit catches up on flat ground. */
    @GameTest(template = T, timeoutTicks = 260, batch = RELEASE)
    public static void ownerIsFoundAndRecruitFollowsOnFlatGround(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> assertPathRoute(recruit));
        helper.succeedWhen(() -> {
            double d = recruit.distanceToSqr(owner);
            if (d > START_DISTANCE_SQR) {
                throw new GameTestAssertException("recruit at " + describe(helper, recruit)
                        + " is still " + String.format("%.2f", Math.sqrt(d)) + " blocks from the owner");
            }
            removeOwner(helper, owner);
        });
    }

    /** No follow while the owner is within 3 blocks; it follows beyond 3 and stops within about 2.65. */
    @GameTest(template = T, timeoutTicks = 200, batch = RELEASE)
    public static void followStartsBeyondThreeBlocksAndStopsWithinAboutTwoPointSix(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        // The recruit stands at x = 2.5. The owner starts 2.9 blocks away, then moves to 3.4.
        ServerPlayer owner = owner(helper, 5.4, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        Vec3 near = helper.absoluteVec(new Vec3(5.4, 3, 2.5));
        Vec3 far = helper.absoluteVec(new Vec3(5.9, 3, 2.5));
        boolean[] sawFollow = {false};

        helper.onEachTick(() -> {
            long t = helper.getTick();
            Vec3 at = t <= 40 ? near : far;
            owner.setPos(at.x, at.y, at.z);
            if (t <= 40 && recruit.isFollowing()) {
                throw new GameTestAssertException("the recruit started following with the owner "
                        + String.format("%.2f", Math.sqrt(recruit.distanceToSqr(owner))) + " blocks away, inside the start distance;"
                        + " the recruit is at " + describe(helper, recruit) + ", spawned at (2.50, 3.00, 2.50), at tick " + t);
            }
            if (recruit.isFollowing()) sawFollow[0] = true;
        });
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.succeedWhen(() -> {
            if (helper.getTick() <= 40) throw new GameTestAssertException("still in the no-follow phase");
            if (!sawFollow[0]) throw new GameTestAssertException("the recruit never started following with the owner beyond 3 blocks");
            double d = recruit.distanceToSqr(owner);
            if (recruit.isFollowing() || d > STOP_DISTANCE_SQR) {
                throw new GameTestAssertException("the recruit hasn't stopped within the stop distance: "
                        + String.format("%.2f", Math.sqrt(d)) + " blocks, following " + recruit.isFollowing()
                        + ", at " + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    /** The recruit follows down a one-block step. */
    @GameTest(template = T, timeoutTicks = 260, batch = RELEASE)
    public static void recruitFollowsDownAOneBlockStep(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 4);
        ground(helper, 7, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 4, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> assertPathRoute(recruit));
        helper.succeedWhen(() -> {
            double d = recruit.distanceToSqr(owner);
            if (d > START_DISTANCE_SQR) {
                throw new GameTestAssertException("recruit at " + describe(helper, recruit)
                        + " is still " + String.format("%.2f", Math.sqrt(d)) + " blocks from the owner");
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * At fall distance 1 the recruit walks to the rim of a two-block drop and stays on the upper
     * level, short of an owner it could reach by jumping down.
     */
    @GameTest(template = T, timeoutTicks = 320, batch = RELEASE)
    public static void recruitStopsAtTheRimOfATwoBlockDropAtFallDistanceOne(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 5);
        ground(helper, 7, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 5, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            assertPathRoute(recruit);
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit went down the two-block drop at fall distance "
                        + recruit.getMaxFallDistance() + ", now at " + describe(helper, recruit));
            }
        });
        helper.runAtTickTime(300, () -> {
            Vec3 p = relative(helper, recruit);
            if (p.x < 5.0) {
                throw new GameTestAssertException("the recruit didn't walk to the rim: at " + describe(helper, recruit));
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** At fall distance 1 the recruit never enters a two-deep pit between it and the owner. */
    @GameTest(template = T, timeoutTicks = 320, batch = RELEASE)
    public static void recruitNeverEntersATwoDeepPitAtFallDistanceOne(GameTestHelper helper) {
        pitB(helper, 1, 300);
    }

    private static final String PIT_FALL_2 = "khaoskorps_pit_fall_2";
    private static final String PIT_FALL_3 = "khaoskorps_pit_fall_3";

    @net.minecraft.gametest.framework.BeforeBatch(batch = PIT_FALL_2)
    public static void fallDistanceTwo(net.minecraft.server.level.ServerLevel level) { setFallDistance(2); }

    @net.minecraft.gametest.framework.AfterBatch(batch = PIT_FALL_2)
    public static void afterFallDistanceTwo(net.minecraft.server.level.ServerLevel level) { resetForkSettings(); }

    @net.minecraft.gametest.framework.BeforeBatch(batch = PIT_FALL_3)
    public static void fallDistanceThree(net.minecraft.server.level.ServerLevel level) { setFallDistance(3); }

    @net.minecraft.gametest.framework.AfterBatch(batch = PIT_FALL_3)
    public static void afterFallDistanceThree(net.minecraft.server.level.ServerLevel level) { resetForkSettings(); }

    private static void setFallDistance(int blocks) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.MaxFallDistance.set(blocks));
    }

    /** The same pit at fall distance 2: the drop in lands in a dead end, so the recruit stays on the rim. */
    @GameTest(template = T, timeoutTicks = 420, batch = PIT_FALL_2)
    public static void partialPathNeverEndsInATwoDeepPitAtFallDistanceTwo(GameTestHelper helper) {
        pitB(helper, 2, 400);
    }

    /** The same pit at fall distance 3: the drop in lands in a dead end, so the recruit stays on the rim. */
    @GameTest(template = T, timeoutTicks = 420, batch = PIT_FALL_3)
    public static void partialPathNeverEndsInATwoDeepPitAtFallDistanceThree(GameTestHelper helper) {
        pitB(helper, 3, 400);
    }

    /**
     * The dead-end check doesn't keep a recruit from its owner: with the owner standing in a closed
     * lower area (a two-block drop with no way back up, smaller than the search for a way out), the
     * recruit at fall distance 2 drops in and reaches the owner, since the owner is what it's after.
     */
    @GameTest(template = T, timeoutTicks = 300, batch = PIT_FALL_2)
    public static void aRecruitFollowsItsOwnerDownIntoAClosedLowerArea(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 3);
        ground(helper, 6, CORRIDOR_X_MAX, 1);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 1, 2.5);
        pin(helper, owner, 12.5, 1, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.0 * 3.0) {
                throw new GameTestAssertException("the recruit hasn't followed the owner down: at " + describe(helper, recruit)
                        + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * The pit: ground at stand height 3 along the corridor, with a pit across
     * its full width at x = 6..8 whose floor is at stand height 1. The far pit floor (8, 1, 2) is
     * nearer the owner at (12, 3, 2) than any rim node, so a partial path prefers it, and the
     * two-block wall out exceeds the 1.125 step-up limit.
     */
    private static void pitB(GameTestHelper helper, int fallDistance, int checkAtTick) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 3);
        ground(helper, 6, 8, 1);
        ground(helper, 9, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        // The fall distance comes from the batch's server setting, which wins over the recruit's own value.

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            assertPathRoute(recruit);
            if (relative(helper, recruit).y < 2.99) {
                throw new GameTestAssertException("at fall distance " + fallDistance
                        + " the recruit entered the pit: at " + describe(helper, recruit)
                        + ", using fall distance " + recruit.getMaxFallDistance());
            }
        });
        helper.runAtTickTime(checkAtTick, () -> {
            if (recruit.getMaxFallDistance() != fallDistance) {
                throw new GameTestAssertException("setup didn't hold: fall distance is "
                        + recruit.getMaxFallDistance() + ", expected " + fallDistance);
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }
}
