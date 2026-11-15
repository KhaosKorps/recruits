package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Following across rough ravines ({@link RavineTerrain}): the recruit starts north, its owner
 * stands south. On every tick the recruit must stay out of the ravine (not go down into it, onto a
 * shelf or its end staircase) and out of the water; it must reach its owner, by the way round east
 * of the ravine. Each seed is its own test, at fall distance 3 (as on the user's test server) and
 * at the default of 1. Optional for now: they record that the detour doesn't yet get round these
 * ravines, and become required once it does.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class RavineGameTests {

    static final String ARENA = "khaoskorps_empty_64x24x64";
    private static final String FALL_THREE = "khaoskorps_rough_ravine_fall_3";
    private static final String FALL_ONE = "khaoskorps_rough_ravine_fall_1";
    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6};
    private static final int TIMEOUT = 2000;

    @BeforeBatch(batch = FALL_THREE)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = FALL_ONE)
    public static void fallOne(ServerLevel level) {
        resetForkSettings();
    }

    @GameTestGenerator
    public static List<TestFunction> roughRavinesAtFallDistanceThree() {
        return tests(FALL_THREE, "aFollowerGoesRoundARoughRavineAtFallDistanceThree");
    }

    @GameTestGenerator
    public static List<TestFunction> roughRavinesAtFallDistanceOne() {
        return tests(FALL_ONE, "aFollowerGoesRoundARoughRavineAtFallDistanceOne");
    }

    private static List<TestFunction> tests(String batch, String name) {
        List<TestFunction> tests = new ArrayList<>();
        for (long seed : SEEDS) {
            tests.add(new TestFunction(batch, name + "_seed" + seed, Main.MOD_ID + ":" + ARENA, TIMEOUT, 0L, false,
                    helper -> followAcross(helper, new RavineTerrain(seed), name + "_seed" + seed)));
        }
        return tests;
    }

    static void followAcross(GameTestHelper helper, RavineTerrain terrain, String testName) {
        terrain.build(helper);
        daytime(helper);
        int x = 14;
        int ownerZ = 56;
        ServerPlayer owner = owner(helper, x + 0.5, terrain.stand[x][ownerZ], ownerZ + 0.5);
        pin(helper, owner, x + 0.5, terrain.stand[x][ownerZ], ownerZ + 0.5);
        DetourGameTests.standing(helper, owner);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, x, terrain.stand[x][8], 8);
        // One recruit, every follow search with its points, the first 60 distinct searches in full detail.
        GroupRecorder recorder = new GroupRecorder(testName, helper, owner, RavineTerrain.SIZE, RavineTerrain.TOP + 1, RavineTerrain.SIZE,
                "{\"seed\":" + terrain.seed + "}", terrain.seed, 0, 60);
        GroupRecorder.Member member = recorder.add(recruit);
        String[] last = {"no check has run yet"};
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.onEachTick(() -> {
            recorder.tick();
            try {
                checkSafe(helper, terrain, recruit);
            } catch (GameTestAssertException failure) {
                String why = failure.getMessage();
                member.outcome = why.contains("water") ? "water" : why.contains("died") ? "dead" : "pit";
                recorder.finish("failed", why);
                throw failure;
            }
            if (helper.getTick() >= TIMEOUT - 2) {
                member.outcome = "stuck";
                recorder.finish("timed out", last[0]);
            }
        });
        helper.succeedWhen(() -> {
            if (recruit.distanceToSqr(owner) > 3.5 * 3.5) {
                last[0] = terrain.describe() + ": at tick " + helper.getTick() + " the recruit is "
                        + String.format("%.1f", Math.sqrt(recruit.distanceToSqr(owner))) + " blocks from its owner, at "
                        + describe(helper, recruit) + "; " + navigationState(helper, recruit);
                throw new GameTestAssertException(last[0]);
            }
            member.outcome = "reached";
            recorder.finish("passed", "reached its owner at tick " + helper.getTick());
            removeOwner(helper, owner);
        });
    }

    /** The recruit must stay out of the ravine and the water, and alive. */
    private static void checkSafe(GameTestHelper helper, RavineTerrain terrain, AbstractRecruitEntity recruit) {
        {
            Vec3 at = relative(helper, recruit);
            BlockPos column = BlockPos.containing(at);
            int cx = column.getX();
            int cz = column.getZ();
            boolean inArena = cx >= 1 && cx <= RavineTerrain.SIZE - 2 && cz >= 1 && cz <= RavineTerrain.SIZE - 2;
            if (recruit.isInWater()) {
                throw new GameTestAssertException(terrain.describe() + ": the recruit is in the water at " + describe(helper, recruit));
            }
            if (inArena && terrain.ravine[cx][cz] && at.y < terrain.rimAt(cx, cz) - 0.5) {
                throw new GameTestAssertException(terrain.describe() + ": the recruit went down into the ravine at tick "
                        + helper.getTick() + ": at " + describe(helper, recruit) + " (rim at " + terrain.rimAt(cx, cz) + ")");
            }
            if (!recruit.isAlive()) throw new GameTestAssertException(terrain.describe() + ": the recruit died");
        }
    }
}
