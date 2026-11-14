package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * EdgeProbe on real blocks. The layout: high ground at stand height 7 (x = 1..5), a one-block
 * step down onto a one-block ledge at stand height 6 (x = 6), then a drop to the floor at stand
 * height 1 (x = 7..14): six blocks from the high ground, five from the ledge.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class EdgeProbeGameTests {

    private static final int Z = 2;

    private static void cliff(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        ground(helper, 6, 6, 6);
        ground(helper, 7, CORRIDOR_X_MAX, 1);
    }

    /** A step down the fall distance allows is no drop; one deeper is; a fall distance as deep as the drop makes it safe. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void onlyAFallDeeperThanTheFallDistanceIsADangerousDrop(GameTestHelper helper) {
        cliff(helper);
        check(!dangerous(helper, 6, 7, 1), "from the high ground, the one-block step onto the ledge is a drop at fall distance 1");
        check(dangerous(helper, 7, 7, 1), "from the high ground, the six-block fall to the floor isn't a drop at fall distance 1");
        check(!dangerous(helper, 7, 7, 6), "from the high ground, the six-block fall is a drop at fall distance 6");
        check(dangerous(helper, 7, 7, 5), "from the high ground, the six-block fall isn't a drop at fall distance 5");
        helper.succeed();
    }

    /** The distance is the square ring the nearest dangerous drop lies on, or NONE beyond the radius. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void theDistanceCountsBlocksToTheNearestDangerousDrop(GameTestHelper helper) {
        cliff(helper);
        expectDistance(helper, 6, 6, 2, 1, "on the ledge");
        expectDistance(helper, 5, 7, 2, 2, "on the high ground's rim");
        expectDistance(helper, 4, 7, 2, EdgeProbe.NONE, "a block back from the rim, radius 2");
        expectDistance(helper, 4, 7, 3, 3, "a block back from the rim, radius 3");
        helper.succeed();
    }

    /**
     * A drop behind a wall doesn't count: a mob can't reach it. Outside the barrier walls the
     * template is open down to the world's floor, so from three blocks in, the only drops within
     * three blocks lie behind the wall.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aDropBehindAWallIsNotCounted(GameTestHelper helper) {
        cliff(helper);
        check(dangerous(helper, 3, 7, CORRIDOR_Z_MAX + 2, 1), "setup: the open space behind the wall isn't a drop");
        expectDistance(helper, 3, 7, 3, EdgeProbe.NONE, "three blocks in, with only drops behind the wall within 3");
        helper.succeed();
    }

    /**
     * A staircase down a block a step is walkable, so it is no drop: from the top step, nothing
     * within three blocks counts at fall distance 1, though the third step is three blocks lower.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aWalkableSlopeIsNotADrop(GameTestHelper helper) {
        stairsDown(helper);
        expectDistance(helper, 2, 7, 3, EdgeProbe.NONE, "on the top step of a staircase going down a block a step");
        helper.succeed();
    }

    /**
     * Lava is deadly, so it counts as a dangerous drop however shallow: a column whose ground is lava
     * one block down, and a column of lava level with the ground, beside flat ground at stand height 5.
     * Water one block down stays safe. The lava and water are removed again before they can flow.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void lavaIsADangerousDropAndWaterIsNot(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 5);
        helper.setBlock(8, 4, Z, Blocks.LAVA);
        helper.setBlock(11, 5, Z, Blocks.LAVA);
        helper.setBlock(4, 4, Z, Blocks.WATER);
        boolean lavaBelow = dangerous(helper, 8, 5, 1);
        boolean lavaLevel = dangerous(helper, 11, 5, 1);
        boolean waterBelow = dangerous(helper, 4, 5, 1);
        helper.setBlock(8, 4, Z, Blocks.STONE);
        helper.setBlock(11, 5, Z, Blocks.AIR);
        helper.setBlock(4, 4, Z, Blocks.STONE);
        check(lavaBelow, "a column with lava one block down isn't a dangerous drop");
        check(lavaLevel, "a column of lava level with the ground isn't a dangerous drop");
        check(!waterBelow, "a column with water one block down counts as a dangerous drop");
        helper.succeed();
    }

    /** Barrier walls and water are not drops: one can't be walked into, the other is swum in. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void wallsAndWaterAreNotDrops(GameTestHelper helper) {
        cliff(helper);
        check(!dangerous(helper, 3, 7, CORRIDOR_Z_MIN - 1, 1), "the barrier wall beside the high ground counts as a drop");
        helper.setBlock(7, 5, Z, Blocks.WATER);
        boolean waterIsADrop = dangerous(helper, 7, 6, 1);
        helper.setBlock(7, 5, Z, Blocks.AIR); // before it flows: no test leaves water behind
        check(!waterIsADrop, "from the ledge, a column with water a block down counts as a drop");
        helper.succeed();
    }

    private static boolean dangerous(GameTestHelper helper, int x, int feetY, int maxFall) {
        return dangerous(helper, x, feetY, Z, maxFall);
    }

    private static boolean dangerous(GameTestHelper helper, int x, int feetY, int z, int maxFall) {
        BlockPos at = helper.absolutePos(new BlockPos(x, feetY, z));
        return EdgeProbe.isDangerousDrop(helper.getLevel(), at.getX(), at.getY(), at.getZ(), maxFall);
    }

    private static void expectDistance(GameTestHelper helper, int x, int feetY, int radius, int expected, String where) {
        BlockPos at = helper.absolutePos(new BlockPos(x, feetY, Z));
        int distance = EdgeProbe.distanceToDangerousDrop(helper.getLevel(), at.getX(), at.getY(), at.getZ(), 1, radius, null);
        if (distance != expected) {
            throw new GameTestAssertException(where + " at fall distance 1: the nearest dangerous drop is "
                    + distance + " blocks away, expected " + expected);
        }
    }

    private static void check(boolean ok, String failure) {
        if (!ok) throw new GameTestAssertException(failure);
    }
}
