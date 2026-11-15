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

    private static final String SWIM_TEN = "khaoskorps_water_swim_10";

    /** The test server's swimming: SwimSpeedMultiplier 10. */
    @net.minecraft.gametest.framework.BeforeBatch(batch = SWIM_TEN)
    public static void swimTen(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.SwimSpeedMultiplier.set(10.0));
    }

    /** Puts the fork's settings back after the swim batch. */
    @net.minecraft.gametest.framework.AfterBatch(batch = SWIM_TEN)
    public static void afterSwimTen(net.minecraft.server.level.ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A following recruit already in a stream near a waterfall gets out to the bank instead of being
     * carried over: the stream, 3 wide (z 9..11), runs from sources at x 7 to a cliff at x 15 and pours
     * over it; the recruit starts in its middle at x 13, two blocks from the edge, and its owner stands
     * on the bank. Swimming is the test server's, x10.
     */
    @GameTest(template = WIDE, timeoutTicks = 300, batch = SWIM_TEN)
    public static void aFollowerInAStreamBesideAWaterfallGetsOut(GameTestHelper helper) {
        ground(helper, 14, 20);
        for (int z = 9; z <= 11; z++) {
            for (int x = 0; x <= 14; x++) helper.setBlock(x, GROUND, z, x >= 7 ? Blocks.AIR : Blocks.STONE);
            helper.setBlock(7, GROUND, z, Blocks.WATER);
        }
        // The owner is made and pinned now: pin schedules a task for every tick, which a delayed task mustn't do.
        ServerPlayer[] owner = {owner(helper, 12.5, GROUND + 1, 16.5)};
        pin(helper, owner[0], 12.5, GROUND + 1, 16.5);
        AbstractRecruitEntity[] follower = new AbstractRecruitEntity[1];
        helper.runAfterDelay(60, () -> follower[0] = followingRecruit(helper, owner[0], 13, GROUND, 10));
        helper.onEachTick(() -> {
            AbstractRecruitEntity recruit = follower[0];
            if (recruit == null) return;
            net.minecraft.world.phys.Vec3 at = recruit.position().subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
            if (at.y < GROUND - 0.5 || at.x > 15.0) {
                recruit.discard();
                removeOwner(helper, owner[0]);
                throw new GameTestAssertException(String.format(java.util.Locale.ROOT,
                        "the recruit was carried over the edge: at (%.1f, %.1f, %.1f) at tick %d", at.x, at.y, at.z, helper.getTick()));
            }
            if (at.z >= 12.0 && at.y >= GROUND + 0.9) {
                recruit.discard();
                removeOwner(helper, owner[0]);
                helper.succeed();
            }
        });
    }

    /**
     * A following recruit's search, starting in water a block from a waterfall, finds a way out: all the
     * water round it lies within the edge margin, where a search from dry ground refuses to go. The
     * recruit is held in place in a still stream of source blocks (z 9..11, x 7..14) that spills over a
     * cliff at x 15; its owner stands on the bank.
     */
    @GameTest(template = WIDE, timeoutTicks = 200)
    public static void aSearchFromWaterBesideADropFindsAWayOut(GameTestHelper helper) {
        ground(helper, 14, 20);
        for (int z = 9; z <= 11; z++) {
            for (int x = 7; x <= 14; x++) helper.setBlock(x, GROUND, z, Blocks.WATER);
        }
        ServerPlayer owner = owner(helper, 12.5, GROUND + 1, 16.5);
        pin(helper, owner, 12.5, GROUND + 1, 16.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 14, GROUND, 10);
        net.minecraft.world.phys.Vec3 hold = helper.absoluteVec(new net.minecraft.world.phys.Vec3(14.5, GROUND, 10.5));
        helper.onEachTick(() -> {
            recruit.setPos(hold.x, hold.y, hold.z);
            recruit.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if (helper.getTick() < 20) return;
            Path path = recruit.getNavigation().getPath();
            if (path == null || (path instanceof AsyncPath async && !async.isProcessed())) return;
            String route = describe(helper, path);
            BlockPos end = path.getEndNode() == null ? null : path.getEndNode().asBlockPos().subtract(helper.absolutePos(BlockPos.ZERO));
            recruit.discard();
            removeOwner(helper, owner);
            if (end == null || end.getZ() < 12) {
                throw new GameTestAssertException("the search from the water found no way to the bank: " + route);
            }
            helper.succeed();
        });
    }

    /**
     * After 60 ticks, so placed water has spread and settled, runs a following recruit's first path
     * from (fx, fz) to an owner at (tx, tz) and checks it.
     */
    private static void crossing(GameTestHelper helper, int fx, int fz, int tx, int tz, java.util.function.Consumer<Path> check) {
        // The owner is made and pinned now: pin schedules a task for every tick, which a delayed task mustn't do.
        ServerPlayer[] owner = {owner(helper, tx + 0.5, GROUND + 1, tz + 0.5)};
        pin(helper, owner[0], tx + 0.5, GROUND + 1, tz + 0.5);
        AbstractRecruitEntity[] follower = new AbstractRecruitEntity[1];
        helper.runAfterDelay(60, () -> follower[0] = followingRecruit(helper, owner[0], fx, GROUND + 1, fz));
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
