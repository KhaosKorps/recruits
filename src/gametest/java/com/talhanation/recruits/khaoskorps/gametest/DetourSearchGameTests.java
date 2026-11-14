package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.DetourSearch;
import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * DetourSearch: a walking search that starts at a chosen position, not where the mob stands, on
 * whichever route UseAsyncPathfinding selects. The recruit stands at x = 2 in the corridor; each
 * search starts at x = 8.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DetourSearchGameTests {

    /** A search from x = 8 to x = 13 starts at x = 8 and reaches x = 13. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void aDetourSearchStartsWhereItIsToldNotWhereTheRecruitStands(GameTestHelper helper) {
        corridor(helper);
        searchOnceDone(helper, path -> {
            BlockPos first = relativeBlock(helper, path.getNode(0).asBlockPos());
            if (!first.equals(new BlockPos(8, 3, 2))) {
                throw new GameTestAssertException("the search started at " + first.toShortString()
                        + ", not at 8, 3, 2; the recruit stands at 2, 3, 2: " + pathPoints(helper, path));
            }
            if (!path.canReach()) {
                throw new GameTestAssertException("the search didn't reach 13, 3, 2: " + pathPoints(helper, path));
            }
        });
    }

    /** With a full-height wall across the corridor at x = 11, the same search can't reach x = 13. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void aDetourSearchReportsAWallItCantCross(GameTestHelper helper) {
        corridor(helper);
        for (int y = 3; y <= WALL_TOP; y++) {
            for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) helper.setBlock(11, y, z, Blocks.STONE);
        }
        searchOnceDone(helper, path -> {
            if (path.canReach()) {
                throw new GameTestAssertException("the search reached 13, 3, 2 through a wall: " + pathPoints(helper, path));
            }
        });
    }

    private static void corridor(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
    }

    /** Starts the search from x = 8 to x = 13 for a recruit at x = 2, and checks the path once it is done. */
    private static void searchOnceDone(GameTestHelper helper, Consumer<Path> check) {
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setAggroState(3);
        Path[] path = {null};
        helper.runAfterDelay(1, () -> {
            path[0] = DetourSearch.find(helper.getLevel(), recruit, helper.absolutePos(new BlockPos(8, 3, 2)),
                    helper.absolutePos(new BlockPos(13, 3, 2)), 12, 0);
            if (path[0] == null) throw new GameTestAssertException("the search couldn't start at 8, 3, 2");
        });
        helper.onEachTick(() -> {
            if (path[0] == null || (path[0] instanceof AsyncPath async && !async.isProcessed())) return;
            check.accept(path[0]);
            recruit.discard();
            helper.succeed();
        });
    }

    private static BlockPos relativeBlock(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }
}
