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

    /**
     * The test owner is never ticked as a player, so it never counts as on the ground by itself; a
     * detour is only planned for an owner on the ground.
     */
    static void standing(GameTestHelper helper, ServerPlayer owner) {
        helper.onEachTick(() -> owner.setOnGround(true));
    }

    static final String DETOUR_OFF = "khaoskorps_detour_off";

    @net.minecraft.gametest.framework.BeforeBatch(batch = DETOUR_OFF)
    public static void detourOff(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.DetourSearch.set(false));
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = DETOUR_OFF)
    public static void afterDetourOff(net.minecraft.server.level.ServerLevel level) {
        resetForkSettings();
    }

    /** With DetourSearch off, the recruit stays on the north side, waiting back from the ravine. */
    @GameTest(template = ARENA, timeoutTicks = 700, batch = DETOUR_OFF)
    public static void withDetourSearchOffARecruitStaysOnItsSideOfTheRavine(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);

        helper.onEachTick(() -> {
            if (relative(helper, recruit).z > 19.0 || relative(helper, recruit).x > 36.0) {
                throw new GameTestAssertException("with DetourSearch off the recruit left the north side: at " + describe(helper, recruit));
            }
        });
        helper.runAtTickTime(680, () -> {
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** A following recruit finds its way round the ravine to the owner, without falling in. */
    @GameTest(template = ARENA, timeoutTicks = 1200)
    public static void aFollowingRecruitFindsItsWayAroundARavine(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        standing(helper, owner);
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
            Main.LOGGER.info("[KhaosKorps measure] a following recruit got round the test ravine to its owner at tick {}", helper.getTick());
            removeOwner(helper, owner);
        });
    }

    /**
     * The planner on its own: asked for the way from the recruit north of the ravine to the owner
     * south of it, it finds a route through the gap at the ravine's east end, with every waypoint on
     * safe ground. Logs how many ticks and searches the plan took.
     */
    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void aDetourPlanFindsTheWayRoundTheRavine(GameTestHelper helper) {
        planRoundTheRavine(helper, "ORIGINAL");
    }

    static final String DIRT_PATH_OFF = "khaoskorps_detour_dirt_path_off";

    @net.minecraft.gametest.framework.BeforeBatch(batch = DIRT_PATH_OFF)
    public static void dirtPathOff(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.DirtPathPreference.set(
                com.talhanation.recruits.khaoskorps.FollowSettings.DirtPathPreference.OFF));
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = DIRT_PATH_OFF)
    public static void afterDirtPathOff(net.minecraft.server.level.ServerLevel level) {
        resetForkSettings();
    }

    /** The same plan with DirtPathPreference OFF, logged for comparison: searches no longer spread over open ground. */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = DIRT_PATH_OFF)
    public static void withDirtPathOffADetourPlanFindsTheWayRoundTheRavine(GameTestHelper helper) {
        planRoundTheRavine(helper, "OFF");
    }

    private static void planRoundTheRavine(GameTestHelper helper, String dirtPath) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);
        boolean[] requested = {false};

        helper.onEachTick(() -> {
            if (!requested[0]) {
                if (helper.getTick() < 2) return;
                requested[0] = com.talhanation.recruits.khaoskorps.DetourRoutes.request(owner, recruit,
                        com.talhanation.recruits.khaoskorps.FollowSettings.current());
                if (!requested[0]) throw new GameTestAssertException("setup: the plan didn't start");
                return;
            }
            com.talhanation.recruits.khaoskorps.DetourPlan plan = com.talhanation.recruits.khaoskorps.DetourRoutes.plan(owner.getUUID());
            if (plan == null) throw new GameTestAssertException("the plan was dropped before it finished");
            if (plan.status() == com.talhanation.recruits.khaoskorps.DetourPlan.Status.PLANNING) return;
            String measured = plan.searches() + " searches in " + plan.ticks() + " ticks";
            if (plan.status() == com.talhanation.recruits.khaoskorps.DetourPlan.Status.FAILED) {
                throw new GameTestAssertException("no way round the ravine found, after " + measured);
            }
            java.util.List<String> waypoints = new java.util.ArrayList<>();
            boolean throughTheGap = false;
            for (net.minecraft.core.BlockPos waypoint : plan.route()) {
                net.minecraft.core.BlockPos rel = waypoint.subtract(helper.absolutePos(net.minecraft.core.BlockPos.ZERO));
                waypoints.add(rel.toShortString());
                if (rel.getX() >= 37) throughTheGap = true;
                int toDrop = com.talhanation.recruits.khaoskorps.EdgeProbe.distanceToDangerousDrop(helper.getLevel(),
                        waypoint.getX(), waypoint.getY(), waypoint.getZ(), recruit.getMaxFallDistance(), 3, null);
                if (toDrop != com.talhanation.recruits.khaoskorps.EdgeProbe.NONE) {
                    throw new GameTestAssertException("waypoint " + rel.toShortString() + " is " + toDrop
                            + " blocks from a drop; route " + waypoints);
                }
            }
            if (!throughTheGap) {
                throw new GameTestAssertException("the route doesn't go through the gap at x = 37..46: " + waypoints + " (" + measured + ")");
            }
            Main.LOGGER.info("[KhaosKorps measure] detour plan round the test ravine, dirt path {}: route {}, {}", dirtPath, waypoints, measured);
            removeOwner(helper, owner);
            helper.succeed();
        });
    }
}
