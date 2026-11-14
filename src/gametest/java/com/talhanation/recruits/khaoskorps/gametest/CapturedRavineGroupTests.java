package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Groups crossing the captured ravine (khaoskorps_ravine_1) between two points the user picked in
 * the test world, A (-166, 62, -384) and B (-159, 62, -359), which stand at (41, 65, 15) and
 * (48, 66, 40) in the arena. The owner stands at one; the group spawns at random safe spots within
 * {@link #SPAWN_RADIUS} blocks of the other, and follows. The walkable way between them runs east
 * round the pit and the pond: about 110 steps for 26 blocks in a straight line.
 *
 * Opt-in, as each runs alone for up to {@link #RUN_TICKS} ticks: generated only when the system
 * property khaoskorps.groupTests is "pilot" (A to B, groups of 1 and 6) or "all" (both ways, groups
 * of 1, 6, 12 and 24); `gradlew runGameTestServer -PgroupTests=pilot` sets it. Each test is its own
 * batch, so no two share the path worker; each sets fall distance 3, as on the servers, when it
 * starts and resets the settings when it ends.
 *
 * Every run is recorded whole ({@link GroupRecorder}). At the end each recruit is judged: reached
 * (came within 4 blocks of the owner), dead (with the cause), in the pit, at an edge (beside a drop
 * deeper than the fall distance), or stuck elsewhere. A test passes when every recruit reached the
 * owner and none died.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CapturedRavineGroupTests {

    static final String PROPERTY = "khaoskorps.groupTests";
    static final int RUN_TICKS = 6000;
    static final int SPAWN_RADIUS = 6;
    private static final int[] A = {41, 65, 15};
    private static final int[] B = {48, 66, 40};
    private static final int SIZE_X = 96, SIZE_Y = 77, SIZE_Z = 48;
    private static final double IN_THE_PIT_BELOW = 48.0;

    @GameTestGenerator
    public static List<TestFunction> groupsAcrossTheCapturedRavine() {
        String which = System.getProperty(PROPERTY, "");
        List<TestFunction> tests = new ArrayList<>();
        if (which.isEmpty()) return tests;
        boolean all = which.equals("all");
        if (!all && !which.equals("pilot")) {
            throw new IllegalArgumentException(PROPERTY + " is \"" + which + "\"; it takes \"pilot\" or \"all\"");
        }
        int[] sizes = all ? new int[]{1, 6, 12, 24} : new int[]{1, 6};
        for (boolean aToB : all ? new boolean[]{true, false} : new boolean[]{true}) {
            for (int size : sizes) {
                String name = "aGroupFollowsAcrossTheCapturedRavine_" + (aToB ? "AtoB" : "BtoA") + "_" + size;
                long seed = (aToB ? 100 : 200) + size;
                tests.add(new TestFunction("khaoskorps_group_" + name.toLowerCase(java.util.Locale.ROOT), name,
                        Main.MOD_ID + ":" + CapturedRavineGameTests.RAVINE_TEMPLATE, RUN_TICKS + 40, 0L, false,
                        helper -> groupRun(helper, name, aToB ? A : B, aToB ? B : A, size, seed)));
            }
        }
        Main.LOGGER.info("[KhaosKorps measure] group tests on the captured ravine: {} generated ({}={})", tests.size(), PROPERTY, which);
        return tests;
    }

    private static void groupRun(GameTestHelper helper, String name, int[] from, int[] to, int size, long seed) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
        try {
            CapturedRavineGameTests.fenceIn(helper);
            ServerPlayer owner = owner(helper, to[0] + 0.5, to[1], to[2] + 0.5);
            pin(helper, owner, to[0] + 0.5, to[1], to[2] + 0.5);
            DetourGameTests.standing(helper, owner);
            List<int[]> spawns = spawnPoints(helper, from, size, seed);
            StringBuilder setup = new StringBuilder("{\"from\":" + RunRecorder.xyz(from[0], from[1], from[2]) + ",\"to\":"
                    + RunRecorder.xyz(to[0], to[1], to[2]) + ",\"size\":" + size + ",\"seed\":" + seed + ",\"spawnRadius\":" + SPAWN_RADIUS
                    + ",\"runTicks\":" + RUN_TICKS + "}");
            GroupRecorder recorder = new GroupRecorder(name, helper, owner, SIZE_X, SIZE_Y, SIZE_Z, setup.toString());
            for (int[] spawn : spawns) recorder.add(followingRecruit(helper, owner, spawn[0], spawn[1], spawn[2]));
            helper.runAfterDelay(1, () -> assertFollowPreconditions(recorder.members().get(0).recruit));
            boolean[] ended = {false};
            helper.onEachTick(() -> {
                if (ended[0]) return;
                try {
                    recorder.tick();
                    boolean allDone = true;
                    for (GroupRecorder.Member m : recorder.members()) allDone &= m.reachedTick >= 0 || m.deathTick >= 0;
                    if (allDone || helper.getTick() >= RUN_TICKS) {
                        ended[0] = true;
                        judge(helper, recorder, owner, name, allDone);
                    }
                } catch (RuntimeException failure) {
                    ended[0] = true;
                    resetForkSettings();
                    recorder.finish("failed", String.valueOf(failure));
                    throw failure;
                }
            });
        } catch (RuntimeException setupFailure) {
            resetForkSettings();
            throw setupFailure;
        }
    }

    /** Judges every recruit, writes the recording, resets the settings, and passes or fails the test. */
    private static void judge(GameTestHelper helper, GroupRecorder recorder, ServerPlayer owner, String name, boolean allDone) {
        int reached = 0, dead = 0, pit = 0, edge = 0, stuck = 0;
        long reachTicks = 0;
        List<String> causes = new ArrayList<>();
        for (GroupRecorder.Member m : recorder.members()) {
            AbstractRecruitEntity r = m.recruit;
            if (m.deathTick >= 0) {
                m.outcome = "dead";
                dead++;
                causes.add(m.deathCause + "@" + m.deathTick);
            } else if (m.reachedTick >= 0) {
                m.outcome = "reached";
                reached++;
                reachTicks += m.reachedTick;
            } else if (relative(helper, r).y < IN_THE_PIT_BELOW) {
                m.outcome = "pit";
                pit++;
            } else if (toDrop(helper, r) <= 1) {
                m.outcome = "edge";
                edge++;
            } else {
                m.outcome = "stuck";
                stuck++;
            }
        }
        int size = recorder.members().size();
        String counts = String.format(java.util.Locale.ROOT, "%d recruits: reached %d%s, dead %d %s, in the pit %d, at an edge %d, stuck %d; ended at tick %d%s",
                size, reached, reached > 0 ? String.format(java.util.Locale.ROOT, " (mean tick %.0f)", reachTicks / (double) reached) : "",
                dead, causes, pit, edge, stuck, helper.getTick(), allDone ? " (everyone done)" : " (time up)");
        Main.LOGGER.info("[KhaosKorps measure] {}: {}", name, counts);
        boolean passed = reached == size && dead == 0;
        recorder.finish(passed ? "passed" : "failed", counts);
        resetForkSettings();
        removeOwner(helper, owner);
        for (GroupRecorder.Member m : recorder.members()) m.recruit.discard();
        if (!passed) throw new GameTestAssertException(name + ": " + counts);
        helper.succeed();
    }

    /** How far {@code recruit} stands from the nearest drop deeper than its fall distance: 1 to 3, or 4 for none that near. */
    private static int toDrop(GameTestHelper helper, AbstractRecruitEntity recruit) {
        BlockPos at = recruit.blockPosition();
        int d = EdgeProbe.distanceToDangerousDrop(helper.getLevel(), at.getX(), at.getY(), at.getZ(), recruit.getMaxFallDistance(), 3, null);
        return d == EdgeProbe.NONE ? 4 : d;
    }

    /**
     * {@code count} distinct spawn spots within {@link #SPAWN_RADIUS} of {@code center}, picked with
     * {@code seed}: room to stand, no fluid, within 2 blocks of the center's height, and no drop
     * deeper than 3 within 2 blocks. Fails the test's setup when too few exist.
     */
    private static List<int[]> spawnPoints(GameTestHelper helper, int[] center, int count, long seed) {
        Random random = new Random(seed);
        List<int[]> spots = new ArrayList<>();
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 4000 && spots.size() < count; attempt++) {
            int x = center[0] + random.nextInt(2 * SPAWN_RADIUS + 1) - SPAWN_RADIUS;
            int z = center[2] + random.nextInt(2 * SPAWN_RADIUS + 1) - SPAWN_RADIUS;
            if ((x - center[0]) * (x - center[0]) + (z - center[2]) * (z - center[2]) > SPAWN_RADIUS * SPAWN_RADIUS) continue;
            if (!used.add(((long) x << 32) ^ z)) continue;
            for (int y = center[1] + 2; y >= center[1] - 2; y--) {
                if (!standable(helper, x, y, z)) continue;
                BlockPos abs = helper.absolutePos(new BlockPos(x, y, z));
                if (EdgeProbe.distanceToDangerousDrop(helper.getLevel(), abs.getX(), abs.getY(), abs.getZ(), 3, 2, null) != EdgeProbe.NONE) break;
                spots.add(new int[]{x, y, z});
                break;
            }
        }
        if (spots.size() < count) {
            throw new GameTestAssertException("setup: only " + spots.size() + " safe spawn spots of " + count + " near " + java.util.Arrays.toString(center));
        }
        return spots;
    }

    private static boolean standable(GameTestHelper helper, int x, int y, int z) {
        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState ground = helper.getBlockState(below);
        if (ground.getCollisionShape(helper.getLevel(), helper.absolutePos(below)).isEmpty() || !ground.getFluidState().isEmpty()) return false;
        for (int up = 0; up <= 1; up++) {
            BlockPos room = new BlockPos(x, y + up, z);
            BlockState state = helper.getBlockState(room);
            if (!state.getCollisionShape(helper.getLevel(), helper.absolutePos(room)).isEmpty() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}
