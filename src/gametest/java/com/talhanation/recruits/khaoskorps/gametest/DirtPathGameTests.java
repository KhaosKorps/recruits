package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.ai.navigation.RecruitsPathNodeEvaluator;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * DirtPathPreference. A path point on plain ground with no dirt path beside it costs 2 per side
 * without one, 8 in all. ORIGINAL adds that again on every look at the point, as 1.15.2 does; ONCE
 * adds it only when the point is created; OFF doesn't add it.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DirtPathGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final String ONCE = "khaoskorps_dirt_path_once";
    private static final String OFF = "khaoskorps_dirt_path_off";
    private static final String OFF_SMALL_BUDGET = "khaoskorps_dirt_path_off_small_budget";

    @BeforeBatch(batch = ONCE)
    public static void once(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.DirtPathPreference.set(FollowSettings.DirtPathPreference.ONCE));
    }

    @AfterBatch(batch = ONCE)
    public static void afterOnce(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = OFF)
    public static void off(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.DirtPathPreference.set(FollowSettings.DirtPathPreference.OFF));
    }

    @AfterBatch(batch = OFF)
    public static void afterOff(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = OFF_SMALL_BUDGET)
    public static void offSmallBudget(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.DirtPathPreference.set(FollowSettings.DirtPathPreference.OFF);
            KhaosKorpsServerConfig.SearchNodesPerBlock.set(1);
        });
    }

    @AfterBatch(batch = OFF_SMALL_BUDGET)
    public static void afterOffSmallBudget(ServerLevel level) {
        resetForkSettings();
    }

    /** ORIGINAL, the default: the point costs 8 the first time it is looked at and 16 the second. */
    @GameTest(template = T, timeoutTicks = 20)
    public static void originalAddsTheDirtPathCostOnEveryLook(GameTestHelper helper) {
        expectCosts(helper, 8.0F, 16.0F, "ORIGINAL");
    }

    /** ONCE: 8 both times. */
    @GameTest(template = T, timeoutTicks = 20, batch = ONCE)
    public static void onceAddsTheDirtPathCostWhenThePointIsCreated(GameTestHelper helper) {
        expectCosts(helper, 8.0F, 8.0F, "ONCE");
    }

    /** OFF: no dirt-path cost. */
    @GameTest(template = T, timeoutTicks = 20, batch = OFF)
    public static void offAddsNoDirtPathCost(GameTestHelper helper) {
        expectCosts(helper, 0.0F, 0.0F, "OFF");
    }

    /**
     * OFF and a search budget of 1 point per block of follow range (32 points): the search heads
     * for the owner 13 blocks away and reaches it, where ORIGINAL runs out (SearchSettingGameTests).
     */
    @GameTest(template = T, timeoutTicks = 200, batch = OFF_SMALL_BUDGET)
    public static void withDirtPathOffASmallSearchBudgetReachesTheOwner(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 3, 2.5);
        pin(helper, owner, 14.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);

        helper.onEachTick(() -> {
            Path path = finishedPath(recruit);
            if (path == null) return;
            if (!path.canReach()) {
                throw new GameTestAssertException("with DirtPathPreference OFF and 32 search points the first path doesn't reach the owner 13 blocks away: "
                        + pathPoints(helper, path));
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    /** A recruit's own evaluator, so a test can look at one path point's cost. */
    private static final class CostProbe extends RecruitsPathNodeEvaluator {
        Node at(BlockPos pos) {
            return this.getNode(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private static void expectCosts(GameTestHelper helper, float first, float second, String mode) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setAggroState(3);
        BlockPos point = helper.absolutePos(new BlockPos(7, 3, 2));
        CostProbe probe = new CostProbe();
        probe.prepare(new PathNavigationRegion(helper.getLevel(), point.offset(-8, -4, -8), point.offset(8, 4, 8)), recruit);
        float firstLook = probe.at(point).costMalus;
        float secondLook = probe.at(point).costMalus;
        probe.done();
        recruit.discard();
        if (firstLook != first || secondLook != second) {
            throw new GameTestAssertException("with DirtPathPreference " + mode + " a plain point costs " + firstLook + " then " + secondLook
                    + "; expected " + first + " then " + second);
        }
        helper.succeed();
    }
}
