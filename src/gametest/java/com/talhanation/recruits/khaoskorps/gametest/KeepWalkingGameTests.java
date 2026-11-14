package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
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
