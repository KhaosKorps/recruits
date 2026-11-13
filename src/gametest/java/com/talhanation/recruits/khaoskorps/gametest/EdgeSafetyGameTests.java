package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * KhaosKorps fork: the user's ravine observations
 * (DRFT-261113-Recruits_Follow_Pathfinding_Settings_And_Measurement, section 1): following
 * recruits fall into a ravine the owner flies over, and "where they are steping down one block
 * like a step down and that lower step is the cliff edge, moving to fast they dont stop fast
 * enough".
 *
 * Optional until a fix lands: these document today's behaviour. They run several attempts,
 * because a slide depends on speed and timing.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class EdgeSafetyGameTests {

    /**
     * High ground at stand height 7 (x = 1..5), a one-block step down onto a one-block ledge at
     * stand height 6 (x = 6), then a drop of five to the floor (x = 7..14, stand height 1).
     * The owner hovers over the drop, so the recruit's target is the floor below the owner
     * (AsyncGroundPathNavigation.createPath), which it can't reach at fall distance 1: its path
     * ends on the ledge, right after the step down.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 260, required = false, attempts = 5, requiredSuccesses = 5)
    public static void aRecruitSteppingDownOntoACliffEdgeLedgeStaysOnIt(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        ground(helper, 6, 6, 6);
        ground(helper, 7, CORRIDOR_X_MAX, 1);
        daytime(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5); // in the air above the drop
        pin(helper, owner, 11.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);
        double[] lowestY = {Double.MAX_VALUE};

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            double y = relative(helper, recruit).y;
            lowestY[0] = Math.min(lowestY[0], y);
            if (y < 5.99) {
                throw new GameTestAssertException("the recruit went over the edge after stepping down: now at "
                        + describe(helper, recruit) + ", fall distance " + recruit.getMaxFallDistance());
            }
        });
        helper.runAtTickTime(240, () -> {
            if (relative(helper, recruit).x < 5.0) {
                throw new GameTestAssertException("setup didn't hold: the recruit never stepped down toward the ledge, at "
                        + describe(helper, recruit));
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /**
     * The same ledge, one recruit, but the owner keeps moving in the air: back and forth along
     * z over the drop, and in and out over the ledge, so the recruit's path keeps changing while
     * it is near the edge. With a single recruit nothing can push it, so a fall here is its own.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 420, required = false, attempts = 5, requiredSuccesses = 5)
    public static void aLoneRecruitFollowingAMovingOwnerNeverGoesOverTheEdge(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        ground(helper, 6, 6, 6);
        ground(helper, 7, CORRIDOR_X_MAX, 1);
        daytime(helper);
        ServerPlayer owner = owner(helper, 3.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            // x sweeps 3.5 -> 12.5 -> 3.5 over 120 ticks; z weaves across the corridor.
            double phase = (t % 120) / 120.0;
            double x = 3.5 + 9.0 * (phase < 0.5 ? phase * 2 : (1 - phase) * 2);
            double z = 2.0 + Math.sin(t / 7.0);
            var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, 8.5, z));
            owner.setPos(at.x, at.y, at.z);
            if (relative(helper, recruit).y < 5.99) {
                throw new GameTestAssertException("with nobody to push it, the recruit went over the edge at tick " + t
                        + ": now at " + describe(helper, recruit));
            }
        });
        helper.runAtTickTime(400, () -> {
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** Eight recruits and a still owner over the drop: how many end up over the edge, pushing included. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 320, required = false)
    public static void aCrowdFollowingToTheLedgeLosesNobodyOverTheEdge(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        ground(helper, 6, 6, 6);
        ground(helper, 7, CORRIDOR_X_MAX, 1);
        daytime(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5);
        pin(helper, owner, 11.5, 8, 2.5);
        java.util.List<AbstractRecruitEntity> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) crowd.add(followingRecruit(helper, owner, 1 + (i % 4), 7, 1 + (i / 4) * 2));

        helper.runAtTickTime(300, () -> {
            long fell = crowd.stream().filter(r -> relative(helper, r).y < 5.99).count();
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (fell > 0) throw new GameTestAssertException(fell + " of 8 recruits ended up over the edge");
            helper.succeed();
        });
    }
}
