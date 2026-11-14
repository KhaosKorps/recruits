package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * A one-block step up under a ceiling of leaves, where a recruit (1.95 blocks tall) has room to
 * stand on the step but perhaps not to jump onto it.
 *
 * The arena (48 x 12 x 48): barrier walls on its edges; lower ground at stand height 3 (z = 1..23),
 * upper ground at stand height 4 (z = 24..46), so the step is between z = 23 and z = 24. Persistent
 * oak leaves fill one layer over z = 21..26 for x = 1 to a given x. The recruit starts on the lower
 * ground at (24, 3, 10); its owner stands on the upper ground at (24.5, 4, 36.5).
 *
 * Optional for now: they measure what recruits do, and become required once the behaviour is settled.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class LowCeilingGameTests {

    private static final String ARENA = DetourGameTests.ARENA;

    private static void stepUnderLeaves(GameTestHelper helper, int ceilingY, int ceilingToX) {
        for (int y = 0; y <= 11; y++) {
            for (int i = 0; i <= 47; i++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, 47, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(47, y, i, Blocks.BARRIER);
            }
        }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = 1; x <= 46; x++) {
            for (int z = 1; z <= 46; z++) {
                int stand = z <= 23 ? 3 : 4;
                for (int y = 0; y < stand; y++) helper.setBlock(x, y, z, Blocks.STONE);
                if (x <= ceilingToX && z >= 21 && z <= 26) helper.setBlock(x, ceilingY, z, leaves);
            }
        }
        daytime(helper);
    }

    private static void followUpTheStep(GameTestHelper helper, String setup) {
        ServerPlayer owner = owner(helper, 24.5, 4, 36.5);
        pin(helper, owner, 24.5, 4, 36.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 3, 10);
        long[] underCeiling = {0};
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            double z = relative(helper, recruit).z;
            if (z >= 21.0 && z < 24.0) underCeiling[0]++;
        });
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException(setup + ": at tick " + helper.getTick() + " the recruit is at "
                        + describe(helper, recruit) + ", " + underCeiling[0] + " ticks so far below the step under the leaves; "
                        + navigationState(helper, recruit));
            }
            Main.LOGGER.info("[KhaosKorps measure] {}: reached its owner at tick {}, {} ticks below the step under the leaves",
                    setup, helper.getTick(), underCeiling[0]);
            removeOwner(helper, owner);
        });
    }

    /** Leaves 3 blocks above the lower ground, everywhere: the only way up is a jump with its head against the leaves. */
    @GameTest(template = ARENA, timeoutTicks = 500, required = false)
    public static void underLeavesThreeBlocksUpARecruitStepsUpToItsOwner(GameTestHelper helper) {
        stepUnderLeaves(helper, 6, 46);
        followUpTheStep(helper, "leaves 3 up everywhere");
    }

    /** Leaves 4 blocks above the lower ground, everywhere: room to jump. */
    @GameTest(template = ARENA, timeoutTicks = 500, required = false)
    public static void underLeavesFourBlocksUpARecruitStepsUpToItsOwner(GameTestHelper helper) {
        stepUnderLeaves(helper, 7, 46);
        followUpTheStep(helper, "leaves 4 up everywhere");
    }

    /** Leaves 3 blocks up for x = 1..34, none beyond: a clear step 11 blocks east of the straight way. */
    @GameTest(template = ARENA, timeoutTicks = 700, required = false)
    public static void withAClearStepBesideLowLeavesARecruitReachesItsOwner(GameTestHelper helper) {
        stepUnderLeaves(helper, 6, 34);
        followUpTheStep(helper, "leaves 3 up, clear from x 35");
    }
}
