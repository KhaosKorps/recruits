package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Following recruits at a cliff edge.
 *
 * Most tests use one layout: high ground at stand height 7 (x = 1..5), a one-block step down onto
 * a one-block ledge at stand height 6 (x = 6), then a drop to the floor at stand height 1
 * (x = 7..14), with the owner in the air above the drop. The recruit's target is then the floor
 * below the owner, which it can't reach at fall distance 1, so its path falls short at the ledge.
 *
 * With the defaults, such a path is cut back two blocks from the drop, and the recruit waits on
 * the high ground. With edge care off, as in 1.15.2, it steps onto the ledge, where it can slide
 * off, and a crowd pushes its members off; those tests are optional and record that behaviour.
 * Timing-sensitive tests run as copies (GameTestSupport.copies says why not attempts).
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class EdgeSafetyGameTests {

    private static final int COPIES = 5;
    private static final String TRAIL = "khaoskorps_trail_short_paths";

    private static void cliff(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        ground(helper, 6, 6, 6);
        ground(helper, 7, CORRIDOR_X_MAX, 1);
        daytime(helper);
    }

    /** With the defaults, a recruit whose owner is over the drop waits on the high ground, short of the ledge. */
    @GameTestGenerator
    public static List<TestFunction> aRecruitWaitsBackFromTheDropItsOwnerIsOver() {
        return copies("arecruitwaitsbackfromthedropitsownerisover", COPIES, 260, true,
                EdgeSafetyGameTests::waitsBackFromTheDrop);
    }

    private static void waitsBackFromTheDrop(GameTestHelper helper) {
        cliff(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5);
        pin(helper, owner, 11.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            Vec3 at = relative(helper, recruit);
            if (at.y < 6.99 || at.x > 5.3) {
                throw new GameTestAssertException("the recruit went onto the ledge or over the edge instead of waiting back: at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
        });
        helper.runAtTickTime(240, () -> {
            if (relative(helper, recruit).x < 3.0) {
                throw new GameTestAssertException("setup didn't hold: the recruit never walked toward the drop, at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** With edge care off, as in 1.15.2: the recruit steps onto the ledge and can slide off it. Optional: records the release. */
    @GameTestGenerator
    public static List<TestFunction> withEdgeCareOffARecruitSteppingOntoTheLedgeStaysOnIt() {
        return copies(EDGE_CARE_OFF_BATCH, "withedgecareoffarecruitsteppingontotheledgestaysonit", COPIES, 260, false,
                EdgeSafetyGameTests::steppingDownOntoTheLedge);
    }

    private static void steppingDownOntoTheLedge(GameTestHelper helper) {
        cliff(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5);
        pin(helper, owner, 11.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 5.99) {
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
     * One recruit, with the owner moving in the air: back and forth along z over the drop, and in
     * and out over the ledge, so the path keeps changing near the edge. With nothing to push it, a
     * fall is the recruit's own. Optional until it passes run after run.
     */
    @GameTestGenerator
    public static List<TestFunction> aLoneRecruitFollowingAMovingOwnerNeverGoesOverTheEdge() {
        return copies("alonerecruitfollowingamovingownernevergoesovertheedge", COPIES, 420, false,
                EdgeSafetyGameTests::loneRecruitUnderAMovingOwner);
    }

    private static void loneRecruitUnderAMovingOwner(GameTestHelper helper) {
        cliff(helper);
        ServerPlayer owner = owner(helper, 3.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            // x sweeps 3.5 -> 12.5 -> 3.5 over 120 ticks; z weaves across the corridor.
            double phase = (t % 120) / 120.0;
            double x = 3.5 + 9.0 * (phase < 0.5 ? phase * 2 : (1 - phase) * 2);
            double z = 2.0 + Math.sin(t / 7.0);
            Vec3 at = helper.absoluteVec(new Vec3(x, 8.5, z));
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

    /** Eight recruits and a still owner over the drop, with the defaults. Optional until it passes run after run. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 320, required = false)
    public static void aCrowdFollowingToTheLedgeLosesNobodyOverTheEdge(GameTestHelper helper) {
        crowdAtTheLedge(helper);
    }

    /** The same crowd with edge care off, as in 1.15.2. Optional: records the release. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 320, required = false, batch = EDGE_CARE_OFF_BATCH)
    public static void withEdgeCareOffACrowdFollowingToTheLedgeLosesNobodyOverTheEdge(GameTestHelper helper) {
        crowdAtTheLedge(helper);
    }

    private static void crowdAtTheLedge(GameTestHelper helper) {
        cliff(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5);
        pin(helper, owner, 11.5, 8, 2.5);
        List<AbstractRecruitEntity> crowd = new ArrayList<>();
        for (int i = 0; i < 8; i++) crowd.add(followingRecruit(helper, owner, 1 + (i % 4), 7, 1 + (i / 4) * 2));

        helper.runAtTickTime(300, () -> {
            long fell = crowd.stream().filter(r -> relative(helper, r).y < 5.99).count();
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (fell > 0) throw new GameTestAssertException(fell + " of 8 recruits ended up over the edge");
            helper.succeed();
        });
    }

    @BeforeBatch(batch = TRAIL)
    public static void shortFollowPaths(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.FollowMaxPathLength.set(8));
    }

    @AfterBatch(batch = TRAIL)
    public static void afterShortFollowPaths(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A trail one block wide beside a five-deep ditch (lanes z = 2 and 3, x = 3..12), with follow
     * paths capped at 8 blocks, so every path along it falls short of the owner at the far end.
     * The drop is beside the trail, not ahead of it, so no path is cut back: the recruit keeps
     * moving and reaches the owner.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 500, batch = TRAIL)
    public static void aRecruitFollowsAlongACliffTrailWhosePathsFallShort(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 5);
        for (int x = 3; x <= 12; x++) {
            for (int z = 2; z <= CORRIDOR_Z_MAX; z++) {
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, Blocks.AIR);
            }
        }
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 5, 1.5);
        pin(helper, owner, 14.5, 5, 1.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 5, 1);

        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit fell off the trail: at " + describe(helper, recruit));
            }
        });
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 9.0) {
                throw new GameTestAssertException("the recruit hasn't reached the owner along the trail: at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }
}
