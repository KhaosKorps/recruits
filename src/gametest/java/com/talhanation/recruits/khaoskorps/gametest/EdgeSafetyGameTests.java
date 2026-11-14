package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Following recruits at a cliff edge. Every test uses one layout: high ground, a one-block step
 * down onto a one-block ledge, then a five-block drop, with the owner in the air above the drop.
 * A recruit that steps down onto the ledge at walking speed can slide off it, and a crowd pushes
 * its members off.
 *
 * Optional until a fix lands: these document today's behaviour. The single-recruit tests run as
 * several copies at once, because a slide depends on speed and timing (GameTestSupport.copies says
 * why copies and not attempts).
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class EdgeSafetyGameTests {

    private static final int COPIES = 5;

    /**
     * High ground at stand height 7 (x = 1..5), a one-block step down onto a one-block ledge at
     * stand height 6 (x = 6), then a drop of five to the floor (x = 7..14, stand height 1).
     * The owner hovers over the drop, so the recruit's target is the floor below the owner
     * (AsyncGroundPathNavigation.createPath), which it can't reach at fall distance 1: its path
     * ends on the ledge, right after the step down.
     */
    @GameTestGenerator
    public static List<TestFunction> aRecruitSteppingDownOntoACliffEdgeLedgeStaysOnIt() {
        return copies("arecruitsteppingdownontoacliffedgeledgestaysonit", COPIES, 260, false,
                EdgeSafetyGameTests::steppingDownOntoTheLedge);
    }

    private static void steppingDownOntoTheLedge(GameTestHelper helper) {
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
    @GameTestGenerator
    public static List<TestFunction> aLoneRecruitFollowingAMovingOwnerNeverGoesOverTheEdge() {
        return copies("alonerecruitfollowingamovingownernevergoesovertheedge", COPIES, 420, false,
                EdgeSafetyGameTests::loneRecruitUnderAMovingOwner);
    }

    private static void loneRecruitUnderAMovingOwner(GameTestHelper helper) {
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
