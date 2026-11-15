package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
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
    private static final String SLOPE = "khaoskorps_slope_short_paths";

    @BeforeBatch(batch = SLOPE)
    public static void shortPathsOnASlope(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.FollowMaxPathLength.set(6));
    }

    @AfterBatch(batch = SLOPE)
    public static void afterShortPathsOnASlope(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A staircase down a block a step, with follow paths capped at 6 blocks, so every path down it
     * falls short of the owner on the flat at the bottom. A slope isn't a drop, so no path is cut
     * back: the recruit walks down to its owner.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 500, batch = SLOPE)
    public static void aRecruitFollowsDownAStaircaseWhosePathsFallShort(GameTestHelper helper) {
        stairsDown(helper);
        daytime(helper);
        ServerPlayer owner = owner(helper, 13.5, 1, 2.5);
        pin(helper, owner, 13.5, 1, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 9.0) {
                throw new GameTestAssertException("the recruit hasn't come down the staircase to the owner: at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    static void cliff(GameTestHelper helper) {
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
     * A wide arena with safe ground behind a rim: barrier walls around x = 0..15, z = 0..8, ground at
     * stand height 5 for x = 1..10, and a five-deep drop for x = 11..14 across the arena's width.
     * The owner stands at the rim, at (10.5, 5, 6.5); the recruit starts inland at (2, 5, 2).
     */
    private static void rimWithGroundBehind(GameTestHelper helper) {
        for (int y = 0; y <= WALL_TOP; y++) {
            for (int x = 0; x <= 15; x++) {
                helper.setBlock(x, y, 0, Blocks.BARRIER);
                helper.setBlock(x, y, 8, Blocks.BARRIER);
            }
            for (int z = 1; z <= 7; z++) {
                helper.setBlock(0, y, z, Blocks.BARRIER);
                helper.setBlock(15, y, z, Blocks.BARRIER);
            }
        }
        for (int x = 1; x <= 10; x++) {
            for (int z = 1; z <= 7; z++) {
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
    }

    /**
     * Follows the owner at the rim, and hands the first follow path to {@code check} once the recruit
     * is walking it, when the navigation has applied its edge care: its end point, relative to the
     * template, and that point's distance to the nearest drop deeper than the fall distance.
     */
    private static void followToTheRim(GameTestHelper helper, java.util.function.BiConsumer<BlockPos, Integer> check) {
        rimWithGroundBehind(helper);
        ServerPlayer owner = owner(helper, 10.5, 5, 6.5);
        pin(helper, owner, 10.5, 5, 6.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 5, 2);

        helper.onEachTick(() -> {
            net.minecraft.world.level.pathfinder.Path path = finishedPath(recruit);
            if (path == null || path.getNextNodeIndex() < 1 || path.getNodeCount() == 0) return;
            BlockPos end = path.getEndNode().asBlockPos();
            int toDrop = com.talhanation.recruits.khaoskorps.EdgeProbe.distanceToDangerousDrop(helper.getLevel(),
                    end.getX(), end.getY(), end.getZ(), recruit.getMaxFallDistance(), 4, null);
            check.accept(end.subtract(helper.absolutePos(BlockPos.ZERO)), toDrop);
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /**
     * With the defaults, a path to an owner standing at a rim it can reach ends on safe ground: no
     * drop within 3 blocks of its end, where 1.15.2's ends beside the drop at the owner's feet.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void aPathToAnOwnerAtARimEndsBackFromTheEdge(GameTestHelper helper) {
        followToTheRim(helper, (end, toDrop) -> {
            if (toDrop != com.talhanation.recruits.khaoskorps.EdgeProbe.NONE && toDrop <= 3) {
                throw new GameTestAssertException("the path to an owner at the rim ends at " + end.toShortString()
                        + ", " + toDrop + " blocks from the drop; it should end more than 3 blocks back");
            }
            double fromOwner = Math.hypot(end.getX() - 10, end.getZ() - 6);
            if (fromOwner > 6.0) {
                throw new GameTestAssertException("the path to an owner at the rim ends at " + end.toShortString()
                        + ", " + String.format("%.1f", fromOwner) + " blocks from the owner; it should stop within 6");
            }
        });
    }

    /** With edge care off, as in 1.15.2, the path ends at the owner, within 2 blocks of the drop (a search stops a block short). */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 200, batch = EDGE_CARE_OFF_BATCH)
    public static void withEdgeCareOffAPathToAnOwnerAtARimEndsAtTheEdge(GameTestHelper helper) {
        followToTheRim(helper, (end, toDrop) -> {
            if (toDrop == com.talhanation.recruits.khaoskorps.EdgeProbe.NONE || toDrop > 2) {
                throw new GameTestAssertException("with edge care off, the path to an owner at the rim ends at "
                        + end.toShortString() + ", " + toDrop + " blocks from the drop, not within 2");
            }
        });
    }

    /**
     * Near the owner, a path ends at its point furthest from a drop, counting where the mob stands:
     * a path along the rim of the wide arena whose points lie 3, 3, 2, 2, 1 and 1 blocks from the
     * drop keeps its first two, so a mob already inside the margin goes no nearer the edge.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void nearTheOwnerAPathEndsAtItsPointFurthestFromTheDrop(GameTestHelper helper) {
        rimWithGroundBehind(helper);
        int[][] points = {{8, 6}, {8, 5}, {9, 4}, {9, 3}, {10, 2}, {10, 1}};
        java.util.List<net.minecraft.world.level.pathfinder.Node> nodes = new java.util.ArrayList<>();
        for (int[] point : points) {
            BlockPos at = helper.absolutePos(new BlockPos(point[0], 5, point[1]));
            nodes.add(new net.minecraft.world.level.pathfinder.Node(at.getX(), at.getY(), at.getZ()));
        }
        net.minecraft.world.level.pathfinder.Path path = new net.minecraft.world.level.pathfinder.Path(
                nodes, helper.absolutePos(new BlockPos(10, 5, 1)), true);
        int keep = com.talhanation.recruits.khaoskorps.PathEdgeTrim.pointsToKeepNearTarget(helper.getLevel(), path, 1, 2, 6);
        if (keep != 2) {
            throw new GameTestAssertException("the path along the rim keeps " + keep + " of its 6 points; it should keep the 2 that lie 3 blocks from the drop");
        }
        helper.succeed();
    }

    private static final String PERCH = "khaoskorps_perch_fall_3";
    private static final String PERCH_EDGE_CARE_OFF = "khaoskorps_perch_fall_3_edge_care_off";

    @BeforeBatch(batch = PERCH)
    public static void perchFallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = PERCH)
    public static void afterPerchFallThree(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = PERCH_EDGE_CARE_OFF)
    public static void perchFallThreeEdgeCareOff(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MaxFallDistance.set(3);
            KhaosKorpsServerConfig.EdgeMargin.set(false);
            KhaosKorpsServerConfig.WaitWhenOwnerUnreachable.set(false);
        });
    }

    @AfterBatch(batch = PERCH_EDGE_CARE_OFF)
    public static void afterPerchFallThreeEdgeCareOff(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A cliff top at stand height 7 (x = 1..5) with a lone block below its edge: a single column at
     * (6, z = 2) to stand height 4, three blocks down, with drops of four or more to the floor all
     * round it. Everything else beyond the cliff is open to the floor.
     */
    private static void cliffWithALoneBlockBelow(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 5, 7);
        for (int y = 0; y < 4; y++) helper.setBlock(6, y, 2, Blocks.STONE);
        daytime(helper);
    }

    /** At fall distance 3 with edge care on, no path drops from the cliff top onto the lone block. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60, batch = PERCH)
    public static void noPathDropsOntoALoneBlockBelowACliff(GameTestHelper helper) {
        searchFromTheCliffToTheLoneBlock(helper, path -> {
            if (path.canReach()) {
                throw new GameTestAssertException("a path drops onto the lone block below the cliff: " + pathPoints(helper, path));
            }
        });
    }

    /** With edge care off, as in 1.15.2, at fall distance 3 the path drops onto the lone block. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60, batch = PERCH_EDGE_CARE_OFF)
    public static void withEdgeCareOffAPathDropsOntoALoneBlockBelowACliff(GameTestHelper helper) {
        searchFromTheCliffToTheLoneBlock(helper, path -> {
            if (!path.canReach()) {
                throw new GameTestAssertException("with edge care off the path doesn't reach the lone block: " + pathPoints(helper, path));
            }
        });
    }

    /**
     * The perch rule in the follow navigation itself, apart from the near-owner rule: no owner, and
     * the navigation is never ticked, so nothing trims the path. With the follow goal's edge care on
     * the navigation, its search finds no way down onto the lone block.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60, batch = PERCH)
    public static void theFollowNavigationWithEdgeCareFindsNoWayOntoTheLoneBlock(GameTestHelper helper) {
        navigationSearchToTheLoneBlock(helper, true, path -> {
            if (path.canReach()) throw new GameTestAssertException("with edge care the follow navigation drops onto the lone block: " + pathPoints(helper, path));
        });
    }

    /** Without edge care on the navigation, as for hold and move orders, the same search drops onto the lone block. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60, batch = PERCH)
    public static void theNavigationWithoutEdgeCareDropsOntoTheLoneBlock(GameTestHelper helper) {
        navigationSearchToTheLoneBlock(helper, false, path -> {
            if (!path.canReach()) throw new GameTestAssertException("without edge care the navigation doesn't reach the lone block: " + pathPoints(helper, path));
        });
    }

    private static void navigationSearchToTheLoneBlock(GameTestHelper helper, boolean edgeCare,
                                                       java.util.function.Consumer<net.minecraft.world.level.pathfinder.Path> check) {
        cliffWithALoneBlockBelow(helper);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 2, 7, 2);
        recruit.setAggroState(3);
        recruit.setNoAi(true);
        net.minecraft.world.level.pathfinder.Path[] path = {null};
        helper.runAfterDelay(1, () -> {
            recruit.setOnGround(true);
            com.talhanation.recruits.pathfinding.AsyncPathNavigation navigation =
                    (com.talhanation.recruits.pathfinding.AsyncPathNavigation) recruit.getNavigation();
            if (edgeCare) navigation.setEdgeCare(com.talhanation.recruits.khaoskorps.EdgeCare.forFollowing(
                    com.talhanation.recruits.khaoskorps.FollowSettings.current()));
            path[0] = navigation.createPath(helper.absolutePos(new BlockPos(6, 4, 2)), 0);
            if (path[0] == null) throw new GameTestAssertException("the search couldn't start on the cliff top");
        });
        helper.onEachTick(() -> {
            if (path[0] == null || (path[0] instanceof com.talhanation.recruits.pathfinding.AsyncPath async && !async.isProcessed())) return;
            check.accept(path[0]);
            recruit.discard();
            helper.succeed();
        });
    }

    private static void searchFromTheCliffToTheLoneBlock(GameTestHelper helper,
                                                         java.util.function.Consumer<net.minecraft.world.level.pathfinder.Path> check) {
        cliffWithALoneBlockBelow(helper);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 2, 7, 2);
        recruit.setAggroState(3);
        net.minecraft.world.level.pathfinder.Path[] path = {null};
        helper.runAfterDelay(1, () -> {
            path[0] = com.talhanation.recruits.khaoskorps.DetourSearch.find(helper.getLevel(), recruit,
                    helper.absolutePos(new BlockPos(5, 7, 2)), helper.absolutePos(new BlockPos(6, 4, 2)), 8, 0);
            if (path[0] == null) throw new GameTestAssertException("the search couldn't start on the cliff top");
        });
        helper.onEachTick(() -> {
            if (path[0] == null || (path[0] instanceof com.talhanation.recruits.pathfinding.AsyncPath async && !async.isProcessed())) return;
            check.accept(path[0]);
            recruit.discard();
            helper.succeed();
        });
    }

    /**
     * The user's case: an owner standing on the lone block, and a recruit following closely, already
     * inside the margin on the cliff top. At fall distance 3 with the defaults, it stays on top.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 220, batch = PERCH)
    public static void aRecruitDoesNotFollowItsOwnerDownOntoALoneBlock(GameTestHelper helper) {
        cliffWithALoneBlockBelow(helper);
        ServerPlayer owner = owner(helper, 6.5, 4, 2.5);
        pin(helper, owner, 6.5, 4, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 4, 7, 2);

        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 6.99) {
                throw new GameTestAssertException("the recruit left the cliff top for the lone block or the drop: at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit));
            }
        });
        helper.runAtTickTime(200, () -> {
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /**
     * The cliff trail: flat ground at stand height 5, with a five-deep ditch in lanes z = 2 and 3
     * for x = 3..10, so lane z = 1 along it is a one-block trail beside a drop. The ditch ends
     * four blocks short of the owner's spot at x = 14, so the owner doesn't stand at an edge.
     */
    private static void cliffTrail(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 5);
        for (int x = 3; x <= 10; x++) {
            for (int z = 2; z <= CORRIDOR_Z_MAX; z++) {
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, Blocks.AIR);
            }
        }
        daytime(helper);
    }

    /** With the defaults, a recruit following along the cliff trail walks at EdgeCarefulSpeed, half its follow speed. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 400)
    public static void aRecruitWalksCarefullyAlongACliffTrail(GameTestHelper helper) {
        speedAlongTheTrail(helper, 1.05 * 0.5, "half its follow speed (EdgeCarefulSpeed 0.5)");
    }

    /** With edge care off, as in 1.15.2, it walks the trail at its full follow speed. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 400, batch = EDGE_CARE_OFF_BATCH)
    public static void withEdgeCareOffARecruitWalksACliffTrailAtFullSpeed(GameTestHelper helper) {
        speedAlongTheTrail(helper, 1.05, "its full follow speed");
    }

    /** Records the speed the recruit is driven at while in the middle of the trail (x = 4.5..9.5). */
    private static void speedAlongTheTrail(GameTestHelper helper, double expected, String what) {
        cliffTrail(helper);
        ServerPlayer owner = owner(helper, 14.5, 5, 1.5);
        pin(helper, owner, 14.5, 5, 1.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 5, 1);
        int[] samples = {0};

        helper.onEachTick(() -> {
            Vec3 at = relative(helper, recruit);
            if (at.y < 4.99) {
                throw new GameTestAssertException("the recruit fell off the trail: at " + describe(helper, recruit));
            }
            if (at.x < 4.5 || at.x > 9.5 || !recruit.isFollowing()) return;
            double speed = recruit.getMoveControl().getSpeedModifier();
            if (Math.abs(speed - expected) > 1e-6) {
                throw new GameTestAssertException("on the trail at " + describe(helper, recruit) + " the recruit is driven at speed "
                        + String.format("%.3f", speed) + ", not " + String.format("%.3f", expected) + ", " + what
                        + "; " + navigationState(helper, recruit));
            }
            samples[0]++;
        });
        helper.succeedWhen(() -> {
            if (relative(helper, recruit).x <= 9.5 || samples[0] < 10) {
                throw new GameTestAssertException("the recruit hasn't walked the trail yet: at " + describe(helper, recruit)
                        + ", " + samples[0] + " speed samples");
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * The cliff trail with follow paths capped at 8 blocks, so every path along it falls short of
     * the owner at the far end. The drop is beside the trail, not ahead of it, so no path is cut
     * back: the recruit keeps moving and reaches the owner.
     */
    /**
     * The same, with the owner past the trail's end and off its line, at (14.5, 5, 3.5): the straight
     * line toward the owner runs ahead along the trail, so no path is cut back, though the ditch lies
     * diagonally ahead.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 500, batch = TRAIL)
    public static void aRecruitFollowsAlongACliffTrailToAnOwnerOffToOneSide(GameTestHelper helper) {
        cliffTrail(helper);
        ServerPlayer owner = owner(helper, 14.5, 5, 3.5);
        pin(helper, owner, 14.5, 5, 3.5);
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

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 500, batch = TRAIL)
    public static void aRecruitFollowsAlongACliffTrailWhosePathsFallShort(GameTestHelper helper) {
        cliffTrail(helper);
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
