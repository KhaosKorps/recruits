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
 * Following around an obstacle too large for one search: a ravine between a recruit and its owner,
 * with a way round at one end.
 *
 * The arena (48 x 12 x 48): barrier walls on its edges; ground at stand height 5; a ravine five
 * deep and six wide (z = 20..25) from the west wall to x = 36, leaving a gap at x = 37..46. The
 * recruit starts north of the ravine at (24, 5, 12); the owner stands south of it at
 * (24.5, 5, 33.5), 21.5 blocks away in a straight line and about 53 blocks of walking round.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DetourGameTests {

    static final String ARENA = "khaoskorps_empty_48x12x48";

    static void ravineArena(GameTestHelper helper) {
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
                boolean ravine = z >= 20 && z <= 25 && x <= 36;
                if (ravine) continue;
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
    }

    /** A following recruit finds its way round the ravine to the owner, without falling in. Optional until the detour search is wired. */
    @GameTest(template = ARENA, timeoutTicks = 1200, required = false)
    public static void aFollowingRecruitFindsItsWayAroundARavine(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit fell into the ravine at tick " + helper.getTick()
                        + ": at " + describe(helper, recruit));
            }
        });
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("at tick " + helper.getTick() + " the recruit is still "
                        + String.format("%.1f", Math.sqrt(recruit.distanceToSqr(owner))) + " blocks from the owner, at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }
}
