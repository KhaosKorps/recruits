package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * A crowd following an owner who has flown across a deep ravine they can't cross, at fall
 * distance 3, as on the servers.
 *
 * The arena (48 x 12 x 48): barrier walls on its edges; ground at stand height 9 north of the
 * ravine (z = 1..19) and south of it (z = 27..46); between them, across the whole width, a ravine
 * whose floor stands at height 1, eight blocks below the rims. The owner hovers over the south rim
 * at (24.5, 10, 30.5), never on the ground, so no detour is planned. The recruits start on the north
 * ground, inland.
 *
 * Each test measures, for every recruit, how far it is from the nearest drop deeper than its fall
 * distance (1 means the next column is the drop) at the end, and the closest it came, and logs the
 * counts. Optional for now: they measure, and fail only if a recruit went over the edge or ended
 * beside it.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CrowdAtRavineGameTests {

    private static final String ARENA = DetourGameTests.ARENA;
    private static final String FALL_THREE = "khaoskorps_crowd_ravine_fall_3";
    private static final int MEASURE_AT = 400;
    /** How far from a drop is looked for; further counts as safe. */
    private static final int LOOK = 5;

    @BeforeBatch(batch = FALL_THREE)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(ServerLevel level) {
        resetForkSettings();
    }

    private static void deepRavine(GameTestHelper helper) {
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
                int stand = z >= 20 && z <= 26 ? 1 : 9;
                for (int y = 0; y < stand; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        daytime(helper);
    }

    /** How far {@code recruit} stands from the nearest dangerous drop, 1 to LOOK, or LOOK + 1 for none that near. */
    private static int toDrop(GameTestHelper helper, AbstractRecruitEntity recruit) {
        BlockPos at = recruit.blockPosition();
        int distance = EdgeProbe.distanceToDangerousDrop(helper.getLevel(), at.getX(), at.getY(), at.getZ(),
                recruit.getMaxFallDistance(), LOOK, null);
        return distance == EdgeProbe.NONE ? LOOK + 1 : distance;
    }

    private static void crowdAtTheRavine(GameTestHelper helper, int size) {
        deepRavine(helper);
        ServerPlayer owner = owner(helper, 24.5, 10, 30.5);
        pin(helper, owner, 24.5, 10, 30.5);
        List<AbstractRecruitEntity> crowd = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            crowd.add(followingRecruit(helper, owner, 12 + i % 24, 9, 4 + 2 * (i / 24)));
        }
        int[] closest = new int[size];
        java.util.Arrays.fill(closest, LOOK + 1);
        helper.runAfterDelay(1, () -> assertFollowPreconditions(crowd.get(0)));
        helper.onEachTick(() -> {
            for (int i = 0; i < size; i++) {
                AbstractRecruitEntity recruit = crowd.get(i);
                if (recruit.isAlive() && relative(helper, recruit).y > 8.5) closest[i] = Math.min(closest[i], toDrop(helper, recruit));
            }
        });
        helper.runAtTickTime(MEASURE_AT, () -> {
            int[] atEnd = new int[LOOK + 2];
            int[] atClosest = new int[LOOK + 2];
            int fell = 0;
            for (int i = 0; i < size; i++) {
                AbstractRecruitEntity recruit = crowd.get(i);
                if (!recruit.isAlive() || relative(helper, recruit).y < 8.5) {
                    fell++;
                    continue;
                }
                atEnd[toDrop(helper, recruit)]++;
                atClosest[closest[i]]++;
            }
            String counts = "fell " + fell + "; at tick " + MEASURE_AT + " by blocks from the drop " + histogram(atEnd)
                    + "; closest each came " + histogram(atClosest);
            Main.LOGGER.info("[KhaosKorps measure] {} recruits following an owner across a deep ravine: {}", size, counts);
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (fell > 0 || atEnd[1] > 0) {
                throw new GameTestAssertException(size + " recruits at a deep ravine: " + counts);
            }
            helper.succeed();
        });
    }

    private static String histogram(int[] counts) {
        StringBuilder out = new StringBuilder("{");
        for (int d = 1; d < counts.length; d++) {
            if (d > 1) out.append(", ");
            out.append(d == counts.length - 1 ? "more than " + (d - 1) : String.valueOf(d)).append(": ").append(counts[d]);
        }
        return out.append('}').toString();
    }

    /**
     * A ledge staircase down the north wall at x = 30..32, as natural ravines have: stand height 6 at
     * z = 20 and 3 at z = 21, above the floor at 1, so each step down is a drop of 3 or less.
     */
    private static void ledgeStaircase(GameTestHelper helper) {
        for (int x = 30; x <= 32; x++) {
            for (int y = 1; y < 6; y++) helper.setBlock(x, y, 20, Blocks.STONE);
            for (int y = 1; y < 3; y++) helper.setBlock(x, y, 21, Blocks.STONE);
        }
    }

    /**
     * 24 recruits wait at the rim for their owner across the ravine. At tick 100 a husk (a zombie that
     * doesn't burn by day) appears on the far rim beside the owner and every recruit is set on it; at
     * tick 160 it is moved down onto the ravine floor, as one that walked in. Measured at tick 400:
     * how many went over the edge or down into the ravine, and how close the rest stand.
     */
    private static void crowdWithATargetInTheRavine(GameTestHelper helper, boolean ledges) {
        deepRavine(helper);
        if (ledges) ledgeStaircase(helper);
        ServerPlayer owner = owner(helper, 24.5, 10, 30.5);
        pin(helper, owner, 24.5, 10, 30.5);
        int size = 24;
        List<AbstractRecruitEntity> crowd = new ArrayList<>();
        for (int i = 0; i < size; i++) crowd.add(followingRecruit(helper, owner, 12 + i, 9, 4));
        net.minecraft.world.entity.monster.Husk[] husk = {null};
        helper.runAfterDelay(1, () -> assertFollowPreconditions(crowd.get(0)));
        helper.runAtTickTime(100, () -> {
            husk[0] = helper.spawn(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(26, 9, 28));
            husk[0].setPersistenceRequired();
            crowd.forEach(recruit -> recruit.setTarget(husk[0]));
        });
        helper.runAtTickTime(160, () -> {
            net.minecraft.world.phys.Vec3 floor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(26.5, 1, 23.5));
            husk[0].teleportTo(floor.x, floor.y, floor.z);
            crowd.forEach(recruit -> { if (recruit.isAlive()) recruit.setTarget(husk[0]); });
        });
        helper.runAtTickTime(MEASURE_AT, () -> {
            int[] atEnd = new int[LOOK + 2];
            int down = 0;
            for (AbstractRecruitEntity recruit : crowd) {
                if (!recruit.isAlive() || relative(helper, recruit).y < 8.5) {
                    down++;
                    continue;
                }
                atEnd[toDrop(helper, recruit)]++;
            }
            String counts = "went over or down " + down + " of " + size + "; the rest by blocks from the drop " + histogram(atEnd);
            Main.LOGGER.info("[KhaosKorps measure] {} recruits set on a target in a deep ravine{}: {}", size,
                    ledges ? " with a ledge staircase" : "", counts);
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (husk[0] != null) husk[0].discard();
            if (down > 0 || atEnd[1] > 0) throw new GameTestAssertException(size + " recruits set on a target in the ravine: " + counts);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdSetOnATargetInASheerRavineStaysOutOfIt(GameTestHelper helper) {
        crowdWithATargetInTheRavine(helper, false);
    }

    @GameTest(template = ARENA, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdSetOnATargetInARavineWithLedgesStaysOutOfIt(GameTestHelper helper) {
        crowdWithATargetInTheRavine(helper, true);
    }

    @GameTest(template = ARENA, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aLoneRecruitAtADeepRavineWaitsBackFromTheEdge(GameTestHelper helper) {
        crowdAtTheRavine(helper, 1);
    }

    @GameTest(template = ARENA, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdOf24AtADeepRavineWaitsBackFromTheEdge(GameTestHelper helper) {
        crowdAtTheRavine(helper, 24);
    }

    @GameTest(template = ARENA, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdOf48AtADeepRavineWaitsBackFromTheEdge(GameTestHelper helper) {
        crowdAtTheRavine(helper, 48);
    }
}
