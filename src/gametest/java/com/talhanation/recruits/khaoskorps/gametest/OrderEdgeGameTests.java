package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Move and hold orders keep back from drops like following does (EdgeMarginOnOrders), while the spot the player
 * ordered may itself lie at an edge. The scene: the walled corridor, ground at stand height 3, cut across by a
 * trench 3 deep at x = 7..9, so the rim the recruit stands on is the column x = 6.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class OrderEdgeGameTests {

    /** A move order to a spot past the trench, which can't be reached: by tick 300 the recruit waits back from the rim (x 5.6 or less). */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 360)
    public static void aMoveOrderToASpotPastATrenchStopsShortOfTheRim(GameTestHelper helper) {
        trench(helper);
        AbstractRecruitEntity recruit = recruit(helper);
        recruit.setMovePos(helper.absolutePos(new BlockPos(12, 3, 2)));
        recruit.setShouldMovePos(true);
        helper.runAfterDelay(300, () -> {
            Vec3 at = relative(helper, recruit);
            String report = String.format(Locale.ROOT, "move order past the trench: at tick 300 the recruit is at x %.2f (the rim is x 6)", at.x);
            Main.LOGGER.info("[KhaosKorps measure] {}", report);
            recruit.discard();
            if (at.x > 5.6) throw new GameTestAssertException("it went to the rim: " + report);
            helper.succeed();
        });
    }

    /** A hold order on the rim itself, the player's choice: by tick 200 the recruit stands there, within 0.8 blocks. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 260)
    public static void aHoldOrderOnTheRimHoldsThere(GameTestHelper helper) {
        trench(helper);
        AbstractRecruitEntity recruit = recruit(helper);
        Vec3 spot = helper.absoluteVec(new Vec3(6.5, 3, 2.5));
        recruit.setHoldPos(spot);
        recruit.setFollowState(3);
        helper.runAfterDelay(200, () -> {
            double off = Math.sqrt(recruit.distanceToSqr(spot));
            String report = String.format(Locale.ROOT, "hold order on the rim: at tick 200 the recruit is %.2f blocks from the spot, at %s", off, describe(helper, recruit));
            Main.LOGGER.info("[KhaosKorps measure] {}", report);
            recruit.discard();
            if (off > 0.8) throw new GameTestAssertException("it didn't go to the ordered spot: " + report);
            helper.succeed();
        });
    }

    private static void trench(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 3);
        ground(helper, 10, CORRIDOR_X_MAX, 3);
        daytime(helper);
    }

    /** A plain recruit standing west at x = 3, passive, so nothing but the order moves it. */
    private static AbstractRecruitEntity recruit(GameTestHelper helper) {
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 3, 3, 2);
        recruit.setAggroState(3);
        recruit.setFollowState(0);
        return recruit;
    }
}
