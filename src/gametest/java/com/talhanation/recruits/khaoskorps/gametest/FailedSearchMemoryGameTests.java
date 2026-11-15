package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.DetourPlan;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * A failed detour plan is remembered while nothing changes, instead of being planned again every 10 seconds, and is
 * forgotten as soon as a player places a block on the ground it searched. The scene: the walled corridor, ground at
 * stand height 3, cut across by a trench 3 deep at x = 7..9 that the recruit can neither cross nor climb out of; the
 * recruit west at x = 3, its owner east at x = 12.5. Default settings: a failed plan is remembered 2 minutes.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class FailedSearchMemoryGameTests {

    /** Ticks 100 to 700: one detour plan for the owner, not a new one every 10 seconds. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 760)
    public static void aFailedDetourPlanIsNotPlannedAgainWhileNothingChanges(GameTestHelper helper) {
        Scene scene = trenchScene(helper);
        Set<Path> searches = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DetourPlan> plans = Collections.newSetFromMap(new IdentityHashMap<>());
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick >= 100 && tick <= 700) {
                if (scene.recruit.getNavigation() instanceof AsyncPathNavigation nav && nav.latestPath() != null) searches.add(nav.latestPath());
                DetourPlan plan = com.talhanation.recruits.khaoskorps.DetourRoutes.plan(scene.owner.getUUID());
                if (plan != null) plans.add(plan);
            }
            if (tick == 701) {
                String report = "ticks 100 to 700: " + searches.size() + " follow searches started, " + plans.size() + " detour plans; recruit at "
                        + describe(helper, scene.recruit);
                Main.LOGGER.info("[KhaosKorps measure] failed search memory: {}", report);
                scene.end(helper);
                if (plans.size() > 1) throw new GameTestAssertException("the failed plan was planned again: " + report);
                helper.succeed();
            }
        });
    }

    /**
     * Once the plan has failed, a player lays planks over the trench: the failed plan is forgotten at once (by the
     * next tick), and the recruit reaches its owner by tick 500.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 560)
    public static void aFailedPlanIsForgottenWhenAPlayerBuildsOverTheGap(GameTestHelper helper) {
        Scene scene = trenchScene(helper);
        long[] builtAt = {-1};
        boolean[] forgotten = {false};
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            DetourPlan plan = com.talhanation.recruits.khaoskorps.DetourRoutes.plan(scene.owner.getUUID());
            if (builtAt[0] < 0 && plan != null && plan.status() == DetourPlan.Status.FAILED) {
                builtAt[0] = tick;
                for (int x = 7; x <= 9; x++) for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                    BlockPos pos = new BlockPos(x, 2, z);
                    helper.setBlock(pos, Blocks.OAK_PLANKS);
                    BlockPos at = helper.absolutePos(pos);
                    MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(helper.getLevel().dimension(), helper.getLevel(), at),
                            Blocks.STONE.defaultBlockState(), scene.owner));
                }
                return;
            }
            if (builtAt[0] >= 0 && tick <= builtAt[0] + 2 && (plan == null || plan.status() != DetourPlan.Status.FAILED)) forgotten[0] = true;
            boolean there = scene.recruit.distanceToSqr(scene.owner) <= 3.5 * 3.5;
            if ((there && builtAt[0] >= 0) || tick >= 500) {
                String report = "plan failed and bridge built at tick " + builtAt[0] + ", failure forgotten by the next tick " + forgotten[0] + "; "
                        + (there ? "reached its owner by tick " : "still away at tick ") + tick + "; recruit at " + describe(helper, scene.recruit);
                Main.LOGGER.info("[KhaosKorps measure] bridge over a remembered failure: {}", report);
                scene.end(helper);
                if (builtAt[0] < 0) throw new GameTestAssertException("setup: the plan never failed: " + report);
                if (!forgotten[0] || !there) throw new GameTestAssertException("the failed plan wasn't forgotten, or the bridge wasn't used: " + report);
                helper.succeed();
            }
        });
    }

    private record Scene(ServerPlayer owner, AbstractRecruitEntity recruit) {
        void end(GameTestHelper helper) {
            this.recruit.discard();
            removeOwner(helper, this.owner);
        }
    }

    private static Scene trenchScene(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 3);
        ground(helper, 10, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        DetourGameTests.standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 3, 3, 2);
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        return new Scene(owner, recruit);
    }
}
