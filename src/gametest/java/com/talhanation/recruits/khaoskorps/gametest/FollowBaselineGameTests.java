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
 * KhaosKorps fork: Follow Me as the 1.15.2 release does it, pinned before any pathfinding change.
 *
 * These are Nena's G0 and G1 (AUDT-261113-Recruits_Test_Audit_And_Test_Plan_By_Nena). They must
 * pass on the untouched release first; later they prove that every new setting's default keeps
 * today's behaviour. The pit tests at fall distance 2 and 3 are optional: they document the risk
 * a longer drop opens (Nena's N2), and are expected to fail until that is fixed.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class FollowBaselineGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final double START_DISTANCE_SQR = 9.0; // RecruitFollowOwnerGoal.java:23
    private static final double STOP_DISTANCE_SQR = 7.0;  // RecruitFollowOwnerGoal.java:24

    /** G0: the owner is found, the path takes the configured route, and the recruit catches up on flat ground. */
    @GameTest(template = T, timeoutTicks = 260)
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

    /** G1-1: no follow while the owner is within 3 blocks; it follows beyond 3 and stops within about 2.65. */
    @GameTest(template = T, timeoutTicks = 200)
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
                        + String.format("%.2f", Math.sqrt(recruit.distanceToSqr(owner))) + " blocks away, inside the start distance");
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
                        + String.format("%.2f", Math.sqrt(d)) + " blocks, following " + recruit.isFollowing());
            }
            removeOwner(helper, owner);
        });
    }

    /** G1-2: the recruit follows down a one-block step. */
    @GameTest(template = T, timeoutTicks = 260)
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
     * G1-3: at fall distance 1 the recruit walks to the rim of a two-block drop and stays on the
     * upper level. This is the player's "2 block drop movement error" as today's behaviour.
     */
    @GameTest(template = T, timeoutTicks = 320)
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

    /** G1-4: at fall distance 1 the recruit never enters a two-deep pit (Nena's pit B). */
    @GameTest(template = T, timeoutTicks = 320)
    public static void recruitNeverEntersATwoDeepPitAtFallDistanceOne(GameTestHelper helper) {
        pitB(helper, 1, 300);
    }

    private static final String PIT_FALL_2 = "khaoskorps_pit_fall_2";
    private static final String PIT_FALL_3 = "khaoskorps_pit_fall_3";

    @net.minecraft.gametest.framework.BeforeBatch(batch = PIT_FALL_2)
    public static void fallDistanceTwo(net.minecraft.server.level.ServerLevel level) { setFallDistance(2); }

    @net.minecraft.gametest.framework.AfterBatch(batch = PIT_FALL_2)
    public static void afterFallDistanceTwo(net.minecraft.server.level.ServerLevel level) { setFallDistance(1); }

    @net.minecraft.gametest.framework.BeforeBatch(batch = PIT_FALL_3)
    public static void fallDistanceThree(net.minecraft.server.level.ServerLevel level) { setFallDistance(3); }

    @net.minecraft.gametest.framework.AfterBatch(batch = PIT_FALL_3)
    public static void afterFallDistanceThree(net.minecraft.server.level.ServerLevel level) { setFallDistance(1); }

    private static void setFallDistance(int blocks) {
        com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.MaxFallDistance.set(blocks);
        com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.republish();
    }

    /** Nena's G4-3 at fall distance 2: expected to fail until the pit fix (N2), so optional. */
    @GameTest(template = T, timeoutTicks = 420, required = false, batch = PIT_FALL_2)
    public static void partialPathNeverEndsInATwoDeepPitAtFallDistanceTwo(GameTestHelper helper) {
        pitB(helper, 2, 400);
    }

    /** Nena's G4-3 at fall distance 3: expected to fail until the pit fix (N2), so optional. */
    @GameTest(template = T, timeoutTicks = 420, required = false, batch = PIT_FALL_3)
    public static void partialPathNeverEndsInATwoDeepPitAtFallDistanceThree(GameTestHelper helper) {
        pitB(helper, 3, 400);
    }

    /**
     * Pit B (Nena's coordinates): ground at stand height 3 along the corridor, with a pit across
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
                        + " the recruit entered the pit: at " + describe(helper, recruit));
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
