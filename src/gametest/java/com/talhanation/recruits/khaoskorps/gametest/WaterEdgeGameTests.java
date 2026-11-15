package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Water and drops: a waterfall over a deep drop is a drop, water beside a deadly drop is no place to
 * walk while following, and flowing water costs more than still water.
 *
 * Ground stands at y 5 (feet at y 6). The fall distance is the default, 1 block.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class WaterEdgeGameTests {

    private static final String WIDE = "khaoskorps_empty_48x12x48";
    private static final int GROUND = 5;

    /** Stone ground at y 5 over x 0..maxX, z 0..maxZ, with stone under it. */
    private static void ground(GameTestHelper helper, int maxX, int maxZ) {
        for (int x = 0; x <= maxX; x++) {
            for (int z = 0; z <= maxZ; z++) {
                for (int y = 1; y <= GROUND; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
    }

    /** Falling water at the top of a deep shaft is a dangerous drop: it is nothing to land on. */
    @GameTest(template = WIDE, timeoutTicks = 20)
    public static void aWaterfallOverADeepDropIsADrop(GameTestHelper helper) {
        ground(helper, 6, 2);
        for (int y = 1; y <= GROUND + 1; y++) helper.setBlock(4, y, 1, Blocks.AIR);
        helper.setBlock(4, 1, 1, Blocks.STONE);
        for (int y = 2; y <= GROUND + 1; y++) {
            helper.setBlock(4, y, 1, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
        }
        BlockPos column = helper.absolutePos(new BlockPos(4, GROUND + 1, 1));
        boolean drop = EdgeProbe.isDangerousDrop(helper.getLevel(), column.getX(), column.getY(), column.getZ(), 1);
        if (!drop) throw new GameTestAssertException("a column of falling water 5 blocks deep counts as safe ground");
        helper.succeed();
    }

    /** A river's surface, flowing but not falling, over a bed within the fall distance, is not a drop. */
    @GameTest(template = WIDE, timeoutTicks = 20)
    public static void aShallowRiverIsNotADrop(GameTestHelper helper) {
        ground(helper, 6, 2);
        helper.setBlock(4, GROUND, 1, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        helper.setBlock(4, GROUND + 1, 1, Blocks.AIR);
        BlockPos column = helper.absolutePos(new BlockPos(4, GROUND + 1, 1));
        boolean drop = EdgeProbe.isDangerousDrop(helper.getLevel(), column.getX(), column.getY(), column.getZ(), 1);
        if (drop) throw new GameTestAssertException("a one-deep flowing river over its bed counts as a dangerous drop");
        helper.succeed();
    }

    /**
     * A following recruit crosses a channel through still water 12 blocks round rather than straight
     * through flowing water. A wall along z 10 leaves two crossings, each a water-filled trench: still
     * water at x 2..4, flowing water at x 8..14 fed from a source at x 14.
     */
    @GameTest(template = WIDE, timeoutTicks = 200)
    public static void aFollowerCrossesStillWaterBeforeFlowingWater(GameTestHelper helper) {
        ground(helper, 20, 20);
        for (int x = 0; x <= 20; x++) {
            boolean still = x >= 2 && x <= 4, flowing = x >= 8 && x <= 14;
            if (still || flowing) {
                helper.setBlock(x, GROUND, 10, Blocks.AIR);
                if (still) helper.setBlock(x, GROUND, 10, Blocks.WATER);
            }
            // The wall leaves open the still pool and the flowing stretch, x 8..13; the source at x 14 is under it.
            if (!still && !(x >= 8 && x <= 13)) {
                for (int y = GROUND + 1; y <= GROUND + 3; y++) helper.setBlock(x, y, 10, Blocks.STONE);
            }
        }
        helper.setBlock(14, GROUND, 10, Blocks.WATER);
        crossing(helper, 9, 4, 9, 16, path -> {
            List<Integer> waterX = waterColumns(helper, path, 10);
            if (waterX.isEmpty()) throw new GameTestAssertException("setup: the path crossed no water: " + describe(helper, path));
            if (waterX.stream().anyMatch(x -> x >= 8)) {
                throw new GameTestAssertException("the path crossed the flowing water at x " + waterX + " instead of the still water at x 2..4");
            }
        });
    }

    /**
     * A following recruit doesn't cross water within the edge margin (2 blocks) of a deadly drop, even
     * when the other crossing is 20 blocks round: a wall along z 10 ends at a cliff at x 15 and is
     * open only at x 3 and x 13, each a one-deep pool of still water; the recruit and its owner stand
     * by the cliff, either side of the wall. The edge cost beside the drop (4 at two blocks) is less
     * than the way round, so only refusing that water keeps the recruit off it.
     */
    @GameTest(template = WIDE, timeoutTicks = 200)
    public static void aFollowerDoesNotCrossWaterBesideADeadlyDrop(GameTestHelper helper) {
        ground(helper, 14, 20);
        for (int x = 0; x <= 14; x++) {
            if (x == 3 || x == 13) helper.setBlock(x, GROUND, 10, Blocks.WATER);
            else for (int y = GROUND + 1; y <= GROUND + 3; y++) helper.setBlock(x, y, 10, Blocks.STONE);
        }
        crossing(helper, 13, 4, 13, 16, path -> {
            List<Integer> waterX = waterColumns(helper, path, 10);
            if (waterX.isEmpty()) throw new GameTestAssertException("setup: the path crossed no water: " + describe(helper, path));
            if (waterX.stream().anyMatch(x -> x >= 13)) {
                BlockPos at = helper.absolutePos(new BlockPos(13, GROUND, 10));
                throw new GameTestAssertException("the path crossed water at x " + waterX + ", within 2 blocks of the drop at x 15; the probe there says "
                        + EdgeProbe.distanceToDangerousDrop(helper.getLevel(), at.getX(), at.getY(), at.getZ(), 1, 2, null)
                        + " (from feet " + GROUND + "), " + EdgeProbe.distanceToDangerousDrop(helper.getLevel(), at.getX(), at.getY() + 1, at.getZ(), 1, 2, null)
                        + " (from feet " + (GROUND + 1) + "); path " + describe(helper, path));
            }
        });
    }

    /**
     * After 60 ticks, so placed water has spread and settled, runs a following recruit's first path
     * from (fx, fz) to an owner at (tx, tz) and checks it.
     */
    private static void crossing(GameTestHelper helper, int fx, int fz, int tx, int tz, java.util.function.Consumer<Path> check) {
        ServerPlayer[] owner = new ServerPlayer[1];
        AbstractRecruitEntity[] follower = new AbstractRecruitEntity[1];
        helper.runAfterDelay(60, () -> {
            owner[0] = owner(helper, tx + 0.5, GROUND + 1, tz + 0.5);
            pin(helper, owner[0], tx + 0.5, GROUND + 1, tz + 0.5);
            follower[0] = followingRecruit(helper, owner[0], fx, GROUND + 1, fz);
        });
        helper.onEachTick(() -> {
            if (follower[0] == null) return;
            AbstractRecruitEntity recruit = follower[0];
            Path path = recruit.getNavigation().getPath();
            if (path == null || (path instanceof AsyncPath async && !async.isProcessed())) return;
            try {
                check.accept(path);
            } finally {
                recruit.discard();
                removeOwner(helper, owner[0]);
            }
            helper.succeed();
        });
    }

    /** The x of each path point in the water at z {@code channelZ}. */
    private static List<Integer> waterColumns(GameTestHelper helper, Path path, int channelZ) {
        List<Integer> xs = new ArrayList<>();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            if (node.z - origin.getZ() == channelZ && !helper.getLevel().getFluidState(node.asBlockPos()).isEmpty()) {
                xs.add(node.x - origin.getX());
            }
        }
        return xs;
    }

    private static String describe(GameTestHelper helper, Path path) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            out.append('(').append(node.x - origin.getX()).append(',').append(node.y - origin.getY()).append(',').append(node.z - origin.getZ()).append(')');
        }
        return out.toString();
    }
}
