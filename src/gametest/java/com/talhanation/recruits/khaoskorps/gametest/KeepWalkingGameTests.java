package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.ai.RecruitFollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * KeepWalkingWhileRepathing, tested on the navigation directly. A recruit with its AI off walks a
 * finished path; its navigation is then handed a new search whose work on the path worker is held
 * on a latch, so it stays unfinished for as long as the test needs. With the setting on, the
 * navigation keeps the old path until the new one is finished, then takes the new one on its next
 * tick; by default, as in 1.15.2, it swaps to the unfinished one at once and has nothing to walk.
 *
 * Holding the latch blocks the path worker, so each test runs alone in its own batch, and the latch
 * is always released.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class KeepWalkingGameTests {

    private static final String KEEP_WALKING = "khaoskorps_keep_walking";
    private static final String DEFAULT_ALONE = "khaoskorps_keep_walking_default";

    @BeforeBatch(batch = KEEP_WALKING)
    public static void keepWalking(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.KeepWalkingWhileRepathing.set(true));
    }

    @AfterBatch(batch = KEEP_WALKING)
    public static void afterKeepWalking(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = DEFAULT_ALONE)
    public static void defaults(ServerLevel level) {
        resetForkSettings();
    }

    private static final String REPEAT_ALONE = "khaoskorps_keep_walking_repeat";
    private static final String GOAL_ALONE = "khaoskorps_keep_walking_goal";

    @BeforeBatch(batch = REPEAT_ALONE)
    public static void keepWalkingRepeat(ServerLevel level) {
        keepWalking(level);
    }

    @AfterBatch(batch = REPEAT_ALONE)
    public static void afterKeepWalkingRepeat(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = GOAL_ALONE)
    public static void keepWalkingGoal(ServerLevel level) {
        keepWalking(level);
    }

    @AfterBatch(batch = GOAL_ALONE)
    public static void afterKeepWalkingGoal(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A finished search waiting to take over isn't lost when the same target is asked for again
     * before the navigation's next tick: the request gets the new path. The path workers are held
     * by latched jobs queued first, so the search stays unfinished until the test lets it run.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100, batch = REPEAT_ALONE)
    public static void withKeepWalkingAFinishedSearchIsNotLostToARepeatedRequest(GameTestHelper helper) {
        if (!RecruitsServerConfig.UseAsyncPathfinding.get()) {
            helper.succeed();
            return;
        }
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setNoAi(true);
        recruit.setOnGround(true);
        AsyncPathNavigation navigation = (AsyncPathNavigation) recruit.getNavigation();
        BlockPos target = helper.absolutePos(new BlockPos(13, 3, 2));
        Path walked = corridorPath(helper, 2, 9);
        AsyncPath[] search = {null};
        CountDownLatch release = holdTheWorkers(helper);
        try {
            navigation.moveTo(walked, 1.0D);
            Path requested = navigation.createPath(target, 1);
            if (!(requested instanceof AsyncPath async) || async.isProcessed()) {
                throw new GameTestAssertException("setup: expected a search still waiting, got " + requested);
            }
            search[0] = async;
            navigation.moveTo(requested, 1.0D);
            if (navigation.getPath() != walked) throw new GameTestAssertException("setup: the walked path wasn't kept");
        } finally {
            release.countDown();
        }
        helper.onEachTick(() -> {
            if (!search[0].isProcessed()) return;
            recruit.setOnGround(true);
            navigation.moveTo(navigation.createPath(target, 1), 1.0D);
            Path now = navigation.getPath();
            recruit.discard();
            if (now != search[0]) {
                throw new GameTestAssertException("after its search finished, asking again for the same target left the navigation on "
                        + (now == walked ? "the old path" : String.valueOf(now)));
            }
            helper.succeed();
        });
    }

    /**
     * The follow goal keeps going while a new search waits, even once the old path has run out; it
     * used to stop there, dropping the search, and wait to start again.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = GOAL_ALONE)
    public static void withKeepWalkingTheFollowGoalWaitsForASearchWhenItsPathRunsOut(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        net.minecraft.server.level.ServerPlayer owner = owner(helper, 13.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        recruit.setNoAi(true);
        AsyncPathNavigation navigation = (AsyncPathNavigation) recruit.getNavigation();
        RecruitFollowOwnerGoal goal = followGoal(recruit);
        CountDownLatch release = new CountDownLatch(1);
        boolean continues;
        try {
            Path walked = corridorPath(helper, 2, 9);
            navigation.moveTo(walked, 1.0D);
            AsyncPath held = new AsyncPath(new ArrayList<>(), Set.of(helper.absolutePos(new BlockPos(13, 3, 2))), helper.getLevel(), () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return corridorPath(helper, 2, 13);
            });
            navigation.moveTo(held, 1.0D);
            walked.setNextNodeIndex(walked.getNodeCount());
            if (!goal.canUse()) throw new GameTestAssertException("setup: the follow goal won't start");
            continues = goal.canContinueToUse();
        } finally {
            release.countDown();
        }
        removeOwner(helper, owner);
        recruit.discard();
        if (!continues) throw new GameTestAssertException("with a search still running, the follow goal stopped because the walked path ran out");
        helper.succeed();
    }

    /** The ravine follow with keep-walking on: the detour's checks read the newest path, not the one being walked. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 1200, batch = KEEP_WALKING)
    public static void withKeepWalkingAFollowingRecruitFindsItsWayAroundARavine(GameTestHelper helper) {
        DetourGameTests.followRoundTheRavine(helper);
    }

    /** Queues one latched job per path worker, so a search queued after them waits until the latch opens. */
    private static CountDownLatch holdTheWorkers(GameTestHelper helper) {
        CountDownLatch release = new CountDownLatch(1);
        int workers = Math.max(1, RecruitsServerConfig.AsyncPathfindingThreadsCount.get());
        for (int i = 0; i < workers; i++) {
            new AsyncPath(new ArrayList<>(), Set.of(helper.absolutePos(new BlockPos(1, 3, 2))), helper.getLevel(), () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }
        return release;
    }

    private static RecruitFollowOwnerGoal followGoal(AbstractRecruitEntity recruit) {
        for (WrappedGoal wrapped : recruit.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RecruitFollowOwnerGoal goal) return goal;
        }
        throw new GameTestAssertException("setup: the recruit has no RecruitFollowOwnerGoal");
    }

    /** With the setting on: the old path is kept while the new one is searched, and the new one takes over once finished. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100, batch = KEEP_WALKING)
    public static void withKeepWalkingTheOldPathIsWalkedUntilTheNewOneIsReady(GameTestHelper helper) {
        swapScene(helper, true);
    }

    /** By default: the navigation swaps to the unfinished path at once, as in 1.15.2. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100, batch = DEFAULT_ALONE)
    public static void byDefaultTheNavigationSwapsToTheUnfinishedPathAtOnce(GameTestHelper helper) {
        swapScene(helper, false);
    }

    /**
     * Following with the setting on: the owner moves back and forth along the corridor, so the
     * follow path keeps changing, and stops at the far end at tick 120; the recruit catches up.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 400, batch = KEEP_WALKING)
    public static void withKeepWalkingARecruitFollowsAMovingOwner(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        net.minecraft.server.level.ServerPlayer owner = owner(helper, 8.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);
        helper.onEachTick(() -> {
            long t = Math.min(helper.getTick(), 120);
            double phase = (t % 60) / 60.0;
            double x = t >= 120 ? 13.5 : 6.5 + 7.0 * (phase < 0.5 ? phase * 2 : (1 - phase) * 2);
            net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, 3, 2.5));
            owner.setPos(at.x, at.y, at.z);
        });
        helper.succeedWhen(() -> {
            if (helper.getTick() < 120 || recruit.distanceToSqr(owner) > 9.0) {
                throw new GameTestAssertException("the recruit hasn't caught up with the owner: at " + describe(helper, recruit)
                        + "; " + navigationState(helper, recruit));
            }
            removeOwner(helper, owner);
        });
    }

    private static void swapScene(GameTestHelper helper, boolean keepWalking) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setNoAi(true);
        if (!(recruit.getNavigation() instanceof AsyncPathNavigation navigation)) {
            throw new GameTestAssertException("setup: the recruit's navigation isn't an AsyncPathNavigation");
        }
        CountDownLatch release = new CountDownLatch(1);
        Path walked = corridorPath(helper, 2, 9);
        Path next = corridorPath(helper, 2, 13);
        AsyncPath[] held = {null};
        try {
            navigation.moveTo(walked, 1.0D);
            held[0] = new AsyncPath(new ArrayList<>(), Set.of(helper.absolutePos(new BlockPos(13, 3, 2))), helper.getLevel(), () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return next;
            });
            navigation.moveTo(held[0], 1.0D);
            Path kept = navigation.getPath();
            if (keepWalking && kept != walked) {
                throw new GameTestAssertException("with KeepWalkingWhileRepathing the navigation dropped the path it was walking for the unfinished one");
            }
            if (!keepWalking && kept != held[0]) {
                throw new GameTestAssertException("by default the navigation should swap to the unfinished path at once, as in 1.15.2; it kept " + kept);
            }
        } finally {
            release.countDown();
        }
        helper.onEachTick(() -> {
            if (!held[0].isProcessed()) return;
            navigation.tick();
            if (navigation.getPath() != held[0]) {
                throw new GameTestAssertException("once the new path was finished the navigation still walks " + navigation.getPath());
            }
            recruit.discard();
            helper.succeed();
        });
    }

    /** A finished path along the corridor's middle lane, x = from..to at stand height 3. */
    private static Path corridorPath(GameTestHelper helper, int from, int to) {
        List<Node> nodes = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            BlockPos at = helper.absolutePos(new BlockPos(x, 3, 2));
            nodes.add(new Node(at.getX(), at.getY(), at.getZ()));
        }
        return new Path(nodes, helper.absolutePos(new BlockPos(to, 3, 2)), true);
    }
}
