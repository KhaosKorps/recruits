package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.talhanation.recruits.entities.ai.RecruitFollowOwnerGoal;
import com.talhanation.recruits.khaoskorps.DetourFollow;
import com.talhanation.recruits.khaoskorps.DetourPlan;
import com.talhanation.recruits.khaoskorps.DetourRoutes;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
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
        followRoundTheRavine(helper);
    }

    /**
     * A follower already beside the owner doesn't take the route found for another follower stuck
     * across the ravine. Once that route is found, the owner walks 8 blocks east along the south
     * bank; the follower beside it follows along the bank, and never heads north toward the route.
     */
    @GameTest(template = ARENA, timeoutTicks = 900)
    public static void aFollowerBesideTheOwnerKeepsToItsSideWhileAnotherWalksARoute(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        standing(helper, owner);
        double[] ownerX = {24.5};
        helper.onEachTick(() -> {
            Vec3 at = helper.absoluteVec(new Vec3(ownerX[0], 5, 33.5));
            owner.setPos(at.x, at.y, at.z);
        });
        AbstractRecruitEntity across = followingRecruit(helper, owner, 24, 5, 12);
        AbstractRecruitEntity beside = followingRecruit(helper, owner, 22, 5, 33);
        long[] movedAt = {-1};
        helper.onEachTick(() -> {
            if (relative(helper, beside).z < 26.0) {
                throw new GameTestAssertException("the follower beside the owner headed for the other side at tick " + helper.getTick()
                        + ": at " + describe(helper, beside) + "; " + navigationState(helper, beside));
            }
            if (movedAt[0] >= 0) return;
            DetourPlan plan = DetourRoutes.plan(owner.getUUID());
            if (plan != null && plan.status() == DetourPlan.Status.FOUND && !plan.route().isEmpty()) {
                ownerX[0] = 32.5;
                movedAt[0] = helper.getTick();
            }
        });
        helper.succeedWhen(() -> {
            if (movedAt[0] < 0) throw new GameTestAssertException("no route found yet for the follower across the ravine");
            if (helper.getTick() < movedAt[0] + 60 || beside.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("at tick " + helper.getTick() + " the follower beside the owner is at "
                        + describe(helper, beside) + "; " + navigationState(helper, beside));
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * A plan that found the owner in reach directly, with no waypoints, doesn't stop the next plan.
     * The plan starts with the owner on the recruit's side, then the owner moves across the ravine,
     * 14 blocks from where the plan was made; the recruit, stuck, gets a new plan and goes round.
     */
    @GameTest(template = ARENA, timeoutTicks = 1100)
    public static void aPlanThatFoundTheOwnerDirectlyDoesNotBlockTheNext(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 16.5);
        standing(helper, owner);
        double[] ownerZ = {16.5};
        helper.onEachTick(() -> {
            Vec3 at = helper.absoluteVec(new Vec3(24.5, 5, ownerZ[0]));
            owner.setPos(at.x, at.y, at.z);
        });
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 6);
        boolean[] requested = {false};
        long[] movedAt = {-1};
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit fell into the ravine: at " + describe(helper, recruit));
            }
            if (!requested[0]) {
                if (helper.getTick() < 2) return;
                requested[0] = DetourRoutes.request(owner, recruit, FollowSettings.current());
                if (!requested[0]) throw new GameTestAssertException("setup: the first plan didn't start");
                return;
            }
            if (movedAt[0] >= 0) return;
            DetourPlan plan = DetourRoutes.plan(owner.getUUID());
            if (plan == null) throw new GameTestAssertException("setup: the first plan was dropped");
            if (plan.status() == DetourPlan.Status.PLANNING) return;
            if (plan.status() != DetourPlan.Status.FOUND || !plan.route().isEmpty()) {
                throw new GameTestAssertException("setup: with the owner in reach the first plan should find it directly; it ended "
                        + plan.status() + " with " + plan.route().size() + " waypoints");
            }
            ownerZ[0] = 30.5;
            movedAt[0] = helper.getTick();
        });
        helper.succeedWhen(() -> {
            if (movedAt[0] < 0 || recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                DetourPlan plan = DetourRoutes.plan(owner.getUUID());
                throw new GameTestAssertException("at tick " + helper.getTick() + " the recruit is at " + describe(helper, recruit)
                        + ", plan " + (plan == null ? "none" : plan.status() + " with " + plan.route().size() + " waypoints"));
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * What a recruit noted long ago doesn't start a plan the moment it follows again. Its detour
     * state is set as if its last follow path, long ago, hadn't reached and it had stood still since;
     * the owner is now in plain reach, so no plan should start.
     */
    @GameTest(template = ARENA, timeoutTicks = 60)
    public static void oldDetourStateDoesNotStartAPlanWhenFollowingStarts(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 16.5);
        pin(helper, owner, 24.5, 5, 16.5);
        standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 6);
        DetourFollow detour = detourOf(recruit);
        setField(detour, "stillAt", recruit.position());
        setField(detour, "stillSince", 0L);
        setField(detour, "lastPathReached", Boolean.FALSE);
        helper.runAtTickTime(40, () -> {
            DetourPlan plan = DetourRoutes.plan(owner.getUUID());
            removeOwner(helper, owner);
            recruit.discard();
            if (plan != null) throw new GameTestAssertException("a plan started from old state, with the owner in reach: " + plan.status());
            helper.succeed();
        });
    }

    private static final String SLOW_START_CHECK = "khaoskorps_detour_start_check_100";

    @net.minecraft.gametest.framework.BeforeBatch(batch = SLOW_START_CHECK)
    public static void slowStartCheck(net.minecraft.server.level.ServerLevel level) {
        forkSettings(() -> com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig.FollowStartCheckTicks.set(100));
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = SLOW_START_CHECK)
    public static void afterSlowStartCheck(net.minecraft.server.level.ServerLevel level) {
        resetForkSettings();
    }

    /**
     * With the follow goal checking whether to start only every 100 ticks, a stuck recruit's goal
     * stops and starts about 100 ticks apart; it still notices it is stuck, gets a plan and goes round.
     */
    @GameTest(template = ARENA, timeoutTicks = 2400, batch = SLOW_START_CHECK)
    public static void withAStartCheckOf100TicksAFollowingRecruitStillGoesRoundTheRavine(GameTestHelper helper) {
        followRoundTheRavine(helper);
    }

    /**
     * A recruit paused for 4 seconds partway along a route (held in place, then told to follow
     * again) takes the route up where it was, not from its first waypoint.
     */
    @GameTest(template = ARENA, timeoutTicks = 1400)
    public static void aRecruitPausedPartwayAlongARouteResumesWhereItWas(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);
        DetourFollow detour = detourOf(recruit);
        int[] pausedAt = {-1};
        long[] resumeAt = {-1};
        helper.onEachTick(() -> {
            if (relative(helper, recruit).y < 4.99) {
                throw new GameTestAssertException("the recruit fell into the ravine: at " + describe(helper, recruit));
            }
            long tick = helper.getTick();
            int next = (Integer) getField(detour, "next");
            Object walking = getField(detour, "walking");
            if (pausedAt[0] < 0) {
                if (walking != null && next >= 2) {
                    pausedAt[0] = next;
                    recruit.setFollowState(2);
                    resumeAt[0] = tick + 80;
                }
                return;
            }
            if (tick == resumeAt[0]) recruit.setFollowState(1);
            if (tick > resumeAt[0] && walking != null && next < pausedAt[0]) {
                throw new GameTestAssertException("after the pause the recruit went back to waypoint " + next
                        + " of its route; it had reached waypoint " + pausedAt[0]);
            }
        });
        helper.succeedWhen(() -> {
            if (resumeAt[0] < 0 || helper.getTick() <= resumeAt[0] || recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("at tick " + helper.getTick() + " the recruit is at " + describe(helper, recruit)
                        + (pausedAt[0] < 0 ? ", not yet paused" : ", paused at waypoint " + pausedAt[0]));
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * A recruit paused partway along a route and then brought to its owner's side checks for its owner
     * first when it follows again, instead of walking back to the route's next waypoint.
     */
    @GameTest(template = ARENA, timeoutTicks = 1400)
    public static void aRecruitResumingNearItsOwnerChecksForItBeforeTheRoute(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);
        DetourFollow detour = detourOf(recruit);
        long[] resumeAt = {-1};
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (resumeAt[0] < 0) {
                if (getField(detour, "walking") != null && (Integer) getField(detour, "next") >= 2) {
                    recruit.setFollowState(2);
                    resumeAt[0] = tick + 80;
                }
                return;
            }
            if (tick == resumeAt[0] - 1) {
                net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(24.5, 5, 29.5));
                recruit.teleportTo(at.x, at.y, at.z);
            }
            if (tick == resumeAt[0]) recruit.setFollowState(1);
            if (tick > resumeAt[0] && recruit.distanceToSqr(owner) > 8.0 * 8.0) {
                throw new GameTestAssertException("brought to its owner's side, the recruit headed back to its route: at " + describe(helper, recruit)
                        + "; detour next " + getField(detour, "next") + ", checking " + getField(detour, "checkingDirect")
                        + ", reached " + getField(detour, "lastPathReached") + ", walking " + (getField(detour, "walking") != null)
                        + ", following " + recruit.isFollowing() + ", resumed " + resumeAt[0] + ", tick " + tick + "; " + navigationState(helper, recruit));
            }
        });
        helper.succeedWhen(() -> {
            if (resumeAt[0] < 0 || helper.getTick() <= resumeAt[0] + 20 || recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                throw new GameTestAssertException("at tick " + helper.getTick() + " the recruit is at " + describe(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    private static Object getField(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("setup: can't read " + name + ": " + e);
        }
    }

    private static DetourFollow detourOf(AbstractRecruitEntity recruit) {
        for (WrappedGoal wrapped : recruit.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RecruitFollowOwnerGoal goal) {
                try {
                    java.lang.reflect.Field field = RecruitFollowOwnerGoal.class.getDeclaredField("detour");
                    field.setAccessible(true);
                    return (DetourFollow) field.get(goal);
                } catch (ReflectiveOperationException e) {
                    throw new GameTestAssertException("setup: can't reach the follow goal's detour state: " + e);
                }
            }
        }
        throw new GameTestAssertException("setup: the recruit has no RecruitFollowOwnerGoal");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("setup: can't set " + name + ": " + e);
        }
    }

    /** The ravine follow scene: the recruit north, the owner south, the recruit must go round without falling in. */
    static void followRoundTheRavine(GameTestHelper helper) {
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

    /**
     * The planner reports its steps to its observer: planning round the ravine, it reports a direct
     * check, at least one spot that became a waypoint, and the direct check that found the owner.
     */
    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void aDetourPlanReportsItsStepsToItsObserver(GameTestHelper helper) {
        ravineArena(helper);
        ServerPlayer owner = owner(helper, 24.5, 5, 33.5);
        pin(helper, owner, 24.5, 5, 33.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 24, 5, 12);
        PlanSteps steps = new PlanSteps(helper);
        steps.watch(recruit);
        boolean[] requested = {false};
        helper.onEachTick(() -> {
            if (!requested[0]) {
                if (helper.getTick() < 2) return;
                requested[0] = DetourRoutes.request(owner, recruit, FollowSettings.current());
                if (!requested[0]) throw new GameTestAssertException("setup: the plan didn't start");
                return;
            }
            DetourPlan plan = DetourRoutes.plan(owner.getUUID());
            if (plan == null) throw new GameTestAssertException("the plan was dropped before it finished");
            if (plan.status() == DetourPlan.Status.PLANNING) return;
            PlanSteps.unwatch(recruit);
            java.util.Map<String, Integer> counts = steps.counts();
            if (plan.status() != DetourPlan.Status.FOUND) {
                throw new GameTestAssertException("setup: no way round the ravine found; the observer saw " + counts);
            }
            if (counts.getOrDefault("direct", 0) < 1 || counts.getOrDefault("reached", 0) < 1 || counts.getOrDefault("direct-reached", 0) < 1) {
                throw new GameTestAssertException("the observer missed steps of a found plan: it saw " + counts);
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
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
