package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Following across a bridge one block wide over a ravine, with the owner on the far side, in line
 * with the bridge or off to one side of it.
 *
 * The arena (48 x 12 x 48): barrier walls on its edges; ground at stand height 5; a ravine five deep
 * and six wide (z = 20..25) across the whole arena, crossed by a bridge one block wide at x = 24.
 * The recruit starts north of the ravine at (24, 5, 12).
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class BridgeGameTests {

    static void bridgeArena(GameTestHelper helper) {
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
                boolean ravine = z >= 20 && z <= 25 && x != 24;
                if (ravine) continue;
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
    }

    /** With the owner straight across, a following recruit crosses the bridge without falling. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 900)
    public static void aRecruitCrossesABridgeToAnOwnerInLineWithIt(GameTestHelper helper) {
        crossTo(helper, 24.5, 33.5);
    }

    /** With the owner six blocks off to one side, it still crosses, rather than turning back at the bridge. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 900)
    public static void aRecruitCrossesABridgeToAnOwnerOffToOneSide(GameTestHelper helper) {
        crossTo(helper, 30.5, 33.5);
    }

    private static final String FALL_THREE = "khaoskorps_bridge_fall_3";

    @net.minecraft.gametest.framework.BeforeBatch(batch = FALL_THREE)
    public static void fallThree(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(net.minecraft.server.level.ServerLevel level) {
        resetForkSettings();
    }

    /** At fall distance 3, as on the user's test server, with the owner straight across. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 900, batch = FALL_THREE)
    public static void atFallDistanceThreeARecruitCrossesABridgeToAnOwnerInLineWithIt(GameTestHelper helper) {
        crossTo(helper, 24.5, 33.5);
    }

    /** At fall distance 3, with the owner off to one side. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 900, batch = FALL_THREE)
    public static void atFallDistanceThreeARecruitCrossesABridgeToAnOwnerOffToOneSide(GameTestHelper helper) {
        crossTo(helper, 30.5, 33.5);
    }

    private static void crossTo(GameTestHelper helper, double ownerX, double ownerZ) {
        bridgeArena(helper);
        ServerPlayer owner = owner(helper, ownerX, 5, ownerZ);
        pin(helper, owner, ownerX, 5, ownerZ);
        DetourGameTests.standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);
        double[] furthestSouth = {0};

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit fell off the bridge at tick " + helper.getTick() + ": at " + describe(helper, recruit));
            }
            furthestSouth[0] = Math.max(furthestSouth[0], relative(helper, recruit).z);
        });
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("at tick " + helper.getTick() + " the recruit hasn't crossed to the owner: at "
                        + describe(helper, recruit) + ", furthest south " + String.format("%.1f", furthestSouth[0])
                        + "; " + navigationState(helper, recruit));
            }
            Main.LOGGER.info("[KhaosKorps measure] a recruit crossed the bridge to an owner at x {} by tick {}", ownerX, helper.getTick());
            removeOwner(helper, owner);
        });
    }
}
